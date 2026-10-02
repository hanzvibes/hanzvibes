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
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime bridge for Antigravity CLI.
 *
 * v0.1 deliberately keeps process transport isolated behind AgentRuntime so a real PTY/JNI
 * implementation can replace this transport without touching Compose or ViewModel code.
 *
 * Binary lookup order:
 *   1. files/bin/agy
 *   2. files/usr/bin/agy
 */
class AntigravityRuntime(
    private val context: Context,
) : AgentRuntime {

    private val process = AtomicReference<Process?>(null)

    private fun candidates(): List<File> = listOf(
        File(context.filesDir, "bin/agy"),
        File(context.filesDir, "usr/bin/agy"),
    )

    private fun binary(): File? = candidates().firstOrNull { it.isFile && it.canExecute() }

    override fun status(): RuntimeStatus {
        val binary = binary()
        return if (binary != null) {
            RuntimeStatus(true, binary.absolutePath, "Antigravity CLI ready")
        } else {
            RuntimeStatus(
                false,
                null,
                "agy belum terpasang. Provision binary Linux/Android-compatible ke files/bin/agy.",
            )
        }
    }

    override fun run(
        prompt: String,
        workspace: File,
        permissionMode: PermissionMode,
    ): Flow<AgentEvent> = callbackFlow {
        val binary = binary()
        if (binary == null) {
            trySend(AgentEvent.Error(status().message))
            close()
            return@callbackFlow
        }

        workspace.mkdirs()
        trySend(AgentEvent.Status("Starting Antigravity in ${workspace.name}"))
        trySend(AgentEvent.Tool("agy", "Launching agent process", ToolState.RUNNING))

        val command = listOf(binary.absolutePath)

        val child = runCatching {
            ProcessBuilder(command)
                .directory(workspace)
                .redirectErrorStream(true)
                .apply {
                    environment()["GRAVITYCODE_PERMISSION_MODE"] = permissionMode.name
                    environment()["TERM"] = "xterm-256color"
                }
                .start()
        }.getOrElse { error ->
            trySend(AgentEvent.Error("Gagal menjalankan agy: ${error.message}"))
            close(error)
            return@callbackFlow
        }

        process.set(child)

        launch(Dispatchers.IO) {
            runCatching {
                child.outputStream.bufferedWriter().use { writer ->
                    writer.write(prompt)
                    writer.newLine()
                    writer.flush()
                }
            }
        }

        launch(Dispatchers.IO) {
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> trySend(AgentEvent.Output(line)) }
            }
            val exit = child.waitFor()
            process.compareAndSet(child, null)
            val state = if (exit == 0) ToolState.SUCCESS else ToolState.FAILED
            trySend(AgentEvent.Tool("agy", "Process exited with code $exit", state))
            close()
        }

        awaitClose {
            if (child.isAlive) child.destroy()
            process.compareAndSet(child, null)
        }
    }

    override fun cancel() {
        process.getAndSet(null)?.destroy()
    }
}
