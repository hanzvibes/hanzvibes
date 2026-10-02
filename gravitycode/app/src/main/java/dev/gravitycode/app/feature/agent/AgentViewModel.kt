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
import dev.gravitycode.app.core.workspace.GitDiff
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
    private var terminalProjectPath: String? = null

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
    fun setTerminalCommand(value: String) = _state.update { it.copy(terminalCommand = value, terminalHistoryIndex = null) }
    fun setFileDraft(value: String) = _state.update { it.copy(fileDraft = value) }
    fun clearEvents() = _state.update { it.copy(events = emptyList()) }
    fun clearTerminal() = _state.update { it.copy(terminalOutput = "") }
    fun closeFilePreview() = _state.update { it.copy(selectedFile = null, fileDraft = "", fileSaving = false) }
    fun closeDiff() = _state.update { it.copy(selectedDiff = null, diffError = null, diffLoading = false, diffReverting = false) }

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
        if (preview.kind != PreviewKind.TEXT || preview.truncated || state.value.fileSaving) return
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

    fun selectDiff(path: String, status: String) {
        if (state.value.diffLoading) return
        val projectRoot = workspaceRepository.activeProjectRoot
        viewModelScope.launch {
            _state.update { it.copy(diffLoading = true, diffError = null, selectedDiff = null) }
            runtime.loadDiff(projectRoot, path, status)
                .onSuccess { diff ->
                    _state.update { it.copy(diffLoading = false, selectedDiff = diff, diffError = null) }
                }
                .onFailure { error ->
                    _state.update { it.copy(diffLoading = false, diffError = error.message ?: "Gagal memuat diff") }
                }
        }
    }

    fun revertSelectedDiff() {
        val diff = state.value.selectedDiff ?: return
        if (state.value.diffReverting) return
        val projectRoot = workspaceRepository.activeProjectRoot
        viewModelScope.launch {
            _state.update { it.copy(diffReverting = true) }
            runtime.revertPath(projectRoot, diff.path, diff.status)
                .onSuccess {
                    _state.update {
                        it.copy(
                            diffReverting = false,
                            selectedDiff = null,
                            diffError = null,
                            events = mergeEvent(it.events, AgentEvent.Status("Reverted ${diff.path}")),
                        )
                    }
                    refreshProjectContext()
                }
                .onFailure { error ->
                    _state.update { it.copy(diffReverting = false, diffError = error.message ?: "Revert gagal") }
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
                    stopTerminalSession()
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
        if (prompt.isEmpty() || state.value.running) return
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

    fun startTerminalSession() {
        if (!state.value.runtimeStatus.available) return
        val projectRoot = workspaceRepository.activeProjectRoot
        val canonical = runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.absolutePath)
        if (state.value.terminalRunning && terminalProjectPath == canonical && runtime.isTerminalAlive()) return

        stopTerminalSession()
        terminalProjectPath = canonical
        _state.update {
            it.copy(
                terminalRunning = true,
                terminalOutput = if (it.terminalOutput.isBlank()) "" else it.terminalOutput + "\n",
            )
        }
        terminalJob = viewModelScope.launch {
            runtime.terminalSession(projectRoot).collect { chunk ->
                _state.update { current ->
                    val normalized = chunk.text.replace("\r\n", "\n").replace('\r', '\n')
                    current.copy(
                        terminalOutput = (current.terminalOutput + normalized).takeLast(TERMINAL_BUFFER_LIMIT),
                        terminalRunning = !chunk.closed,
                    )
                }
            }
            _state.update { it.copy(terminalRunning = false) }
        }
    }

    fun submitTerminalCommand() {
        val command = state.value.terminalCommand.trimEnd()
        if (command.isBlank()) return
        if (!state.value.terminalRunning || !runtime.isTerminalAlive()) {
            startTerminalSession()
            _state.update {
                it.copy(terminalOutput = (it.terminalOutput + "\n[terminal starting; run command again when ready]\n").takeLast(TERMINAL_BUFFER_LIMIT))
            }
            return
        }
        val sent = runtime.sendTerminalInput(command + "\n")
        if (sent) {
            _state.update {
                it.copy(
                    terminalCommand = "",
                    terminalHistory = (it.terminalHistory + command).takeLast(100),
                    terminalHistoryIndex = null,
                )
            }
        }
    }

    fun terminalInterrupt() {
        if (!runtime.interruptTerminal()) {
            _state.update { it.copy(terminalOutput = (it.terminalOutput + "\n[terminal is not running]\n").takeLast(TERMINAL_BUFFER_LIMIT)) }
        }
    }

    fun terminalTab() {
        runtime.terminalTab()
    }

    fun terminalHistoryPrevious() {
        _state.update { current ->
            if (current.terminalHistory.isEmpty()) return@update current
            val next = when (val index = current.terminalHistoryIndex) {
                null -> current.terminalHistory.lastIndex
                else -> (index - 1).coerceAtLeast(0)
            }
            current.copy(terminalHistoryIndex = next, terminalCommand = current.terminalHistory[next])
        }
    }

    fun terminalHistoryNext() {
        _state.update { current ->
            val index = current.terminalHistoryIndex ?: return@update current
            if (index >= current.terminalHistory.lastIndex) {
                current.copy(terminalHistoryIndex = null, terminalCommand = "")
            } else {
                val next = index + 1
                current.copy(terminalHistoryIndex = next, terminalCommand = current.terminalHistory[next])
            }
        }
    }

    fun restartTerminalSession() {
        stopTerminalSession()
        startTerminalSession()
    }

    fun stopTerminalSession() {
        terminalJob?.cancel()
        terminalJob = null
        terminalProjectPath = null
        runtime.stopTerminal()
        _state.update { it.copy(terminalRunning = false) }
    }

    fun cancel() {
        runtime.cancel()
        runningJob?.cancel()
        _state.update { it.copy(running = false, events = mergeEvent(it.events, AgentEvent.Status("Cancelled"))) }
        refreshProjectContext()
    }

    private fun refreshProjectContext() {
        val local = workspaceRepository.localStatus()
        val oldProject = state.value.repositoryStatus.workspacePath
        _state.update {
            it.copy(
                runtimeStatus = runtime.status(),
                workspaceFiles = workspaceRepository.files(),
                repositoryStatus = local,
            )
        }
        if (oldProject != local.workspacePath && state.value.terminalRunning) stopTerminalSession()
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
                    if (runningIndex >= 0) {
                        val started = result[runningIndex] as AgentEvent.Tool
                        result[runningIndex] = event.copy(timestamp = started.timestamp)
                    } else {
                        result += event
                    }
                } else {
                    result += event
                }
            }
            else -> result += event
        }
        return result.takeLast(240)
    }

    override fun onCleared() {
        runtime.cancel()
        runtime.stopTerminal()
        super.onCleared()
    }

    private companion object {
        const val TERMINAL_BUFFER_LIMIT = 220_000
    }
}

data class AgentUiState(
    val prompt: String = "",
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val runtimeStatus: RuntimeStatus,
    val workspaceFiles: List<WorkspaceEntry>,
    val repositoryStatus: ProjectStatus,
    val selectedFile: FilePreview? = null,
    val fileDraft: String = "",
    val fileSaving: Boolean = false,
    val selectedDiff: GitDiff? = null,
    val diffLoading: Boolean = false,
    val diffError: String? = null,
    val diffReverting: Boolean = false,
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
    val terminalOutput: String = "",
    val terminalRunning: Boolean = false,
    val terminalHistory: List<String> = emptyList(),
    val terminalHistoryIndex: Int? = null,
)