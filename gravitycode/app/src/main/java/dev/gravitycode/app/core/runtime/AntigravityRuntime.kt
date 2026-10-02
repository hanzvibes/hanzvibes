package dev.gravitycode.app.core.runtime

import android.content.Context
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AntigravityRuntime(context: Context) : AgentRuntime {
    private val provisioner = RuntimeProvisioner(context)
    val auth = AntigravityAuth(provisioner)
    private val process = AtomicReference<Process?>(null)

    override fun status(): RuntimeStatus = RuntimeStatus(
        available = provisioner.isReady(),
        executable = provisioner.agyBinary().takeIf { it.isFile }?.absolutePath,
        message = provisioner.statusMessage(),
    )

    suspend fun setup(onProgress: (Float, String) -> Unit) {
        provisioner.install(onProgress)
        auth.refresh()
    }

    fun beginAuth(workspace: File) = auth.start(workspace)
    fun submitAuthCode(code: String) = auth.submitCode(code)
    fun cancelAuth() = auth.cancel()

    suspend fun cloneRepository(url: String, workspace: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            val clean = url.trim()
            require(clean.startsWith("https://") || clean.startsWith("git@")) { "Gunakan URL repository Git HTTPS atau SSH" }
            val repoName = clean.substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val destination = File(workspace, repoName)
            require(!destination.exists()) { "Folder $repoName sudah ada" }
            val command = AntigravitySandbox.guestCommand(provisioner, workspace, "/usr/bin/git", listOf("clone", clean, "/workspace/$repoName"))
            val child = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
                .start()
            val output = child.inputStream.bufferedReader().use { it.readText() }
            require(child.waitFor(3, TimeUnit.MINUTES) && child.exitValue() == 0) { output.takeLast(2000).ifBlank { "git clone gagal" } }
            repoName
        }
    }

    override fun run(prompt: String, workspace: File, permissionMode: PermissionMode): Flow<AgentEvent> = callbackFlow {
        if (!provisioner.isReady()) {
            trySend(AgentEvent.Error("Runtime belum siap. Tap Setup runtime."))
            close()
            return@callbackFlow
        }
        if (auth.state.value !is AuthState.SignedIn) {
            trySend(AgentEvent.Error("Login Google Antigravity dulu sebelum menjalankan agent."))
            close()
            return@callbackFlow
        }

        workspace.mkdirs()
        val permissionArgs = when (permissionMode) {
            PermissionMode.PLAN_ONLY -> listOf("--mode", "plan")
            PermissionMode.ACCEPT_EDITS -> listOf("--mode", "accept-edits")
            PermissionMode.FULL_ACCESS -> listOf("--dangerously-skip-permissions")
        }
        val args = buildList {
            add("--output-format")
            add("stream-json")
            addAll(permissionArgs)
            add("--print")
            add(prompt)
        }
        val command = AntigravitySandbox.agyCommand(provisioner, workspace, args, pty = false)
        val stderr = File(provisioner.runtimeDirectory, "agy-stderr.log")
        val child = runCatching {
            ProcessBuilder(command)
                .directory(provisioner.runtimeDirectory)
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr))
                .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
                .start()
        }.getOrElse { error ->
            trySend(AgentEvent.Error("Gagal menjalankan agy: ${error.message}"))
            close(error)
            return@callbackFlow
        }
        process.set(child)
        trySend(AgentEvent.Status("Antigravity menjalankan task di /workspace"))
        trySend(AgentEvent.Tool("agy", "Agent started", ToolState.RUNNING))

        launch(Dispatchers.IO) {
            var gotResult = false
            var streamedText = false
            runCatching {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        when (root.optString("event")) {
                            "step_update" -> {
                                val step = root.optJSONObject("step_update") ?: return@forEach
                                when (step.optString("step_type")) {
                                    "agent_response" -> {
                                        val delta = step.optString("text_delta")
                                        if (delta.isNotEmpty()) {
                                            streamedText = true
                                            trySend(AgentEvent.Output(delta))
                                        }
                                    }
                                    "tool" -> {
                                        val info = step.optJSONObject("tool_info")
                                        val name = info?.optString("name")?.ifBlank { null } ?: step.optString("tool_name", "tool")
                                        val done = step.optString("state") == "DONE"
                                        val errorText = info?.opt("error")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                                        val output = info?.optString("output").orEmpty()
                                        val detail = output.takeLast(1200).ifBlank { if (done) "Completed" else "Running" }
                                        trySend(AgentEvent.Tool(name, errorText ?: detail, when {
                                            !done -> ToolState.RUNNING
                                            errorText != null -> ToolState.FAILED
                                            else -> ToolState.SUCCESS
                                        }))
                                    }
                                }
                            }
                            "result" -> {
                                gotResult = true
                                val result = root.optJSONObject("result") ?: return@forEach
                                val status = result.optString("status")
                                val response = result.optString("response")
                                val error = result.optString("error")
                                if (status.isNotBlank() && status != "SUCCESS") {
                                    trySend(AgentEvent.Error(error.ifBlank { response.ifBlank { status } }))
                                } else {
                                    if (!streamedText && response.isNotBlank()) trySend(AgentEvent.Output(response))
                                    trySend(AgentEvent.Tool("agy", "Turn completed", ToolState.SUCCESS))
                                }
                            }
                        }
                    }
                }
                val exit = child.waitFor()
                if (exit != 0 && !gotResult) {
                    trySend(AgentEvent.Error("agy exit $exit: ${stderr.readTextOrNull()?.takeLast(1800).orEmpty()}"))
                    trySend(AgentEvent.Tool("agy", "Process failed", ToolState.FAILED))
                }
            }.onFailure { error -> trySend(AgentEvent.Error(error.message ?: "Antigravity runtime error")) }
            process.compareAndSet(child, null)
            close()
        }

        awaitClose {
            if (child.isAlive) child.destroyForcibly()
            process.compareAndSet(child, null)
        }
    }

    override fun cancel() {
        process.getAndSet(null)?.let { if (it.isAlive) it.destroyForcibly() }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
