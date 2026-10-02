package dev.gravitycode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.workspace.FilePreview
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.feature.agent.AgentUiState
import dev.gravitycode.app.feature.agent.AgentViewModel
import dev.gravitycode.app.ui.theme.GravityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GravityTheme {
                val vm: AgentViewModel = viewModel()
                val state by vm.state.collectAsState()
                GravityCodeScreen(
                    state = state,
                    onPromptChange = vm::setPrompt,
                    onPermissionChange = vm::setPermissionMode,
                    onRun = vm::runAgent,
                    onCancel = vm::cancel,
                    onRefresh = vm::refreshRuntime,
                    onSetupRuntime = vm::setupRuntime,
                    onBeginAuth = vm::beginAuth,
                    onAuthCodeChange = vm::setAuthCode,
                    onSubmitAuthCode = vm::submitAuthCode,
                    onCloneUrlChange = vm::setCloneUrl,
                    onClone = vm::cloneRepository,
                    onClearEvents = vm::clearEvents,
                    onSelectFile = vm::selectFile,
                    onCloseFile = vm::closeFilePreview,
                )
            }
        }
    }
}

private enum class WorkspaceTab(val label: String) {
    AGENT("Agent"),
    FILES("Files"),
    STATUS("Status"),
}

@Composable
private fun GravityCodeScreen(
    state: AgentUiState,
    onPromptChange: (String) -> Unit,
    onPermissionChange: (PermissionMode) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onSetupRuntime: () -> Unit,
    onBeginAuth: () -> Unit,
    onAuthCodeChange: (String) -> Unit,
    onSubmitAuthCode: () -> Unit,
    onCloneUrlChange: (String) -> Unit,
    onClone: () -> Unit,
    onClearEvents: () -> Unit,
    onSelectFile: (String) -> Unit,
    onCloseFile: () -> Unit,
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) WorkspaceTab.AGENT.name else WorkspaceTab.STATUS.name)
    }
    val tab = WorkspaceTab.valueOf(selectedTab)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { AppHeader(state) },
        bottomBar = {
            Column {
                CompactStatusBar(state)
                Composer(state, onPromptChange, onRun, onCancel)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            WorkspaceTabs(tab) { selectedTab = it.name }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                WorkspaceTab.AGENT -> AgentPane(state, onPermissionChange, onClearEvents, Modifier.weight(1f))
                WorkspaceTab.FILES -> FilesPane(state, onRefresh, onSelectFile, Modifier.weight(1f))
                WorkspaceTab.STATUS -> StatusPane(
                    state = state,
                    onRefresh = onRefresh,
                    onSetupRuntime = onSetupRuntime,
                    onBeginAuth = onBeginAuth,
                    onAuthCodeChange = onAuthCodeChange,
                    onSubmitAuthCode = onSubmitAuthCode,
                    onCloneUrlChange = onCloneUrlChange,
                    onClone = onClone,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    state.selectedFile?.let { preview -> FilePreviewDialog(preview, onCloseFile) }
}

@Composable
private fun AppHeader(state: AgentUiState) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GravityCode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.repositoryStatus.projectName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.repositoryStatus.branch?.let {
                        Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("· v0.3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RuntimeDot(state.runtimeStatus.available, state.authState is AuthState.SignedIn)
        }
    }
}

@Composable
private fun RuntimeDot(runtimeReady: Boolean, signedIn: Boolean) {
    val ready = runtimeReady && signedIn
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.size(9.dp).background(
                if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                RoundedCornerShape(50),
            ),
        )
        Text(if (ready) "ready" else if (runtimeReady) "login" else "setup", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WorkspaceTabs(selected: WorkspaceTab, onSelected: (WorkspaceTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkspaceTab.entries.forEach { tab ->
            FilterChip(selected = selected == tab, onClick = { onSelected(tab) }, label = { Text(tab.label) })
        }
    }
}

@Composable
private fun AgentPane(
    state: AgentUiState,
    onPermissionChange: (PermissionMode) -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (state.events.isNotEmpty()) TextButton(onClick = onClearEvents) { Text("Clear") }
        }
        PermissionRow(state.permissionMode, onPermissionChange)
        Spacer(Modifier.height(8.dp))
        EventTimeline(state, Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRow(selected: PermissionMode, onSelected: (PermissionMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        PermissionMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelected(mode) }, label = { Text(mode.title) })
        }
    }
}

@Composable
private fun EventTimeline(state: AgentUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) listState.animateScrollToItem(state.events.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (state.events.isEmpty()) item { AgentEmptyState(state) }
        items(state.events) { event -> EventRow(event) }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun AgentEmptyState(state: AgentUiState) {
    val hint = when {
        !state.runtimeStatus.available -> "Runtime belum siap. Buka tab Status untuk setup."
        state.authState !is AuthState.SignedIn -> "Login Antigravity dari tab Status."
        !state.repositoryStatus.repository -> "Clone repository dari tab Status, atau langsung beri task pada workspace."
        else -> "Agent siap bekerja di ${state.repositoryStatus.projectName}."
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("New agent session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventRow(event: AgentEvent) {
    when (event) {
        is AgentEvent.Prompt -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                modifier = Modifier.widthIn(max = 330.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(event.text, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is AgentEvent.Output -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Text(event.text.trimEnd(), modifier = Modifier.fillMaxWidth().padding(13.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is AgentEvent.Tool -> ToolEventRow(event)
        is AgentEvent.Status -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
            Text(event.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is AgentEvent.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Error", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ToolEventRow(event: AgentEvent.Tool) {
    val stateLabel = when (event.state) {
        ToolState.RUNNING -> "running"
        ToolState.SUCCESS -> "done"
        ToolState.FAILED -> "failed"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("◆", color = if (event.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(event.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (event.detail.isNotBlank() && event.detail !in setOf("Completed", "Running", "Agent started", "Turn completed")) {
                    Text(
                        event.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilesPane(
    state: AgentUiState,
    onRefresh: () -> Unit,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileCount = state.workspaceFiles.count { !it.directory }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.repositoryStatus.projectName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(
                    "/workspace · $fileCount files",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Spacer(Modifier.height(5.dp))
        if (state.workspaceFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Workspace kosong", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.workspaceFiles) { entry -> FileRow(entry, onSelectFile) }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun FileRow(entry: WorkspaceEntry, onSelectFile: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.directory) { onSelectFile(entry.relativePath) }
            .padding(start = (entry.depth.coerceAtMost(6) * 14).dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (entry.directory) "▾" else "·", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Text(
            entry.name,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (entry.directory) "DIR" else entry.extension?.uppercase()?.take(5) ?: formatBytes(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusPane(
    state: AgentUiState,
    onRefresh: () -> Unit,
    onSetupRuntime: () -> Unit,
    onBeginAuth: () -> Unit,
    onAuthCodeChange: (String) -> Unit,
    onSubmitAuthCode: () -> Unit,
    onCloneUrlChange: (String) -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProjectStatusCard(state)
        RuntimeCard(state, onRefresh, onSetupRuntime, onBeginAuth, onAuthCodeChange, onSubmitAuthCode)
        if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) {
            CloneCard(state, onCloneUrlChange, onClone)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProjectStatusCard(state: AgentUiState) {
    val repo = state.repositoryStatus
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Project", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val label = when {
                    !repo.repository -> "workspace"
                    !repo.statusKnown -> "checking"
                    repo.changedFiles == 0 -> "clean"
                    else -> "${repo.changedFiles} changed"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusLine("Name", repo.projectName)
            StatusLine("Path", "/workspace")
            StatusLine("Branch", repo.branch ?: if (repo.repository) "detached" else "not a git repo")
            if (repo.repository) {
                StatusLine("HEAD", repo.head ?: "—")
                StatusLine("Changes", if (repo.statusKnown) repo.changedFiles.toString() else "checking…")
                if (repo.ahead > 0 || repo.behind > 0) StatusLine("Sync", "↑${repo.ahead}  ↓${repo.behind}")
                repo.latestCommit?.let { StatusLine("Latest", it) }
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    state: AgentUiState,
    onRefresh: () -> Unit,
    onSetup: () -> Unit,
    onBeginAuth: () -> Unit,
    onAuthCodeChange: (String) -> Unit,
    onSubmitAuthCode: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Runtime & account", fontWeight = FontWeight.SemiBold)
                    Text(state.runtimeStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }

            if (state.installing) {
                LinearProgressIndicator(progress = { state.installProgress }, modifier = Modifier.fillMaxWidth())
                Text(state.installMessage, style = MaterialTheme.typography.bodySmall)
            } else if (!state.runtimeStatus.available) {
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup runtime") }
            } else {
                when (val auth = state.authState) {
                    AuthState.SignedOut -> Button(onClick = onBeginAuth, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                    AuthState.Starting -> BusyRow("Menyiapkan Google sign-in…")
                    is AuthState.AwaitingCode -> {
                        Button(onClick = { uriHandler.openUri(auth.url) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                        InputSurface(state.authCode, onAuthCodeChange, "Paste authorization code")
                        Button(onClick = onSubmitAuthCode, enabled = state.authCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit code") }
                    }
                    AuthState.Verifying -> BusyRow("Verifying Antigravity account…")
                    AuthState.SignedIn -> StatusLine("Account", "Google connected")
                    is AuthState.Error -> {
                        Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onBeginAuth, modifier = Modifier.fillMaxWidth()) { Text("Retry sign in") }
                    }
                }
            }
            StatusLine("Permission", state.permissionMode.title)
            StatusLine("Session", if (state.running) "running" else "idle")
        }
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CloneCard(state: AgentUiState, onCloneUrlChange: (String) -> Unit, onClone: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.repositoryStatus.repository) "Open another repository" else "Open repository", fontWeight = FontWeight.SemiBold)
            InputSurface(state.cloneUrl, onCloneUrlChange, "https://github.com/owner/repo.git")
            OutlinedButton(onClick = onClone, enabled = state.cloneUrl.isNotBlank() && !state.cloning, modifier = Modifier.fillMaxWidth()) {
                if (state.cloning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Clone into workspace")
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(86.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = if (label in setOf("Path", "HEAD")) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun InputSurface(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
        )
    }
}

@Composable
private fun CompactStatusBar(state: AgentUiState) {
    val repo = state.repositoryStatus
    val branch = repo.branch ?: repo.projectName
    val changes = when {
        !repo.repository -> "workspace"
        !repo.statusKnown -> "checking"
        repo.changedFiles == 0 -> "clean"
        else -> "${repo.changedFiles} changes"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(branch, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(changes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.permissionMode.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.running) "agent running" else "idle", style = MaterialTheme.typography.labelSmall, color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(state: AgentUiState, onPromptChange: (String) -> Unit, onRun: () -> Unit, onCancel: () -> Unit) {
    val ready = state.runtimeStatus.available && state.authState is AuthState.SignedIn
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                if (state.prompt.isBlank()) {
                    Text(
                        if (ready) "Ask GravityCode to inspect, edit, build…" else "Finish setup in Status first",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BasicTextField(
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 4,
                )
            }
            AnimatedVisibility(state.running) { TextButton(onClick = onCancel) { Text("Stop") } }
            Button(onClick = onRun, enabled = ready && state.prompt.isNotBlank() && !state.running) {
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run")
            }
        }
    }
}

@Composable
private fun FilePreviewDialog(preview: FilePreview, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preview.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatBytes(preview.sizeBytes)}${if (preview.truncated) " · preview truncated" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text(preview.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(preview.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
