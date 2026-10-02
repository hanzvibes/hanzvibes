package dev.gravitycode.app.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.runtime.AntigravityRuntime
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.runtime.RuntimeStatus
import dev.gravitycode.app.core.workspace.FilePreview
import dev.gravitycode.app.core.workspace.PreviewKind
import dev.gravitycode.app.core.workspace.ProjectStatus
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.core.workspace.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
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
    private var projectRefreshJob: Job? = null
    private var terminalJob: Job? = null

    private val _state = MutableStateFlow(
        AgentUiState(
            runtimeStatus = runtime.status(),
            workspaceFiles = workspaceRepository.files(),
            repositoryStatus = workspaceRepository.localStatus(),
            authState = runtime.auth.state.value,
        ),
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runtime.auth.state.collect { authState -> _state.update { it.copy(authState = authState) } }
        }
        refreshProjectContext()
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value) }
    fun setPermissionMode(mode: PermissionMode) = _state.update { it.copy(permissionMode = mode) }
    fun setAuthCode(value: String) = _state.update { it.copy(authCode = value) }
    fun setCloneUrl(value: String) = _state.update { it.copy(cloneUrl = value) }
    fun setTerminalCommand(value: String) = _state.update { it.copy(terminalCommand = value) }
    fun setFileDraft(value: String) = _state.update { it.copy(fileDraft = value) }
    fun clearEvents() = _state.update { it.copy(events = emptyList()) }
    fun clearTerminal() = _state.update { it.copy(terminalEntries = emptyList()) }
    fun closeFilePreview() = _state.update { it.copy(selectedFile = null, fileDraft = "", fileSaving = false) }

    fun selectFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.preview(relativePath)
                .onSuccess { preview ->
                    _state.update {
                        it.copy(
                            selectedFile = preview,
                            fileDraft = if (preview.kind == PreviewKind.TEXT) preview.content else "",
                            fileSaving = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(events = mergeEvent(it.events, AgentEvent.Error(error.message ?: "Gagal membuka file"))) }
                }
        }
    }

    fun saveSelectedFile() {
        val preview = state.value.selectedFile ?: return
        if (preview.kind != PreviewKind.TEXT || state.value.fileSaving) return
        val draft = state.value.fileDraft
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(fileSaving = true) }
            workspaceRepository.saveText(preview.path, draft)
                .onSuccess { updated ->
                    _state.update {
                        it.copy(
                            selectedFile = updated,
                            fileDraft = updated.content,
                            fileSaving = false,
                            events = mergeEvent(it.events, AgentEvent.Status("Saved ${updated.path}")),
                        )
                    }
                    refreshProjectContext()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            fileSaving = false,
                            events = mergeEvent(it.events, AgentEvent.Error(error.message ?: "Gagal menyimpan file")),
                        )
                    }
                }
        }
    }

    fun refreshRuntime() {
        runtime.auth.refresh()
        _state.update { it.copy(runtimeStatus = runtime.status()) }
        refreshProjectContext()
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
                        events = mergeEvent(current.events, AgentEvent.Status("Runtime Antigravity siap")),
                    )
                }
                refreshProjectContext()
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        installing = false,
                        runtimeStatus = runtime.status(),
                        events = mergeEvent(current.events, AgentEvent.Error(error.message ?: "Runtime setup gagal")),
                    )
                }
            }
        }
    }

    fun beginAuth() = runtime.beginAuth(workspaceRepository.activeProjectRoot)

    fun submitAuthCode() {
        runtime.submitAuthCode(state.value.authCode)
        _state.update { it.copy(authCode = "") }
    }

    fun cloneRepository() {
        val url = state.value.cloneUrl.trim()
        if (url.isEmpty() || state.value.cloning) return
        viewModelScope.launch {
            _state.update { it.copy(cloning = true, events = mergeEvent(it.events, AgentEvent.Status("Cloning $url"))) }
            runtime.cloneRepository(url, workspaceRepository.activeWorkspace)
                .onSuccess { name ->
                    _state.update { current ->
                        current.copy(
                            cloning = false,
                            cloneUrl = "",
                            events = mergeEvent(current.events, AgentEvent.Status("Repository siap: $name")),
                        )
                    }
                    refreshProjectContext()
                }
                .onFailure { error ->
                    _state.update { it.copy(cloning = false, events = mergeEvent(it.events, AgentEvent.Error(error.message ?: "git clone gagal"))) }
                }
        }
    }

    fun runAgent() {
        val prompt = state.value.prompt.trim()
        if (prompt.isEmpty() || state.value.running || state.value.terminalRunning) return
        if (!state.value.runtimeStatus.available) {
            _state.update { it.copy(events = mergeEvent(it.events, AgentEvent.Error("Setup runtime dulu"))) }
            return
        }
        if (state.value.authState !is AuthState.SignedIn) {
            _state.update { it.copy(events = mergeEvent(it.events, AgentEvent.Error("Login Google Antigravity dulu"))) }
            return
        }

        val projectRoot = workspaceRepository.activeProjectRoot
        runningJob?.cancel()
        _state.update {
            it.copy(
                running = true,
                prompt = "",
                events = (it.events + AgentEvent.Prompt(prompt) + AgentEvent.Status("Running in ${projectRoot.name}")).takeLast(240),
            )
        }
        runningJob = viewModelScope.launch {
            runtime.run(prompt, projectRoot, state.value.permissionMode).collect { event ->
                _state.update { current -> current.copy(events = mergeEvent(current.events, event)) }
            }
            _state.update { it.copy(running = false) }
            refreshProjectContext()
        }
    }

    fun runTerminalCommand() {
        val command = state.value.terminalCommand.trim()
        if (command.isEmpty() || state.value.terminalRunning || state.value.running) return
        if (!state.value.runtimeStatus.available) {
            _state.update { it.copy(terminalEntries = it.terminalEntries + TerminalEntry(command, "Runtime belum siap", -1)) }
            return
        }
        val projectRoot = workspaceRepository.activeProjectRoot
        terminalJob?.cancel()
        _state.update { it.copy(terminalRunning = true, terminalCommand = "") }
        terminalJob = viewModelScope.launch {
            runtime.runShell(projectRoot, command)
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            terminalRunning = false,
                            terminalEntries = (it.terminalEntries + TerminalEntry(command, result.output, result.exitCode)).takeLast(80),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            terminalRunning = false,
                            terminalEntries = (it.terminalEntries + TerminalEntry(command, error.message ?: "Command failed", -1)).takeLast(80),
                        )
                    }
                }
            refreshProjectContext()
        }
    }

    fun cancel() {
        runtime.cancel()
        runningJob?.cancel()
        _state.update { it.copy(running = false, events = mergeEvent(it.events, AgentEvent.Status("Cancelled"))) }
        refreshProjectContext()
    }

    private fun refreshProjectContext() {
        val local = workspaceRepository.localStatus()
        _state.update {
            it.copy(
                runtimeStatus = runtime.status(),
                workspaceFiles = workspaceRepository.files(),
                repositoryStatus = local,
            )
        }
        if (!runtime.status().available) return
        val projectRoot = workspaceRepository.activeProjectRoot
        projectRefreshJob?.cancel()
        projectRefreshJob = viewModelScope.launch {
            val inspected = runCatching { runtime.inspectRepository(projectRoot) }.getOrDefault(local)
            _state.update {
                it.copy(
                    repositoryStatus = inspected,
                    workspaceFiles = workspaceRepository.files(),
                )
            }
        }
    }

    private fun mergeEvent(existing: List<AgentEvent>, event: AgentEvent): List<AgentEvent> {
        val result = existing.toMutableList()
        when (event) {
            is AgentEvent.Output -> {
                val last = result.lastOrNull()
                if (last is AgentEvent.Output) {
                    result[result.lastIndex] = last.copy(text = last.text + event.text)
                } else {
                    result += event
                }
            }
            is AgentEvent.Tool -> {
                if (event.state != ToolState.RUNNING) {
                    val runningIndex = result.indexOfLast {
                        it is AgentEvent.Tool && it.name == event.name && it.state == ToolState.RUNNING
                    }
                    if (runningIndex >= 0) result[runningIndex] = event else result += event
                } else {
                    result += event
                }
            }
            else -> result += event
        }
        return result.takeLast(240)
    }
}

data class TerminalEntry(
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

data class AgentUiState(
    val prompt: String = "",
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val runtimeStatus: RuntimeStatus,
    val workspaceFiles: List<WorkspaceEntry>,
    val repositoryStatus: ProjectStatus,
    val selectedFile: FilePreview? = null,
    val fileDraft: String = "",
    val fileSaving: Boolean = false,
    val authState: AuthState,
    val authCode: String = "",
    val cloneUrl: String = "",
    val events: List<AgentEvent> = emptyList(),
    val running: Boolean = false,
    val installing: Boolean = false,
    val installProgress: Float = 0f,
    val installMessage: String = "",
    val cloning: Boolean = false,
    val terminalCommand: String = "",
    val terminalEntries: List<TerminalEntry> = emptyList(),
    val terminalRunning: Boolean = false,
)
