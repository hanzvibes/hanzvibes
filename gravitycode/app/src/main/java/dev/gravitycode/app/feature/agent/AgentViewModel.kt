package dev.gravitycode.app.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.runtime.AntigravityRuntime
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
        ),
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value) }

    fun setPermissionMode(mode: PermissionMode) = _state.update { it.copy(permissionMode = mode) }

    fun refreshRuntime() = _state.update {
        it.copy(
            runtimeStatus = runtime.status(),
            workspaceFiles = workspaceRepository.files(),
        )
    }

    fun runAgent() {
        val prompt = state.value.prompt.trim()
        if (prompt.isEmpty() || state.value.running) return

        runningJob?.cancel()
        _state.update { it.copy(running = true, prompt = "", events = it.events + AgentEvent.Status("Queued: $prompt")) }

        runningJob = viewModelScope.launch {
            runtime.run(
                prompt = prompt,
                workspace = workspaceRepository.activeWorkspace,
                permissionMode = state.value.permissionMode,
            ).collect { event ->
                _state.update { current ->
                    current.copy(
                        events = (current.events + event).takeLast(250),
                        runtimeStatus = runtime.status(),
                        workspaceFiles = workspaceRepository.files(),
                    )
                }
            }
            _state.update { it.copy(running = false) }
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
    val events: List<AgentEvent> = emptyList(),
    val running: Boolean = false,
)
