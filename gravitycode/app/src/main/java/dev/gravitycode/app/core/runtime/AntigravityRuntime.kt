package dev.gravitycode.app.core.runtime

import android.content.Context
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.workspace.GitChange
import dev.gravitycode.app.core.workspace.GitCommit
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AntigravityRuntime(context: Context) : AgentRuntime {
    private val provisioner = RuntimeProvisioner(context)
    val auth = AntigravityAuth(provisioner)
    private val process = AtomicReference<Process?>(null)
    private val terminals = ConcurrentHashMap<String, TerminalHandle>()

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
            val result = runGuest(workspace, "/usr/bin/git", listOf("clone", clean, "/workspace/$repoName"), 180)
            require(result.exitCode == 0) { result.output.takeLast(3000).ifBlank { "git clone gagal" } }
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
        val changes = changedLines.take(200).mapNotNull { line ->
            if (line.length < 3) return@mapNotNull null
            val xy = line.take(2)
            GitChange(
                status = xy.trim().ifBlank { "?" },
                path = line.drop(3).substringAfter(" -> ").trim(),
                staged = xy.getOrNull(0)?.let { it != ' ' && it != '?' } == true,
                working = xy.getOrNull(1)?.let { it != ' ' } == true || xy == "??",
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

    suspend fun gitStage(projectRoot: File, path: String): Result<String> = gitResult(projectRoot, listOf("add", "--", safeRelativePath(projectRoot, path)))
    suspend fun gitUnstage(projectRoot: File, path: String): Result<String> = gitResult(projectRoot, listOf("restore", "--staged", "--", safeRelativePath(projectRoot, path)), allowEmpty = true)
    suspend fun gitStageAll(projectRoot: File): Result<String> = gitResult(projectRoot, listOf("add", "-A"))
    suspend fun gitUnstageAll(projectRoot: File): Result<String> = gitResult(projectRoot, listOf("reset"), allowEmpty = true)
    suspend fun gitCommit(projectRoot: File, message: String): Result<String> {
        require(message.trim().isNotEmpty()) { "Commit message kosong" }
        return gitResult(projectRoot, listOf("commit", "-m", message.trim()))
    }
    suspend fun gitPull(projectRoot: File): Result<String> = gitResult(projectRoot, listOf("pull", "--ff-only"))
    suspend fun gitPush(projectRoot: File): Result<String> = gitResult(projectRoot, listOf("push"))
    suspend fun gitFetch(projectRoot: File): Result<String> = gitResult(projectRoot, listOf("fetch", "--prune"))
    suspend fun gitCreateBranch(projectRoot: File, branch: String): Result<String> {
        val clean = validateBranch(branch)
        return gitResult(projectRoot, listOf("switch", "-c", clean))
    }
    suspend fun gitSwitchBranch(projectRoot: File, branch: String): Result<String> = gitResult(projectRoot, listOf("switch", validateBranch(branch)))

    suspend fun gitBranches(projectRoot: File): List<String> = withContext(Dispatchers.IO) {
        val result = runGit(projectRoot, listOf("branch", "--format=%(refname:short)"))
        if (result.exitCode != 0) emptyList() else result.output.lines().map(String::trim).filter(String::isNotBlank).distinct().take(80)
    }

    suspend fun gitHistory(projectRoot: File): List<GitCommit> = withContext(Dispatchers.IO) {
        val result = runGit(projectRoot, listOf("log", "-30", "--pretty=format:%h%x1f%ad%x1f%s", "--date=short"))
        if (result.exitCode != 0) emptyList() else result.output.lines().mapNotNull { line ->
            val parts = line.split('\u001f', limit = 3)
            if (parts.size == 3) GitCommit(parts[0], parts[1], parts[2]) else null
        }
    }

    suspend fun gitRemoteUrl(projectRoot: File): String? = withContext(Dispatchers.IO) {
        runGit(projectRoot, listOf("remote", "get-url", "origin")).takeIf { it.exitCode == 0 }?.output?.trim()?.takeIf(String::isNotBlank)
    }

    private suspend fun gitResult(projectRoot: File, args: List<String>, allowEmpty: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(File(projectRoot, ".git").exists()) { "Bukan Git repository" }
            val result = runGit(projectRoot, args)
            require(result.exitCode == 0) { result.output.takeLast(3000).ifBlank { "git ${args.firstOrNull()} gagal" } }
            result.output.trim().ifBlank { if (allowEmpty) "OK" else "Done" }
        }
    }

    suspend fun loadDiff(projectRoot: File, path: String, statusHint: String? = null): Result<GitDiff> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(File(projectRoot, ".git").exists()) { "Bukan Git repository" }
            val safePath = safeRelativePath(projectRoot, path)
            val status = statusHint?.takeIf { it.isNotBlank() } ?: runGit(projectRoot, listOf("status", "--porcelain=v1", "--", safePath)).output.lineSequence().firstOrNull()?.take(2)?.trim().orEmpty()
            val patch = if (status == "??") {
                val target = File(projectRoot, safePath)
                when {
                    !target.exists() -> "File tidak ditemukan."
                    target.isDirectory -> "Untracked directory: $safePath"
                    target.length() > DIFF_FILE_LIMIT -> "Untracked file terlalu besar untuk diff mobile (${target.length()} bytes)."
                    else -> {
                        val bytes = target.readBytes()
                        if (bytes.take(4096).any { it == 0.toByte() }) "Binary file: $safePath"
                        else {
                            val lines = String(bytes, Charsets.UTF_8).split('\n')
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
            GitDiff(
                path = safePath,
                status = status.ifBlank { "M" },
                patch = patch.take(DIFF_OUTPUT_LIMIT),
                additions = patch.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") },
                deletions = patch.lineSequence().count { it.startsWith("-") && !it.startsWith("---") },
                binary = patch.startsWith("Binary file:"),
            )
        }
    }

    suspend fun revertPath(projectRoot: File, path: String, statusHint: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(File(projectRoot, ".git").exists()) { "Bukan Git repository" }
            val safePath = safeRelativePath(projectRoot, path)
            val status = statusHint?.takeIf { it.isNotBlank() } ?: runGit(projectRoot, listOf("status", "--porcelain=v1", "--", safePath)).output.lineSequence().firstOrNull()?.take(2)?.trim().orEmpty()
            if (status == "??") {
                val target = File(projectRoot, safePath).canonicalFile
                require(target.path.startsWith(projectRoot.canonicalPath + File.separator)) { "Path di luar project" }
                require(target.exists() && target.deleteRecursively()) { "Gagal menghapus untracked path" }
            } else {
                val restore = runGit(projectRoot, listOf("restore", "--staged", "--worktree", "--", safePath))
                if (restore.exitCode != 0) {
                    val fallback = runGit(projectRoot, listOf("checkout", "HEAD", "--", safePath))
                    require(fallback.exitCode == 0) { (restore.output + "\n" + fallback.output).takeLast(1800) }
                }
            }
        }
    }

    suspend fun listModels(projectRoot: File): List<String> = withContext(Dispatchers.IO) {
        if (!provisioner.isReady()) return@withContext emptyList()
        val result = runGuest(projectRoot, "/usr/local/bin/agy", listOf("models"), 30)
        val regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,}")
        result.output.lines()
            .flatMap { regex.findAll(it).map { m -> m.value }.toList() }
            .filter { it.contains("gemini", true) || it.contains("claude", true) }
            .distinct()
            .take(16)
    }

    fun terminalSession(sessionId: String, projectRoot: File): Flow<TerminalChunk> = callbackFlow {
        if (!provisioner.isReady()) {
            trySend(TerminalChunk("Runtime belum siap.\n", closed = true, exitCode = -1))
            close()
            return@callbackFlow
        }
        stopTerminal(sessionId)
        val command = AntigravitySandbox.guestPtyCommand(provisioner, projectRoot, "/bin/sh", listOf("-i"))
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
        val writer = OutputStreamWriter(child.outputStream, Charsets.UTF_8)
        terminals[sessionId] = TerminalHandle(child, writer)
        trySend(TerminalChunk("\u001B[90m[GravityCode shell · ${projectRoot.name}]\u001B[0m\n"))
        launch(Dispatchers.IO) {
            var exitCode = -1
            runCatching {
                val buffer = ByteArray(4096)
                while (true) {
                    val read = child.inputStream.read(buffer)
                    if (read < 0) break
                    if (read > 0) trySend(TerminalChunk(String(buffer, 0, read, Charsets.UTF_8)))
                }
                exitCode = child.waitFor()
            }.onFailure { error -> trySend(TerminalChunk("\n[terminal error: ${error.message}]\n")) }
            terminals.remove(sessionId)?.let { runCatching { it.writer.close() } }
            trySend(TerminalChunk("\n[terminal exited $exitCode]\n", closed = true, exitCode = exitCode))
            close()
        }
        awaitClose {
            val handle = terminals.remove(sessionId)
            runCatching { handle?.writer?.close() }
            if (handle?.process?.isAlive == true) handle.process.destroyForcibly()
        }
    }

    fun sendTerminalInput(sessionId: String, input: String): Boolean {
        val handle = terminals[sessionId] ?: return false
        if (!handle.process.isAlive) return false
        return runCatching { handle.writer.write(input); handle.writer.flush(); true }.getOrDefault(false)
    }

    fun interruptTerminal(sessionId: String): Boolean = sendTerminalInput(sessionId, "\u0003")
    fun terminalTab(sessionId: String): Boolean = sendTerminalInput(sessionId, "\t")
    fun isTerminalAlive(sessionId: String): Boolean = terminals[sessionId]?.process?.isAlive == true

    fun stopTerminal(sessionId: String) {
        terminals.remove(sessionId)?.let { handle ->
            runCatching { handle.writer.close() }
            if (handle.process.isAlive) handle.process.destroyForcibly()
        }
    }

    fun stopAllTerminals() {
        terminals.keys.toList().forEach(::stopTerminal)
    }

    suspend fun runShell(projectRoot: File, shellCommand: String): Result<TerminalResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(provisioner.isReady()) { "Runtime belum siap" }
            require(shellCommand.isNotBlank()) { "Command kosong" }
            val result = runGuest(projectRoot, "/bin/sh", listOf("-lc", shellCommand), 120)
            TerminalResult(result.exitCode, result.output)
        }
    }

    override fun run(prompt: String, workspace: File, permissionMode: PermissionMode): Flow<AgentEvent> =
        run(prompt, workspace, permissionMode, conversationId = null, model = null)

    fun run(
        prompt: String,
        workspace: File,
        permissionMode: PermissionMode,
        conversationId: String?,
        model: String?,
    ): Flow<AgentEvent> = callbackFlow {
        if (!provisioner.isReady()) {
            trySend(AgentEvent.Error("Runtime belum siap. Tap Setup runtime.")); close(); return@callbackFlow
        }
        if (auth.state.value !is AuthState.SignedIn) {
            trySend(AgentEvent.Error("Login Google Antigravity dulu sebelum menjalankan agent.")); close(); return@callbackFlow
        }
        workspace.mkdirs()
        val permissionArgs = when (permissionMode) {
            PermissionMode.PLAN_ONLY -> listOf("--mode", "plan")
            PermissionMode.ACCEPT_EDITS -> listOf("--mode", "accept-edits")
            PermissionMode.FULL_ACCESS -> listOf("--dangerously-skip-permissions")
        }
        val args = buildList {
            add("--output-format"); add("stream-json")
            add("--print-timeout"); add("24h")
            if (!conversationId.isNullOrBlank()) { add("--conversation"); add(conversationId) }
            if (!model.isNullOrBlank()) { add("--model"); add(model) }
            addAll(permissionArgs)
            add("--print"); add(prompt)
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
            trySend(AgentEvent.Error("Gagal menjalankan agy: ${error.message}")); close(error); return@callbackFlow
        }
        process.set(child)
        trySend(AgentEvent.Status("Antigravity bekerja di ${workspace.name}"))
        trySend(AgentEvent.Tool("agy", "Agent started", ToolState.RUNNING))

        launch(Dispatchers.IO) {
            var gotResult = false
            var streamedText = false
            var emittedConversation: String? = null
            runCatching {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        val conversation = root.optString("conversation_id").takeIf { it.isNotBlank() }
                            ?: root.optJSONObject("result")?.optString("conversation_id")?.takeIf { it.isNotBlank() }
                        if (conversation != null && conversation != emittedConversation) {
                            emittedConversation = conversation
                            trySend(AgentEvent.Conversation(conversation))
                        }
                        when (root.optString("event")) {
                            "init" -> Unit
                            "step_update" -> {
                                val step = root.optJSONObject("step_update") ?: return@forEach
                                when (step.optString("step_type")) {
                                    "agent_response" -> {
                                        val delta = step.optString("text_delta")
                                        if (delta.isNotEmpty()) { streamedText = true; trySend(AgentEvent.Output(delta)) }
                                    }
                                    "tool" -> {
                                        val info = step.optJSONObject("tool_info")
                                        val name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: step.optString("tool_name", "tool")
                                        val done = step.optString("state") == "DONE"
                                        val errorText = info?.opt("error")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                                        val output = info?.optString("output").orEmpty()
                                        val detail = output.takeLast(5000).ifBlank { if (done) "Completed" else "Running" }
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
                                val result = root.optJSONObject("result") ?: root
                                val status = result.optString("status")
                                val response = result.optString("response")
                                val error = result.optString("error")
                                if (status.isNotBlank() && status != "SUCCESS") trySend(AgentEvent.Error(error.ifBlank { response.ifBlank { status } }))
                                else {
                                    if (!streamedText && response.isNotBlank()) trySend(AgentEvent.Output(response))
                                    trySend(AgentEvent.Tool("agy", "Turn completed", ToolState.SUCCESS))
                                }
                            }
                        }
                    }
                }
                val exit = child.waitFor()
                if (exit != 0 && !gotResult) {
                    trySend(AgentEvent.Error("agy exit $exit: ${stderr.readTextOrNull()?.takeLast(2200).orEmpty()}"))
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

    private fun runGit(projectRoot: File, args: List<String>): CommandResult = runGuest(projectRoot, "/usr/bin/git", args, 90)

    private fun runGuest(projectRoot: File, executable: String, args: List<String>, timeoutSeconds: Long): CommandResult {
        val command = AntigravitySandbox.guestCommand(provisioner, projectRoot, executable, args)
        val child = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
            .start()
        val output = StringBuilder()
        val reader = child.inputStream.bufferedReader()
        val started = System.nanoTime()
        while (child.isAlive && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < timeoutSeconds) {
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
        return CommandResult(runCatching { child.exitValue() }.getOrDefault(-1), output.toString())
    }

    private fun safeRelativePath(projectRoot: File, path: String): String {
        val root = projectRoot.canonicalFile
        val target = File(root, path).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Path berada di luar repository" }
        return target.relativeTo(root).path.replace(File.separatorChar, '/')
    }

    private fun validateBranch(branch: String): String {
        val clean = branch.trim()
        require(clean.matches(Regex("[A-Za-z0-9._/-]+")) && !clean.contains("..")) { "Nama branch invalid" }
        return clean
    }

    data class TerminalResult(val exitCode: Int, val output: String)
    data class TerminalChunk(val text: String, val closed: Boolean = false, val exitCode: Int? = null)
    private data class TerminalHandle(val process: Process, val writer: OutputStreamWriter)
    private data class CommandResult(val exitCode: Int, val output: String)
    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private companion object {
        const val TERMINAL_OUTPUT_LIMIT = 300_000
        const val DIFF_OUTPUT_LIMIT = 500_000
        const val DIFF_FILE_LIMIT = 1_500_000L
    }
}
