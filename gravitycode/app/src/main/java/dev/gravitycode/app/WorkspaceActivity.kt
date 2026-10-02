package dev.gravitycode.app

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import dev.gravitycode.app.core.workspace.PreviewKind
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.feature.agent.AgentUiState
import dev.gravitycode.app.feature.agent.AgentViewModel
import dev.gravitycode.app.feature.agent.TerminalEntry
import dev.gravitycode.app.ui.theme.GravityTheme

class WorkspaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GravityTheme {
                val vm: AgentViewModel = viewModel()
                val state by vm.state.collectAsState()
                WorkspaceScreen(
                    state = state,
                    vm = vm,
                )
            }
        }
    }
}

private enum class WorkspaceTab(val label: String) {
    AGENT("Agent"),
    FILES("Files"),
    TERMINAL("Terminal"),
    GIT("Git"),
    STATUS("Status"),
}

@Composable
private fun WorkspaceScreen(state: AgentUiState, vm: AgentViewModel) {
    var selectedTab by rememberSaveable {
        mutableStateOf(if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) WorkspaceTab.AGENT.name else WorkspaceTab.STATUS.name)
    }
    val tab = WorkspaceTab.valueOf(selectedTab)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Header(state) },
        bottomBar = {
            Column {
                CompactStatusBarV04(state)
                if (tab == WorkspaceTab.AGENT) {
                    ComposerV04(state, vm::setPrompt, vm::runAgent, vm::cancel)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            TabRow(tab) { selectedTab = it.name }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                WorkspaceTab.AGENT -> AgentPaneV04(state, vm::setPermissionMode, vm::clearEvents, Modifier.weight(1f))
                WorkspaceTab.FILES -> FilesPaneV04(state, vm::refreshRuntime, vm::selectFile, Modifier.weight(1f))
                WorkspaceTab.TERMINAL -> TerminalPane(state, vm::setTerminalCommand, vm::runTerminalCommand, vm::clearTerminal, Modifier.weight(1f))
                WorkspaceTab.GIT -> GitPane(state, vm::refreshRuntime, Modifier.weight(1f))
                WorkspaceTab.STATUS -> StatusPaneV04(state, vm, Modifier.weight(1f))
            }
        }
    }

    state.selectedFile?.let { preview ->
        FileEditorDialog(
            preview = preview,
            draft = state.fileDraft,
            saving = state.fileSaving,
            onDraftChange = vm::setFileDraft,
            onSave = vm::saveSelectedFile,
            onClose = vm::closeFilePreview,
        )
    }
}

@Composable
private fun Header(state: AgentUiState) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GravityCode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.repositoryStatus.projectName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.repositoryStatus.branch?.let { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("· v0.4", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val ready = state.runtimeStatus.available && state.authState is AuthState.SignedIn
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, RoundedCornerShape(50)))
                Text(if (ready) "ready" else "setup", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TabRow(selected: WorkspaceTab, onSelected: (WorkspaceTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        WorkspaceTab.entries.forEach { tab ->
            FilterChip(selected = tab == selected, onClick = { onSelected(tab) }, label = { Text(tab.label) })
        }
    }
}

@Composable
private fun AgentPaneV04(
    state: AgentUiState,
    onPermissionChange: (PermissionMode) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Agent session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("cwd /workspace · ${state.repositoryStatus.projectName}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.events.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PermissionMode.entries.forEach { mode ->
                FilterChip(selected = state.permissionMode == mode, onClick = { onPermissionChange(mode) }, label = { Text(mode.title) })
            }
        }
        Spacer(Modifier.height(8.dp))
        AgentTimelineV04(state, Modifier.weight(1f))
    }
}

@Composable
private fun AgentTimelineV04(state: AgentUiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) listState.animateScrollToItem(state.events.lastIndex)
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (state.events.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ready for a task", fontWeight = FontWeight.SemiBold)
                    Text("Inspect, edit, build, test, or refactor this repository.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.events) { event -> EventCard(event) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EventCard(event: AgentEvent) {
    when (event) {
        is AgentEvent.Prompt -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(modifier = Modifier.widthIn(max = 335.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(event.text, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
            }
        }
        is AgentEvent.Output -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Text(event.text.trimEnd(), modifier = Modifier.fillMaxWidth().padding(13.dp))
        }
        is AgentEvent.Status -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
            Text(event.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is AgentEvent.Tool -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Row(modifier = Modifier.fillMaxWidth().padding(11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                Text("◆", color = if (event.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("${event.name} · ${event.state.name.lowercase()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (event.detail.isNotBlank() && event.detail !in setOf("Completed", "Running", "Agent started", "Turn completed")) {
                        Text(event.detail, maxLines = 5, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        is AgentEvent.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Error", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun FilesPaneV04(state: AgentUiState, onRefresh: () -> Unit, onSelectFile: (String) -> Unit, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember(state.repositoryStatus.projectName, state.workspaceFiles.size) {
        mutableStateOf(state.workspaceFiles.filter { it.directory && it.depth <= 1 }.map { it.relativePath }.toSet())
    }
    val visible = remember(state.workspaceFiles, expanded, query) {
        if (query.isNotBlank()) {
            state.workspaceFiles.filter { it.relativePath.contains(query.trim(), ignoreCase = true) }
        } else {
            state.workspaceFiles.filter { entry -> ancestors(entry.relativePath).all { it in expanded } }
        }
    }
    val fileCount = state.workspaceFiles.count { !it.directory }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.repositoryStatus.projectName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${state.repositoryStatus.workspacePath} · $fileCount files", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        SearchBox(query, { query = it }, "Search files…")
        Spacer(Modifier.height(5.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(visible) { entry ->
                FileTreeRow(
                    entry = entry,
                    expanded = entry.relativePath in expanded,
                    onToggle = {
                        expanded = if (entry.relativePath in expanded) expanded - entry.relativePath else expanded + entry.relativePath
                    },
                    onOpen = { onSelectFile(entry.relativePath) },
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun FileTreeRow(entry: WorkspaceEntry, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { if (entry.directory) onToggle() else onOpen() }
            .padding(start = (entry.depth.coerceAtMost(7) * 15).dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(if (entry.directory) if (expanded) "▾" else "▸" else fileGlyph(entry.extension), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        Text(entry.name, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (entry.directory) "DIR" else entry.extension?.uppercase()?.take(5) ?: formatBytesV04(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TerminalPane(
    state: AgentUiState,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.terminalEntries.size) {
        if (state.terminalEntries.isNotEmpty()) listState.animateScrollToItem(state.terminalEntries.lastIndex)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("/workspace · ${state.repositoryStatus.projectName}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.terminalEntries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.terminalEntries.isEmpty()) item {
                Text("Run shell commands in the active repository.", modifier = Modifier.padding(vertical = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            items(state.terminalEntries) { entry -> TerminalBlock(entry) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (state.terminalCommand.isBlank()) Text("npm test, git status, ls…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                BasicTextField(
                    value = state.terminalCommand,
                    onValueChange = onCommandChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                )
            }
            Button(onClick = onRun, enabled = state.terminalCommand.isNotBlank() && !state.terminalRunning && !state.running) {
                if (state.terminalRunning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run")
            }
        }
    }
}

@Composable
private fun TerminalBlock(entry: TerminalEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("$ ${entry.command}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            if (entry.output.isNotBlank()) Text(entry.output.trimEnd(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("exit ${entry.exitCode}", style = MaterialTheme.typography.labelSmall, color = if (entry.exitCode == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GitPane(state: AgentUiState, onRefresh: () -> Unit, modifier: Modifier) {
    val repo = state.repositoryStatus
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Source control", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(repo.branch ?: "not a git repository", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusLineV04("HEAD", repo.head ?: "—")
                StatusLineV04("Latest", repo.latestCommit ?: "—")
                StatusLineV04("Sync", if (repo.ahead == 0 && repo.behind == 0) "up to date" else "↑${repo.ahead}  ↓${repo.behind}")
                StatusLineV04("Working tree", if (repo.changedFiles == 0) "clean" else "${repo.changedFiles} changed")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Changes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (repo.changedPaths.isEmpty()) {
            Text("Working tree clean", modifier = Modifier.padding(vertical = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(repo.changedPaths) { change ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(change.status, modifier = Modifier.width(28.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        Text(change.path, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPaneV04(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Project", fontWeight = FontWeight.SemiBold)
                StatusLineV04("Name", state.repositoryStatus.projectName)
                StatusLineV04("Path", state.repositoryStatus.workspacePath)
                StatusLineV04("Agent cwd", "/workspace")
                StatusLineV04("Branch", state.repositoryStatus.branch ?: "—")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Runtime & account", fontWeight = FontWeight.SemiBold)
                        Text(state.runtimeStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = vm::refreshRuntime) { Text("Refresh") }
                }
                if (state.installing) {
                    LinearProgressIndicator(progress = { state.installProgress }, modifier = Modifier.fillMaxWidth())
                    Text(state.installMessage, style = MaterialTheme.typography.bodySmall)
                } else if (!state.runtimeStatus.available) {
                    Button(onClick = vm::setupRuntime, modifier = Modifier.fillMaxWidth()) { Text("Setup runtime") }
                } else {
                    when (val auth = state.authState) {
                        AuthState.SignedOut -> Button(onClick = vm::beginAuth, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                        AuthState.Starting -> BusyRowV04("Preparing sign-in…")
                        is AuthState.AwaitingCode -> {
                            Button(onClick = { uriHandler.openUri(auth.url) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                            SearchBox(state.authCode, vm::setAuthCode, "Paste authorization code")
                            Button(onClick = vm::submitAuthCode, enabled = state.authCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit code") }
                        }
                        AuthState.Verifying -> BusyRowV04("Verifying account…")
                        AuthState.SignedIn -> StatusLineV04("Account", "Google connected")
                        is AuthState.Error -> {
                            Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = vm::beginAuth, modifier = Modifier.fillMaxWidth()) { Text("Retry sign in") }
                        }
                    }
                }
                StatusLineV04("Permission", state.permissionMode.title)
                StatusLineV04("Agent", if (state.running) "running" else "idle")
                StatusLineV04("Terminal", if (state.terminalRunning) "running" else "idle")
            }
        }
        if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Open another repository", fontWeight = FontWeight.SemiBold)
                    SearchBox(state.cloneUrl, vm::setCloneUrl, "https://github.com/owner/repo.git")
                    OutlinedButton(onClick = vm::cloneRepository, enabled = state.cloneUrl.isNotBlank() && !state.cloning, modifier = Modifier.fillMaxWidth()) {
                        if (state.cloning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Clone into workspace")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FileEditorDialog(
    preview: FilePreview,
    draft: String,
    saving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 5.dp) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preview.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
                        Text("${formatBytesV04(preview.sizeBytes)} · ${preview.path}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                when (preview.kind) {
                    PreviewKind.IMAGE -> {
                        val bitmap = remember(preview.path) { preview.imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = preview.path, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            } else {
                                Text("Image could not be decoded", color = Color.White)
                            }
                        }
                    }
                    PreviewKind.TEXT -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(12.dp),
                        ) {
                            BasicTextField(
                                value = draft,
                                onValueChange = onDraftChange,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onSave, enabled = !saving && draft != preview.content) {
                                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
                            }
                        }
                    }
                    PreviewKind.BINARY -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Binary preview is not available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
private fun BusyRowV04(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusLineV04(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = if (label in setOf("Path", "Agent cwd", "HEAD")) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun CompactStatusBarV04(state: AgentUiState) {
    val repo = state.repositoryStatus
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(repo.branch ?: repo.projectName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (repo.changedFiles == 0) "clean" else "${repo.changedFiles} changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.permissionMode.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.running) "agent running" else if (state.terminalRunning) "terminal running" else "idle", style = MaterialTheme.typography.labelSmall, color = if (state.running || state.terminalRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComposerV04(state: AgentUiState, onPromptChange: (String) -> Unit, onRun: () -> Unit, onCancel: () -> Unit) {
    val ready = state.runtimeStatus.available && state.authState is AuthState.SignedIn
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(horizontal = 13.dp, vertical = 11.dp)) {
                if (state.prompt.isBlank()) Text(if (ready) "Ask GravityCode to inspect, edit, build…" else "Finish setup in Status first", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                BasicTextField(value = state.prompt, onValueChange = onPromptChange, modifier = Modifier.fillMaxWidth(), maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
            }
            AnimatedVisibility(state.running) { TextButton(onClick = onCancel) { Text("Stop") } }
            Button(onClick = onRun, enabled = ready && state.prompt.isNotBlank() && !state.running && !state.terminalRunning) {
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run")
            }
        }
    }
}

private fun ancestors(path: String): List<String> {
    val parts = path.split('/').dropLast(1)
    if (parts.isEmpty()) return emptyList()
    return parts.indices.map { index -> parts.take(index + 1).joinToString("/") }
}

private fun fileGlyph(extension: String?): String = when (extension) {
    "js", "ts", "kt", "java", "py", "c", "cpp", "h" -> "◇"
    "json", "yaml", "yml", "toml" -> "◆"
    "md", "txt" -> "·"
    "png", "jpg", "jpeg", "webp", "gif" -> "▧"
    else -> "·"
}

private fun formatBytesV04(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
