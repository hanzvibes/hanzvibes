package dev.gravitycode.app.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface AuthState {
    data object SignedOut : AuthState
    data object Starting : AuthState
    data class AwaitingCode(val url: String) : AuthState
    data object Verifying : AuthState
    data object SignedIn : AuthState
    data class Error(val message: String) : AuthState
}

class AntigravityAuth(private val provisioner: RuntimeProvisioner) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AuthState>(if (tokenFile().isFile) AuthState.SignedIn else AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private val transcript = StringBuilder()
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    fun refresh() {
        if (tokenFile().isFile) _state.value = AuthState.SignedIn
        else if (_state.value is AuthState.SignedIn) _state.value = AuthState.SignedOut
    }

    fun start(workspace: File) {
        if (!provisioner.isReady()) {
            _state.value = AuthState.Error("Setup runtime dulu sebelum login")
            return
        }
        if (tokenFile().isFile) {
            _state.value = AuthState.SignedIn
            return
        }
        if (process?.isAlive == true) return
        _state.value = AuthState.Starting
        codeSubmitted = false
        synchronized(transcript) { transcript.clear() }
        scope.launch {
            runCatching {
                val child = ProcessBuilder(AntigravitySandbox.agyCommand(provisioner, workspace, emptyList(), pty = true))
                    .redirectErrorStream(true)
                    .apply { environment().putAll(AntigravitySandbox.environment(provisioner)) }
                    .start()
                process = child
                launch { driveLoginMenu(child) }
                val buffer = CharArray(1024)
                child.inputStream.bufferedReader().use { reader ->
                    while (child.isAlive) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val chunk = String(buffer, 0, count)
                        if (!codeSubmitted) synchronized(transcript) {
                            transcript.append(chunk)
                            if (transcript.length > 120_000) transcript.delete(0, transcript.length - 120_000)
                        }
                        inspectOutput(chunk)
                    }
                }
                val exit = runCatching { child.waitFor() }.getOrDefault(-1)
                if (_state.value !is AuthState.SignedIn && _state.value !is AuthState.Verifying) {
                    _state.value = if (tokenFile().isFile) AuthState.SignedIn else AuthState.Error("Login Antigravity berhenti (exit $exit)")
                }
            }.onFailure { _state.value = AuthState.Error(it.message ?: "Gagal memulai login Antigravity") }
        }
    }

    fun submitCode(code: String) {
        val child = process ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty() || !child.isAlive) return
        codeSubmitted = true
        _state.value = AuthState.Verifying
        scope.launch {
            runCatching {
                child.outputStream.write((trimmed + "\r").toByteArray())
                child.outputStream.flush()
            }.onFailure {
                _state.value = AuthState.Error(it.message ?: "Gagal mengirim authorization code")
                return@launch
            }
            repeat(60) {
                if (tokenFile().isFile) {
                    _state.value = AuthState.SignedIn
                    stopProcess()
                    return@launch
                }
                delay(2_000)
            }
            if (_state.value is AuthState.Verifying) _state.value = AuthState.Error("Verifikasi login timeout")
        }
    }

    fun cancel() {
        stopProcess()
        if (_state.value !is AuthState.SignedIn) _state.value = AuthState.SignedOut
    }

    private suspend fun driveLoginMenu(child: Process) {
        repeat(225) {
            if (!child.isAlive) return
            val clean = cleanTranscript()
            if (clean.contains("Select login method", ignoreCase = true)) {
                child.outputStream.write('\r'.code)
                child.outputStream.flush()
                return
            }
            delay(400)
        }
    }

    private fun inspectOutput(chunk: String) {
        if (tokenFile().isFile || SIGNED_IN_MARKERS.any { chunk.contains(it, ignoreCase = true) }) {
            _state.value = AuthState.SignedIn
            stopProcess()
            return
        }
        if (_state.value is AuthState.Verifying) return
        val clean = cleanTranscript()
        val start = clean.lastIndexOf(OAUTH_PREFIX)
        if (start >= 0) {
            val tail = clean.substring(start)
            val url = tail.takeWhile { !it.isWhitespace() }
            if (url.length > OAUTH_PREFIX.length) _state.value = AuthState.AwaitingCode(url)
        }
    }

    private fun cleanTranscript(): String = ANSI.replace(synchronized(transcript) { transcript.toString() }, "")

    private fun tokenFile(): File = File(provisioner.rootfs, "root/.gemini/antigravity-cli/antigravity-oauth-token")

    private fun stopProcess() {
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
    }

    companion object {
        private const val OAUTH_PREFIX = "https://accounts.google.com/o/oauth2/auth?"
        private val SIGNED_IN_MARKERS = listOf("Successfully signed in", "You are signed in", "Signed in as")
        private val ANSI = Regex("\\u001B\\[[;?\\d]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007|\\u001B[=>][\\d;]*[a-zA-Z]?")
    }
}
