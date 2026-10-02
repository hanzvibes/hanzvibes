package dev.gravitycode.app.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.runtime.AntigravityRuntime
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.runtime.RuntimeStatus
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.core.workspace.WorkspaceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val workspaceRepository = WorkspaceRepository(application)
    private val runtime = AntigravityRuntime(application)
    private var runningJob: Job? = null

    private val _state = MutableStateFlow(
        AgentUiState(
            runtimeStatus = runtime.status(),
            workspaceFiles = workspaceRepository.files(),
            authState = runtime.auth.state.value,
        ),
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runtime.auth.state.collect { authState -> _state.update { it.copy(authState = authState) } }
        }
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value) }
    fun setPermissionMode(mode: PermissionMode) = _state.update { it.copy(permissionMode = mode) }
    fun setAuthCode(value: String) = _state.update { it.copy(authCode = value) }
    fun setCloneUrl(value: String) = _state.update { it.copy(cloneUrl = value) }

    fun refreshRuntime() = _state.update {
        runtime.auth.refresh()
        it.copy(runtimeStatus = runtime.status(), workspaceFiles = workspaceRepository.files())
    }

    fun setupRuntime() {
        if (state.value.installing) return
        viewModelScope.launch {
            _state.update { it.copy(installing = true, installProgress = 0f, installMessage = "Starting runtime setup") }
            runCatching {
                runtime.setup { progress, message ->
                    _state.update { it.copy(installProgress = progress, installMessage = message) }
                }
            }.onSuccess {
                _state.update { current ->
                    current.copy(
                        installing = false,
                        runtimeStatus = runtime.status(),
                        workspaceFiles = workspaceRepository.files(),
                        events = current.events + AgentEvent.Status("Runtime Antigravity siap"),
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(installing = false, runtimeStatus = runtime.status(), events = current.events + AgentEvent.Error(error.message ?: "Runtime setup gagal"))
                }
            }
        }
    }

    fun beginAuth() = runtime.beginAuth(workspaceRepository.activeWorkspace)

    fun submitAuthCode() {
        runtime.submitAuthCode(state.value.authCode)
        _state.update { it.copy(authCode = "") }
    }

    fun cloneRepository() {
        val url = state.value.cloneUrl.trim()
        if (url.isEmpty() || state.value.cloning) return
        viewModelScope.launch {
            _state.update { it.copy(cloning = true, events = it.events + AgentEvent.Status("Cloning $url")) }
            runtime.cloneRepository(url, workspaceRepository.activeWorkspace)
                .onSuccess { name ->
                    _state.update { current ->
                        current.copy(cloning = false, cloneUrl = "", workspaceFiles = workspaceRepository.files(), events = current.events + AgentEvent.Status("Repository siap di /workspace/$name"))
                    }
                }
                .onFailure { error -> _state.update { it.copy(cloning = false, events = it.events + AgentEvent.Error(error.message ?: "git clone gagal")) } }
        }
    }

    fun runAgent() {
        val prompt = state.value.prompt.trim()
        if (prompt.isEmpty() || state.value.running) return
        if (!state.value.runtimeStatus.available) {
            _state.update { it.copy(events = it.events + AgentEvent.Error("Setup runtime dulu")) }
            return
        }
        if (state.value.authState !is AuthState.SignedIn) {
            _state.update { it.copy(events = it.events + AgentEvent.Error("Login Google Antigravity dulu")) }
            return
        }

        runningJob?.cancel()
        _state.update { it.copy(running = true, prompt = "", events = it.events + AgentEvent.Status("Queued: $prompt")) }
        runningJob = viewModelScope.launch {
            runtime.run(prompt, workspaceRepository.activeWorkspace, state.value.permissionMode).collect { event ->
                _state.update { current ->
                    current.copy(
                        events = (current.events + event).takeLast(300),
                        runtimeStatus = runtime.status(),
                        workspaceFiles = workspaceRepository.files(),
                    )
                }
            }
            _state.update { it.copy(running = false, workspaceFiles = workspaceRepository.files()) }
        }
    }

    fun cancel() {
        runtime.cancel()
        runningJob?.cancel()
        _state.update { it.copy(running = false, events = it.events + AgentEvent.Status("Cancelled")) }
    }
}

data class AgentUiState(
    val prompt: String = "",
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val runtimeStatus: RuntimeStatus,
    val workspaceFiles: List<WorkspaceEntry>,
    val authState: AuthState,
    val authCode: String = "",
    val cloneUrl: String = "",
    val events: List<AgentEvent> = emptyList(),
    val running: Boolean = false,
    val installing: Boolean = false,
    val installProgress: Float = 0f,
    val installMessage: String = "",
    val cloning: Boolean = false,
)
