package dev.gravitycode.app.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gravitycode.app.background.AgentForegroundService
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.persistence.CrashRecovery
import dev.gravitycode.app.core.persistence.SavedSession
import dev.gravitycode.app.core.persistence.SessionStore
import dev.gravitycode.app.core.persistence.SessionSummary
import dev.gravitycode.app.core.runtime.AntigravityRuntime
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.runtime.RuntimeStatus
import dev.gravitycode.app.core.workspace.FilePreview
import dev.gravitycode.app.core.workspace.GitCommit
import dev.gravitycode.app.core.workspace.GitDiff
import dev.gravitycode.app.core.workspace.PreviewKind
import dev.gravitycode.app.core.workspace.ProjectStatus
import dev.gravitycode.app.core.workspace.ProjectSummary
import dev.gravitycode.app.core.workspace.SearchHit
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.core.workspace.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val workspaceRepository = WorkspaceRepository(application)
    private val runtime = AntigravityRuntime(application)
    private val sessionStore = SessionStore(application)
    private val crashRecovery = CrashRecovery(application)
    private var runningJob: Job? = null
    private var projectRefreshJob: Job? = null
    private val terminalJobs = mutableMapOf<String, Job>()
    private var terminalProjectPath: String? = null

    private fun currentProjectKey(): String = workspaceRepository.activeProjectRoot.name.ifBlank { "workspace" }

    private val initialSaved = sessionStore.newest(currentProjectKey())
    private val _state = MutableStateFlow(
        AgentUiState(
            runtimeStatus = runtime.status(),
            workspaceFiles = workspaceRepository.files(),
            repositoryStatus = workspaceRepository.localStatus(),
            projects = workspaceRepository.projects(),
            sessions = sessionStore.list(currentProjectKey()),
            activeSessionId = initialSaved?.summary?.id,
            activeSessionTitle = initialSaved?.summary?.title ?: "New session",
            conversationId = initialSaved?.summary?.conversationId,
            events = initialSaved?.events.orEmpty(),
            authState = runtime.auth.state.value,
            terminalTabs = listOf(TerminalTabState(id = "terminal-1", title = "Terminal 1")),
            activeTerminalId = "terminal-1",
            lastCrash = crashRecovery.read(),
        ),
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { runtime.auth.state.collect { authState -> _state.update { it.copy(authState = authState) } } }
        refreshProjectContext()
    }

    fun setPrompt(value: String) = _state.update { it.copy(prompt = value) }
    fun setPermissionMode(mode: PermissionMode) = _state.update { it.copy(permissionMode = mode) }
    fun setAuthCode(value: String) = _state.update { it.copy(authCode = value) }
    fun setCloneUrl(value: String) = _state.update { it.copy(cloneUrl = value) }
    fun setCommitMessage(value: String) = _state.update { it.copy(commitMessage = value) }
    fun suggestCommitMessage() {
        val all = state.value.repositoryStatus.changedPaths
        val changes = all.filter { it.staged }.ifEmpty { all }
        val message = when {
            changes.isEmpty() -> "chore: update project"
            changes.size == 1 -> {
                val change = changes.first()
                val verb = when {
                    change.status.contains("A") || change.status == "??" -> "feat"
                    change.status.contains("D") -> "chore"
                    else -> "fix"
                }
                "$verb: update ${change.path.substringAfterLast('/')}"
            }
            changes.all { it.path.substringAfterLast('.').lowercase() in setOf("md", "txt") } -> "docs: update documentation"
            else -> "chore: update ${changes.size} files"
        }
        _state.update { it.copy(commitMessage = message) }
    }
    fun setNewBranch(value: String) = _state.update { it.copy(newBranch = value) }
    fun setSelectedModel(value: String?) = _state.update { it.copy(selectedModel = value?.takeIf { model -> model != "Default" }) }
    fun setFileDraft(value: String) = _state.update { it.copy(fileDraft = value) }
    fun clearEvents() { _state.update { it.copy(events = emptyList()) }; persistSession() }
    fun clearCrash() { crashRecovery.clear(); _state.update { it.copy(lastCrash = null) } }
    fun closeFilePreview() = _state.update { it.copy(selectedFile = null, selectedTargetLine = 0, fileDraft = "", fileSaving = false) }
    fun closeDiff() = _state.update { it.copy(selectedDiff = null, diffError = null, diffLoading = false, diffReverting = false) }

    fun selectFile(relativePath: String, targetLine: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.preview(relativePath)
                .onSuccess { preview -> _state.update { it.copy(selectedFile = preview, selectedTargetLine = targetLine, fileDraft = if (preview.kind == PreviewKind.TEXT) preview.content else "", fileSaving = false) } }
                .onFailure { error -> appendEvent(AgentEvent.Error(error.message ?: "Gagal membuka file")) }
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
                    _state.update { it.copy(selectedFile = updated, fileDraft = updated.content, fileSaving = false) }
                    appendEvent(AgentEvent.Status("Saved ${updated.path}"))
                    refreshProjectContext()
                }
                .onFailure { error -> _state.update { it.copy(fileSaving = false) }; appendEvent(AgentEvent.Error(error.message ?: "Gagal menyimpan file")) }
        }
    }

    fun createPath(path: String, directory: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.createPath(path, directory)
                .onSuccess { refreshProjectContext(); appendEvent(AgentEvent.Status("Created $path")) }
                .onFailure { appendEvent(AgentEvent.Error(it.message ?: "Gagal membuat path")) }
        }
    }

    fun renamePath(oldPath: String, newPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.renamePath(oldPath, newPath)
                .onSuccess { closeFilePreview(); refreshProjectContext(); appendEvent(AgentEvent.Status("Renamed $oldPath → $newPath")) }
                .onFailure { appendEvent(AgentEvent.Error(it.message ?: "Rename gagal")) }
        }
    }

    fun deletePath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspaceRepository.deletePath(path)
                .onSuccess { closeFilePreview(); refreshProjectContext(); appendEvent(AgentEvent.Status("Deleted $path")) }
                .onFailure { appendEvent(AgentEvent.Error(it.message ?: "Delete gagal")) }
        }
    }

    fun setSearchQuery(value: String) = _state.update { it.copy(searchQuery = value) }
    fun searchWorkspace() {
        val query = state.value.searchQuery.trim()
        if (query.length < 2 || state.value.searching) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(searching = true) }
            val results = workspaceRepository.search(query)
            _state.update { it.copy(searching = false, searchResults = results) }
        }
    }

    fun selectDiff(path: String, status: String) {
        if (state.value.diffLoading) return
        val projectRoot = workspaceRepository.activeProjectRoot
        viewModelScope.launch {
            _state.update { it.copy(diffLoading = true, diffError = null, selectedDiff = null) }
            runtime.loadDiff(projectRoot, path, status)
                .onSuccess { diff -> _state.update { it.copy(diffLoading = false, selectedDiff = diff) } }
                .onFailure { error -> _state.update { it.copy(diffLoading = false, diffError = error.message ?: "Gagal memuat diff") } }
        }
    }

    fun revertSelectedDiff() {
        val diff = state.value.selectedDiff ?: return
        if (state.value.diffReverting) return
        viewModelScope.launch {
            _state.update { it.copy(diffReverting = true) }
            runtime.revertPath(workspaceRepository.activeProjectRoot, diff.path, diff.status)
                .onSuccess { _state.update { it.copy(diffReverting = false, selectedDiff = null, diffError = null) }; appendEvent(AgentEvent.Status("Reverted ${diff.path}")); refreshProjectContext() }
                .onFailure { error -> _state.update { it.copy(diffReverting = false, diffError = error.message ?: "Revert gagal") } }
        }
    }

    fun attachFile(path: String) = addContext(AgentContextItem("file:$path", ContextType.FILE, path.substringAfterLast('/'), path))
    fun attachSelectedDiff() {
        val diff = state.value.selectedDiff ?: return
        addContext(AgentContextItem("diff:${diff.path}", ContextType.DIFF, "Diff ${diff.path}", diff.patch.take(12_000)))
    }
    fun attachTerminal() {
        val tab = activeTerminal() ?: return
        val content = tab.output.takeLast(10_000)
        if (content.isNotBlank()) addContext(AgentContextItem("terminal:${tab.id}:${System.currentTimeMillis()}", ContextType.TERMINAL, tab.title, content))
    }
    fun removeContext(id: String) = _state.update { it.copy(contextItems = it.contextItems.filterNot { item -> item.id == id }) }
    private fun addContext(item: AgentContextItem) = _state.update { current -> current.copy(contextItems = (current.contextItems.filterNot { it.id == item.id } + item).takeLast(8)) }

    fun newSession() {
        persistSession()
        val saved = sessionStore.create(currentProjectKey())
        _state.update { it.copy(activeSessionId = saved.summary.id, activeSessionTitle = saved.summary.title, conversationId = null, events = emptyList(), sessions = sessionStore.list(currentProjectKey())) }
    }

    fun openSession(id: String) {
        persistSession()
        val saved = sessionStore.load(currentProjectKey(), id) ?: return
        _state.update { it.copy(activeSessionId = saved.summary.id, activeSessionTitle = saved.summary.title, conversationId = saved.summary.conversationId, events = saved.events, sessions = sessionStore.list(currentProjectKey())) }
    }

    fun renameSession(id: String, title: String) {
        sessionStore.rename(currentProjectKey(), id, title)
        val active = state.value.activeSessionId == id
        _state.update { it.copy(sessions = sessionStore.list(currentProjectKey()), activeSessionTitle = if (active) title.trim().ifBlank { "Untitled session" } else it.activeSessionTitle) }
    }

    fun deleteSession(id: String) {
        sessionStore.delete(currentProjectKey(), id)
        if (state.value.activeSessionId == id) newSession() else _state.update { it.copy(sessions = sessionStore.list(currentProjectKey())) }
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
            runCatching { runtime.setup { progress, message -> _state.update { it.copy(installProgress = progress, installMessage = message) } } }
                .onSuccess { _state.update { it.copy(installing = false, runtimeStatus = runtime.status()) }; appendEvent(AgentEvent.Status("Runtime Antigravity siap")); refreshProjectContext() }
                .onFailure { error -> _state.update { it.copy(installing = false, runtimeStatus = runtime.status()) }; appendEvent(AgentEvent.Error(error.message ?: "Runtime setup gagal")) }
        }
    }

    fun beginAuth() = runtime.beginAuth(workspaceRepository.activeProjectRoot)
    fun submitAuthCode() { runtime.submitAuthCode(state.value.authCode); _state.update { it.copy(authCode = "") } }

    fun cloneRepository() {
        val url = state.value.cloneUrl.trim()
        if (url.isEmpty() || state.value.cloning) return
        viewModelScope.launch {
            _state.update { it.copy(cloning = true) }
            appendEvent(AgentEvent.Status("Cloning $url"))
            runtime.cloneRepository(url, workspaceRepository.activeWorkspace)
                .onSuccess { name ->
                    stopAllTerminals()
                    workspaceRepository.markActiveProject(name)
                    _state.update { it.copy(cloning = false, cloneUrl = "") }
                    loadProjectSession()
                    refreshProjectContext()
                    appendEvent(AgentEvent.Status("Repository siap: $name"))
                }
                .onFailure { error -> _state.update { it.copy(cloning = false) }; appendEvent(AgentEvent.Error(error.message ?: "git clone gagal")) }
        }
    }

    fun switchProject(name: String) {
        if (state.value.running) return
        persistSession(); stopAllTerminals()
        workspaceRepository.switchProject(name)
            .onSuccess { loadProjectSession(); refreshProjectContext() }
            .onFailure { appendEvent(AgentEvent.Error(it.message ?: "Gagal switch project")) }
    }

    fun deleteProject(name: String) {
        if (state.value.running || workspaceRepository.activeProjectRoot.name == name) {
            appendEvent(AgentEvent.Error("Switch ke project lain sebelum menghapus project aktif")); return
        }
        workspaceRepository.deleteProject(name)
            .onSuccess { refreshProjectContext() }
            .onFailure { appendEvent(AgentEvent.Error(it.message ?: "Gagal menghapus project")) }
    }

    fun runAgent() {
        val prompt = state.value.prompt.trim()
        if (prompt.isEmpty() || state.value.running) return
        if (!state.value.runtimeStatus.available) { appendEvent(AgentEvent.Error("Setup runtime dulu")); return }
        if (state.value.authState !is AuthState.SignedIn) { appendEvent(AgentEvent.Error("Login Google Antigravity dulu")); return }

        ensureSession(prompt)
        val current = state.value
        val projectRoot = workspaceRepository.activeProjectRoot
        val fullPrompt = composePrompt(prompt, current.contextItems)
        runningJob?.cancel()
        _state.update { it.copy(running = true, prompt = "", contextItems = emptyList(), events = (it.events + AgentEvent.Prompt(prompt) + AgentEvent.Status("Running in ${projectRoot.name}")).takeLast(240)) }
        persistSession()
        AgentForegroundService.start(getApplication(), projectRoot.name, prompt)
        runningJob = viewModelScope.launch {
            try {
                runtime.run(fullPrompt, projectRoot, state.value.permissionMode, state.value.conversationId, state.value.selectedModel).collect { event ->
                    if (event is AgentEvent.Conversation) {
                        _state.update { it.copy(conversationId = event.id) }
                    } else appendEvent(event, persist = false)
                }
            } finally {
                _state.update { it.copy(running = false) }
                persistSession()
                AgentForegroundService.stop(getApplication())
                refreshProjectContext()
            }
        }
    }

    fun cancel() {
        runtime.cancel(); runningJob?.cancel(); runningJob = null
        _state.update { it.copy(running = false) }
        appendEvent(AgentEvent.Status("Cancelled")); persistSession(); AgentForegroundService.stop(getApplication()); refreshProjectContext()
    }

    fun newTerminal() {
        if (state.value.terminalTabs.size >= 4) return
        val id = "terminal-${UUID.randomUUID().toString().take(8)}"
        val title = "Terminal ${state.value.terminalTabs.size + 1}"
        _state.update { it.copy(terminalTabs = it.terminalTabs + TerminalTabState(id, title), activeTerminalId = id) }
        startTerminalSession(id)
    }

    fun selectTerminal(id: String) {
        if (state.value.terminalTabs.none { it.id == id }) return
        _state.update { it.copy(activeTerminalId = id) }
        if (!runtime.isTerminalAlive(id)) startTerminalSession(id)
    }

    fun closeTerminal(id: String) {
        if (state.value.terminalTabs.size <= 1) { restartTerminalSession(id); return }
        terminalJobs.remove(id)?.cancel(); runtime.stopTerminal(id)
        _state.update { current ->
            val next = current.terminalTabs.filterNot { it.id == id }
            current.copy(terminalTabs = next, activeTerminalId = if (current.activeTerminalId == id) next.first().id else current.activeTerminalId)
        }
    }

    fun setTerminalCommand(value: String) = updateActiveTerminal { it.copy(command = value, historyIndex = null) }
    fun clearTerminal() = updateActiveTerminal { it.copy(output = "") }

    fun startTerminalSession(id: String = state.value.activeTerminalId) {
        if (!state.value.runtimeStatus.available) return
        val projectRoot = workspaceRepository.activeProjectRoot
        val canonical = runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.absolutePath)
        if (terminalProjectPath != null && terminalProjectPath != canonical) stopAllTerminals()
        terminalProjectPath = canonical
        if (runtime.isTerminalAlive(id)) { updateTerminal(id) { it.copy(running = true) }; return }
        terminalJobs[id]?.cancel()
        updateTerminal(id) { it.copy(running = true) }
        terminalJobs[id] = viewModelScope.launch {
            runtime.terminalSession(id, projectRoot).collect { chunk ->
                updateTerminal(id) { tab -> tab.copy(output = (tab.output + chunk.text.replace("\r\n", "\n").replace('\r', '\n')).takeLast(TERMINAL_BUFFER_LIMIT), running = !chunk.closed) }
            }
            updateTerminal(id) { it.copy(running = false) }
        }
    }

    fun submitTerminalCommand() {
        val tab = activeTerminal() ?: return
        val command = tab.command.trimEnd()
        if (command.isBlank()) return
        if (!runtime.isTerminalAlive(tab.id)) { startTerminalSession(tab.id); return }
        if (runtime.sendTerminalInput(tab.id, command + "\n")) {
            updateActiveTerminal { it.copy(command = "", history = (it.history + command).takeLast(100), historyIndex = null) }
        }
    }

    fun terminalInterrupt() { activeTerminal()?.let { runtime.interruptTerminal(it.id) } }
    fun terminalTab() { activeTerminal()?.let { runtime.terminalTab(it.id) } }
    fun terminalHistoryPrevious() = updateActiveTerminal { tab ->
        if (tab.history.isEmpty()) tab else {
            val next = tab.historyIndex?.minus(1)?.coerceAtLeast(0) ?: tab.history.lastIndex
            tab.copy(historyIndex = next, command = tab.history[next])
        }
    }
    fun terminalHistoryNext() = updateActiveTerminal { tab ->
        val index = tab.historyIndex ?: return@updateActiveTerminal tab
        if (index >= tab.history.lastIndex) tab.copy(historyIndex = null, command = "")
        else tab.copy(historyIndex = index + 1, command = tab.history[index + 1])
    }
    fun restartTerminalSession(id: String = state.value.activeTerminalId) { terminalJobs.remove(id)?.cancel(); runtime.stopTerminal(id); updateTerminal(id) { it.copy(running = false) }; startTerminalSession(id) }

    private fun stopAllTerminals() {
        terminalJobs.values.forEach(Job::cancel); terminalJobs.clear(); runtime.stopAllTerminals(); terminalProjectPath = null
        _state.update { it.copy(terminalTabs = listOf(TerminalTabState("terminal-1", "Terminal 1")), activeTerminalId = "terminal-1") }
    }

    fun gitStage(path: String) = runGitAction("Staging $path") { runtime.gitStage(workspaceRepository.activeProjectRoot, path) }
    fun gitUnstage(path: String) = runGitAction("Unstaging $path") { runtime.gitUnstage(workspaceRepository.activeProjectRoot, path) }
    fun gitStageAll() = runGitAction("Staging changes") { runtime.gitStageAll(workspaceRepository.activeProjectRoot) }
    fun gitUnstageAll() = runGitAction("Unstaging changes") { runtime.gitUnstageAll(workspaceRepository.activeProjectRoot) }
    fun gitPull() = runGitAction("Pulling") { runtime.gitPull(workspaceRepository.activeProjectRoot) }
    fun gitPush() = runGitAction("Pushing") { runtime.gitPush(workspaceRepository.activeProjectRoot) }
    fun gitFetch() = runGitAction("Fetching") { runtime.gitFetch(workspaceRepository.activeProjectRoot) }
    fun gitCommit() {
        val message = state.value.commitMessage.trim()
        if (message.isBlank()) return
        runGitAction("Committing") { runtime.gitCommit(workspaceRepository.activeProjectRoot, message) }
        _state.update { it.copy(commitMessage = "") }
    }
    fun gitCreateBranch() {
        val branch = state.value.newBranch.trim(); if (branch.isBlank()) return
        runGitAction("Creating branch $branch") { runtime.gitCreateBranch(workspaceRepository.activeProjectRoot, branch) }
        _state.update { it.copy(newBranch = "") }
    }
    fun gitSwitchBranch(branch: String) = runGitAction("Switching to $branch") { runtime.gitSwitchBranch(workspaceRepository.activeProjectRoot, branch) }

    private fun runGitAction(label: String, action: suspend () -> Result<String>) {
        if (state.value.gitBusy) return
        viewModelScope.launch {
            _state.update { it.copy(gitBusy = true, gitNotice = label) }
            action().onSuccess { output -> _state.update { it.copy(gitBusy = false, gitNotice = output.takeLast(1000)) }; refreshProjectContext() }
                .onFailure { error -> _state.update { it.copy(gitBusy = false, gitNotice = error.message ?: "$label gagal") } }
        }
    }

    private fun refreshProjectContext() {
        val local = workspaceRepository.localStatus()
        _state.update { it.copy(runtimeStatus = runtime.status(), workspaceFiles = workspaceRepository.files(), repositoryStatus = local, projects = workspaceRepository.projects(), sessions = sessionStore.list(currentProjectKey())) }
        if (!runtime.status().available) return
        val projectRoot = workspaceRepository.activeProjectRoot
        projectRefreshJob?.cancel()
        projectRefreshJob = viewModelScope.launch {
            val inspected = runCatching { runtime.inspectRepository(projectRoot) }.getOrDefault(local)
            val branches = runCatching { runtime.gitBranches(projectRoot) }.getOrDefault(emptyList())
            val history = runCatching { runtime.gitHistory(projectRoot) }.getOrDefault(emptyList())
            val remote = runCatching { runtime.gitRemoteUrl(projectRoot) }.getOrNull()
            val models = runCatching { runtime.listModels(projectRoot) }.getOrDefault(emptyList())
            _state.update { it.copy(repositoryStatus = inspected, workspaceFiles = workspaceRepository.files(), projects = workspaceRepository.projects(), gitBranches = branches, gitHistory = history, remoteUrl = remote, modelOptions = models) }
        }
    }

    private fun ensureSession(prompt: String) {
        if (state.value.activeSessionId != null) return
        val title = prompt.lineSequence().firstOrNull()?.trim()?.take(52).orEmpty().ifBlank { "New session" }
        val session = sessionStore.create(currentProjectKey(), title)
        _state.update { it.copy(activeSessionId = session.summary.id, activeSessionTitle = title, sessions = sessionStore.list(currentProjectKey())) }
    }

    private fun loadProjectSession() {
        val saved = sessionStore.newest(currentProjectKey())
        _state.update { it.copy(activeSessionId = saved?.summary?.id, activeSessionTitle = saved?.summary?.title ?: "New session", conversationId = saved?.summary?.conversationId, events = saved?.events.orEmpty(), sessions = sessionStore.list(currentProjectKey()), selectedFile = null, selectedDiff = null, searchResults = emptyList(), contextItems = emptyList()) }
    }

    private fun persistSession() {
        val snapshot = state.value
        val id = snapshot.activeSessionId ?: return
        val key = currentProjectKey()
        val existing = sessionStore.load(key, id)
        val now = System.currentTimeMillis()
        val summary = (existing?.summary ?: SessionSummary(id, key, snapshot.activeSessionTitle, snapshot.conversationId, now, now)).copy(title = snapshot.activeSessionTitle, conversationId = snapshot.conversationId)
        sessionStore.save(SavedSession(summary, snapshot.events))
        _state.update { it.copy(sessions = sessionStore.list(key)) }
    }

    private fun appendEvent(event: AgentEvent, persist: Boolean = true) {
        _state.update { current -> current.copy(events = mergeEvent(current.events, event)) }
        if (persist && event !is AgentEvent.Output && event !is AgentEvent.Tool) persistSession()
    }

    private fun composePrompt(prompt: String, context: List<AgentContextItem>): String = buildString {
        append(prompt)
        if (context.isNotEmpty()) {
            append("\n\n# Attached GravityCode context\n")
            context.forEach { item ->
                when (item.type) {
                    ContextType.FILE -> append("\nFile: @").append(item.payload).append('\n')
                    ContextType.DIFF -> append("\nGit diff (read-only context):\n```diff\n").append(item.payload).append("\n```\n")
                    ContextType.TERMINAL -> append("\nTerminal output (read-only context):\n```text\n").append(item.payload).append("\n```\n")
                }
            }
        }
    }

    private fun activeTerminal(): TerminalTabState? = state.value.terminalTabs.firstOrNull { it.id == state.value.activeTerminalId }
    private fun updateActiveTerminal(transform: (TerminalTabState) -> TerminalTabState) { updateTerminal(state.value.activeTerminalId, transform) }
    private fun updateTerminal(id: String, transform: (TerminalTabState) -> TerminalTabState) = _state.update { current -> current.copy(terminalTabs = current.terminalTabs.map { if (it.id == id) transform(it) else it }) }

    private fun mergeEvent(existing: List<AgentEvent>, event: AgentEvent): List<AgentEvent> {
        val result = existing.toMutableList()
        when (event) {
            is AgentEvent.Output -> {
                val last = result.lastOrNull()
                if (last is AgentEvent.Output) result[result.lastIndex] = last.copy(text = last.text + event.text) else result += event
            }
            is AgentEvent.Tool -> {
                if (event.state != ToolState.RUNNING) {
                    val index = result.indexOfLast { it is AgentEvent.Tool && it.name == event.name && it.state == ToolState.RUNNING }
                    if (index >= 0) result[index] = event.copy(timestamp = (result[index] as AgentEvent.Tool).timestamp) else result += event
                } else result += event
            }
            is AgentEvent.Conversation -> Unit
            else -> result += event
        }
        return result.takeLast(240)
    }

    override fun onCleared() {
        persistSession(); runtime.cancel(); runtime.stopAllTerminals(); AgentForegroundService.stop(getApplication()); super.onCleared()
    }

    private companion object { const val TERMINAL_BUFFER_LIMIT = 260_000 }
}

enum class ContextType { FILE, DIFF, TERMINAL }

data class AgentContextItem(
    val id: String,
    val type: ContextType,
    val label: String,
    val payload: String,
)

data class TerminalTabState(
    val id: String,
    val title: String,
    val command: String = "",
    val output: String = "",
    val running: Boolean = false,
    val history: List<String> = emptyList(),
    val historyIndex: Int? = null,
)

data class AgentUiState(
    val prompt: String = "",
    val permissionMode: PermissionMode = PermissionMode.ACCEPT_EDITS,
    val runtimeStatus: RuntimeStatus,
    val workspaceFiles: List<WorkspaceEntry>,
    val repositoryStatus: ProjectStatus,
    val projects: List<ProjectSummary> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val activeSessionTitle: String = "New session",
    val conversationId: String? = null,
    val selectedFile: FilePreview? = null,
    val selectedTargetLine: Int = 0,
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
    val terminalTabs: List<TerminalTabState> = emptyList(),
    val activeTerminalId: String = "terminal-1",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val contextItems: List<AgentContextItem> = emptyList(),
    val commitMessage: String = "",
    val newBranch: String = "",
    val gitBusy: Boolean = false,
    val gitNotice: String = "",
    val gitBranches: List<String> = emptyList(),
    val gitHistory: List<GitCommit> = emptyList(),
    val remoteUrl: String? = null,
    val modelOptions: List<String> = emptyList(),
    val selectedModel: String? = null,
    val lastCrash: String? = null,
)
