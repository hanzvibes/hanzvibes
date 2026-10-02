package dev.gravitycode.app.core.runtime

import android.content.Context
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.workspace.GitChange
import dev.gravitycode.app.core.workspace.GitDiff
import dev.gravitycode.app.core.workspace.ProjectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AntigravityRuntime(context: Context) : AgentRuntime {
    private val provisioner = RuntimeProvisioner(context)
    val auth = AntigravityAuth(provisioner)
    private val process = AtomicReference<Process?>(null)
    private val terminalProcess = AtomicReference<Process?>(null)
    private val terminalWriter = AtomicReference<OutputStreamWriter?>(null)

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
            val completed = child.waitFor(3, TimeUnit.MINUTES)
            if (!completed && child.isAlive) child.destroyForcibly()
            require(completed && child.exitValue() == 0) { output.takeLast(2000).ifBlank { "git clone gagal" } }
            repoName
        }
    }

    suspend fun inspectRepository(projectRoot: File): ProjectStatus = withContext(Dispatchers.IO) {
        val repository = File(projectRoot, ".git").exists()
        val base = ProjectStatus(
            projectName = projectRoot.name.ifBlank { "Workspace" },
            workspacePath = if (repository) "/workspace/${projectRoot.name}" else "/workspace",
            repository = repository,
        )
        if (!base.repository || !provisioner.isReady()) return@withContext base

        val statusResult = runGit(projectRoot, listOf("status", "--porcelain=v1", "--branch", "--untracked-files=normal"))
        if (statusResult.exitCode != 0) return@withContext base
        val lines = statusResult.output.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.takeIf { it.startsWith("## ") }?.removePrefix("## ").orEmpty()
        val branchRaw = header.substringBefore("...").substringBefore(" [").trim()
        val branch = when {
            branchRaw.isBlank() -> null
            branchRaw.startsWith("HEAD") -> "detached"
            else -> branchRaw
        }
        val ahead = Regex("ahead (\\d+)").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val behind = Regex("behind (\\d+)").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val changedLines = lines.drop(1)
        val changes = changedLines.take(150).mapNotNull { line ->
            if (line.length < 3) return@mapNotNull null
            GitChange(
                status = line.take(2).trim().ifBlank { "?" },
                path = line.drop(3).substringAfter(" -> ").trim(),
            )
        }

        val logResult = runGit(projectRoot, listOf("log", "-1", "--pretty=format:%h%x1f%s"))
        val logParts = logResult.output.trim().split('\u001f', limit = 2)
        base.copy(
            branch = branch,
            changedFiles = changedLines.size,
            changedPaths = changes,
            ahead = ahead,
            behind = behind,
            head = logParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            latestCommit = logParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            statusKnown = true,
        )
    }

    suspend fun loadDiff(projectRoot: File, path: String, statusHint: String? = null): Result<GitDiff> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(File(projectRoot, ".git").exists()) { "Bukan Git repository" }
            val safePath = safeRelativePath(projectRoot, path)
            val status = statusHint?.takeIf { it.isNotBlank() } ?: runGit(
                projectRoot,
                listOf("status", "--porcelain=v1", "--", safePath),
            ).output.lineSequence().firstOrNull()?.take(2)?.trim().orEmpty()

            val patch = if (status == "??") {
                val target = File(projectRoot, safePath)
                when {
                    !target.exists() -> "File tidak ditemukan."
                    target.isDirectory -> "Untracked directory: $safePath"
                    target.length() > DIFF_FILE_LIMIT -> "Untracked file terlalu besar untuk diff mobile (${target.length()} bytes)."
                    else -> {
                        val bytes = target.readBytes()
                        if (bytes.take(4096).any { it == 0.toByte() }) {
                            "Binary file: $safePath"
                        } else {
                            val text = String(bytes, Charsets.UTF_8)
                            val lines = if (text.isEmpty()) emptyList() else text.split('\n')
                            buildString {
                                appendLine("diff --git a/$safePath b/$safePath")
                                appendLine("new file mode 100644")
                                appendLine("--- /dev/null")
                                appendLine("+++ b/$safePath")
                                appendLine("@@ -0,0 +1,${lines.size} @@")
                                lines.forEach { append('+').appendLine(it) }
                            }
                        }
                    }
                }
            } else {
                val result = runGit(projectRoot, listOf("diff", "--no-ext-diff", "--no-color", "HEAD", "--", safePath))
                require(result.exitCode == 0) { result.output.takeLast(1600).ifBlank { "git diff gagal" } }
                result.output.ifBlank { "Tidak ada working-tree diff untuk $safePath." }
            }

            val additions = patch.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
            val deletions = patch.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
            GitDiff(
                path = safePath,
                status = status.ifBlank { "M" },
                patch = patch.take(DIFF_OUTPUT_LIMIT),
                additions = additions,
                deletions = deletions,
                binary = patch.startsWith("Binary file:"),
            )
        }
    }

    suspend fun revertPath(projectRoot: File, path: String, statusHint: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(File(projectRoot, ".git").exists()) { "Bukan Git repository" }
            val safePath = safeRelativePath(projectRoot, path)
            val status = statusHint?.takeIf { it.isNotBlank() } ?: runGit(
                projectRoot,
                listOf("status", "--porcelain=v1", "--", safePath),
            ).output.lineSequence().firstOrNull()?.take(2)?.trim().orEmpty()

            if (status == "??") {
                val target = File(projectRoot, safePath).canonicalFile
                val root = projectRoot.canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Path di luar project" }
                require(target.exists()) { "File tidak ditemukan" }
                require(target.deleteRecursively()) { "Gagal menghapus untracked path" }
            } else {
                val restore = runGit(projectRoot, listOf("restore", "--staged", "--worktree", "--", safePath))
                if (restore.exitCode != 0) {
                    val fallback = runGit(projectRoot, listOf("checkout", "HEAD", "--", safePath))
                    require(fallback.exitCode == 0) { (restore.output + "\n" + fallback.output).takeLast(1800) }
                }
            }
        }
    }

    fun terminalSession(projectRoot: File): Flow<TerminalChunk> = callbackFlow {
        if (!provisioner.isReady()) {
            trySend(TerminalChunk("Runtime belum siap.\n", closed = true, exitCode = -1))
            close()
            return@callbackFlow
        }

        stopTerminal()
        val command = AntigravitySandbox.guestPtyCommand(
            provisioner = provisioner,
            workspace = projectRoot,
            executable = "/bin/sh",
            arguments = listOf("-i"),
        )
        val child = runCatching {
            ProcessBuilder(command)
                .directory(provisioner.runtimeDirectory)
                .redirectErrorStream(true)
                .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
                .start()
        }.getOrElse { error ->
            trySend(TerminalChunk("Gagal membuka terminal: ${error.message}\n", closed = true, exitCode = -1))
            close(error)
            return@callbackFlow
        }
        terminalProcess.set(child)
        val writer = OutputStreamWriter(child.outputStream, Charsets.UTF_8)
        terminalWriter.set(writer)
        trySend(TerminalChunk("\u001B[90m[GravityCode shell · ${projectRoot.name}]\u001B[0m\n"))

        launch(Dispatchers.IO) {
            var exitCode = -1
            runCatching {
                val input = child.inputStream
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) trySend(TerminalChunk(String(buffer, 0, read, Charsets.UTF_8)))
                }
                exitCode = child.waitFor()
            }.onFailure { error ->
                trySend(TerminalChunk("\n[terminal error: ${error.message}]\n"))
            }
            terminalWriter.compareAndSet(writer, null)
            terminalProcess.compareAndSet(child, null)
            trySend(TerminalChunk("\n[terminal exited $exitCode]\n", closed = true, exitCode = exitCode))
            close()
        }

        awaitClose {
            terminalWriter.compareAndSet(writer, null)
            runCatching { writer.close() }
            if (child.isAlive) child.destroyForcibly()
            terminalProcess.compareAndSet(child, null)
        }
    }

    fun sendTerminalInput(input: String): Boolean {
        val child = terminalProcess.get()
        val writer = terminalWriter.get()
        if (child?.isAlive != true || writer == null) return false
        return runCatching {
            writer.write(input)
            writer.flush()
            true
        }.getOrDefault(false)
    }

    fun interruptTerminal(): Boolean = sendTerminalInput("\u0003")
    fun terminalTab(): Boolean = sendTerminalInput("\t")
    fun isTerminalAlive(): Boolean = terminalProcess.get()?.isAlive == true

    fun stopTerminal() {
        terminalWriter.getAndSet(null)?.let { runCatching { it.close() } }
        terminalProcess.getAndSet(null)?.let { child -> if (child.isAlive) child.destroyForcibly() }
    }

    suspend fun runShell(projectRoot: File, shellCommand: String): Result<TerminalResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(shellCommand.isNotBlank()) { "Command kosong" }
            val command = AntigravitySandbox.guestCommand(
                provisioner,
                projectRoot,
                "/bin/sh",
                listOf("-lc", shellCommand),
            )
            val child = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
                .start()
            val output = StringBuilder()
            val reader = child.inputStream.bufferedReader()
            val started = System.nanoTime()
            while (child.isAlive && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 120) {
                while (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (output.length < TERMINAL_OUTPUT_LIMIT) output.appendLine(line)
                }
                Thread.sleep(20)
            }
            if (child.isAlive) child.destroyForcibly()
            while (reader.ready() && output.length < TERMINAL_OUTPUT_LIMIT) {
                val line = reader.readLine() ?: break
                output.appendLine(line)
            }
            val completed = !child.isAlive
            TerminalResult(
                exitCode = if (completed) runCatching { child.exitValue() }.getOrDefault(-1) else -1,
                output = if (completed) output.toString() else output.append("\n[command timed out]").toString(),
            )
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
        trySend(AgentEvent.Status("Antigravity bekerja di ${workspace.name}"))
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
                                        val name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: step.optString("tool_name", "tool")
                                        val done = step.optString("state") == "DONE"
                                        val errorText = info?.opt("error")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                                        val output = info?.optString("output").orEmpty()
                                        val detail = output.takeLast(4000).ifBlank { if (done) "Completed" else "Running" }
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

    private fun runGit(projectRoot: File, args: List<String>): CommandResult {
        val command = AntigravitySandbox.guestCommand(provisioner, projectRoot, "/usr/bin/git", args)
        val child = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
            .start()
        val output = child.inputStream.bufferedReader().use { it.readText() }
        val completed = child.waitFor(20, TimeUnit.SECONDS)
        if (!completed && child.isAlive) child.destroyForcibly()
        return CommandResult(if (completed) child.exitValue() else -1, output)
    }

    private fun safeRelativePath(projectRoot: File, path: String): String {
        val root = projectRoot.canonicalFile
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Path berada di luar repository" }
        return target.relativeTo(root).path.replace(File.separatorChar, '/')
    }

    data class TerminalResult(val exitCode: Int, val output: String)
    data class TerminalChunk(val text: String, val closed: Boolean = false, val exitCode: Int? = null)

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private companion object {
        const val TERMINAL_OUTPUT_LIMIT = 200_000
        const val DIFF_OUTPUT_LIMIT = 400_000
        const val DIFF_FILE_LIMIT = 1_000_000L
    }
}