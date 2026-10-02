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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.model.ToolState
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.workspace.FilePreview
import dev.gravitycode.app.core.workspace.GitDiff
import dev.gravitycode.app.core.workspace.PreviewKind
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.feature.agent.AgentUiState
import dev.gravitycode.app.feature.agent.AgentViewModel
import dev.gravitycode.app.ui.theme.GravityTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkspaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GravityTheme {
                val vm: AgentViewModel = viewModel()
                val state by vm.state.collectAsState()
                WorkspaceScreen(state, vm)
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

    LaunchedEffect(tab, state.runtimeStatus.available, state.repositoryStatus.workspacePath) {
        if (tab == WorkspaceTab.TERMINAL && state.runtimeStatus.available) vm.startTerminalSession()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Header(state) },
        bottomBar = {
            Column {
                CompactStatusBar(state)
                if (tab == WorkspaceTab.AGENT) Composer(state, vm::setPrompt, vm::runAgent, vm::cancel)
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
                WorkspaceTab.AGENT -> AgentPane(state, vm, Modifier.weight(1f))
                WorkspaceTab.FILES -> FilesPane(state, vm::refreshRuntime, vm::selectFile, Modifier.weight(1f))
                WorkspaceTab.TERMINAL -> TerminalPane(state, vm, Modifier.weight(1f))
                WorkspaceTab.GIT -> GitPane(state, vm, Modifier.weight(1f))
                WorkspaceTab.STATUS -> StatusPane(state, vm, Modifier.weight(1f))
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

    if (state.selectedDiff != null || state.diffLoading || state.diffError != null) {
        DiffDialog(
            diff = state.selectedDiff,
            loading = state.diffLoading,
            error = state.diffError,
            reverting = state.diffReverting,
            onClose = vm::closeDiff,
            onOpenFile = { path ->
                vm.closeDiff()
                vm.selectFile(path)
            },
            onRevert = vm::revertSelectedDiff,
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
                    Text("· v0.5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun AgentPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Agent session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "cwd ${state.repositoryStatus.workspacePath} · ${if (state.running) "running" else "idle"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.events.isNotEmpty()) TextButton(onClick = vm::clearEvents) { Text("Clear") }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PermissionMode.entries.forEach { mode ->
                FilterChip(selected = state.permissionMode == mode, onClick = { vm.setPermissionMode(mode) }, label = { Text(mode.title) })
            }
        }
        Spacer(Modifier.height(8.dp))
        AgentTimeline(state, Modifier.weight(1f))
    }
}

@Composable
private fun AgentTimeline(state: AgentUiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size, state.events.lastOrNull()?.let { if (it is AgentEvent.Output) it.text.length else 0 }) {
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
        is AgentEvent.Tool -> ToolTimelineCard(event)
        is AgentEvent.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Error", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ToolTimelineCard(event: AgentEvent.Tool) {
    var expanded by rememberSaveable(event.timestamp, event.name) { mutableStateOf(event.state == ToolState.FAILED) }
    val stateLabel = when (event.state) {
        ToolState.RUNNING -> "RUNNING"
        ToolState.SUCCESS -> "DONE"
        ToolState.FAILED -> "FAILED"
    }
    val detailVisible = event.detail.isNotBlank() && event.detail !in setOf("Completed", "Running", "Agent started", "Turn completed")
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = detailVisible) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (event.state == ToolState.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (event.state == ToolState.SUCCESS) "✓" else "!", color = if (event.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(toolTitle(event.name), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (!expanded && detailVisible) {
                        Text(event.detail.lineSequence().firstOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = if (event.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (detailVisible) Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded && detailVisible) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(10.dp),
                ) {
                    Text(event.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun FilesPane(state: AgentUiState, onRefresh: () -> Unit, onSelectFile: (String) -> Unit, modifier: Modifier) {
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
            if (entry.directory) "DIR" else entry.extension?.uppercase()?.take(5) ?: formatBytes(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TerminalPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val outputScroll = rememberScrollState()
    LaunchedEffect(state.terminalOutput.length) {
        if (state.terminalOutput.isNotEmpty()) outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Interactive terminal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.repositoryStatus.workspacePath} · ${if (state.terminalRunning) "shell active" else "stopped"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(8.dp).background(if (state.terminalRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
        }
        Spacer(Modifier.height(7.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::terminalInterrupt, enabled = state.terminalRunning) { Text("Ctrl+C") }
            OutlinedButton(onClick = vm::terminalTab, enabled = state.terminalRunning) { Text("Tab") }
            OutlinedButton(onClick = vm::terminalHistoryPrevious) { Text("↑") }
            OutlinedButton(onClick = vm::terminalHistoryNext) { Text("↓") }
            OutlinedButton(onClick = vm::restartTerminalSession, enabled = state.runtimeStatus.available) { Text("Restart") }
            TextButton(onClick = vm::clearTerminal) { Text("Clear") }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0B0D), RoundedCornerShape(12.dp)).padding(10.dp),
        ) {
            if (state.terminalOutput.isBlank()) {
                Text("Starting shell…", color = Color(0xFF8C8C92), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    ansiText(state.terminalOutput),
                    modifier = Modifier.fillMaxSize().verticalScroll(outputScroll),
                    color = Color(0xFFE8E8EA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (state.terminalCommand.isBlank()) Text("type command…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                BasicTextField(
                    value = state.terminalCommand,
                    onValueChange = vm::setTerminalCommand,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.submitTerminalCommand() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                )
            }
            Button(onClick = vm::submitTerminalCommand, enabled = state.terminalCommand.isNotBlank() && state.terminalRunning) { Text("Enter") }
        }
    }
}

@Composable
private fun GitPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val repo = state.repositoryStatus
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Source control", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(repo.branch ?: "not a git repository", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.diffLoading) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
            TextButton(onClick = vm::refreshRuntime) { Text("Refresh") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusLine("HEAD", repo.head ?: "—")
                StatusLine("Latest", repo.latestCommit ?: "—")
                StatusLine("Sync", if (repo.ahead == 0 && repo.behind == 0) "up to date" else "↑${repo.ahead}  ↓${repo.behind}")
                StatusLine("Working tree", if (repo.changedFiles == 0) "clean" else "${repo.changedFiles} changed")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Changes · tap a file for diff", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (repo.changedPaths.isEmpty()) {
            Text("Working tree clean", modifier = Modifier.padding(vertical = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(repo.changedPaths) { change ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectDiff(change.path, change.status) }.padding(vertical = 10.dp, horizontal = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(change.status, modifier = Modifier.width(28.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        Text(change.path, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Project", fontWeight = FontWeight.SemiBold)
                StatusLine("Name", state.repositoryStatus.projectName)
                StatusLine("Path", state.repositoryStatus.workspacePath)
                StatusLine("Branch", state.repositoryStatus.branch ?: "—")
                StatusLine("Changes", state.repositoryStatus.changedFiles.toString())
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
                        AuthState.Starting -> BusyRow("Preparing sign-in…")
                        is AuthState.AwaitingCode -> {
                            Button(onClick = { uriHandler.openUri(auth.url) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                            SearchBox(state.authCode, vm::setAuthCode, "Paste authorization code")
                            Button(onClick = vm::submitAuthCode, enabled = state.authCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit code") }
                        }
                        AuthState.Verifying -> BusyRow("Verifying account…")
                        AuthState.SignedIn -> StatusLine("Account", "Google connected")
                        is AuthState.Error -> {
                            Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = vm::beginAuth, modifier = Modifier.fillMaxWidth()) { Text("Retry sign in") }
                        }
                    }
                }
                StatusLine("Permission", state.permissionMode.title)
                StatusLine("Agent", if (state.running) "running" else "idle")
                StatusLine("Terminal", if (state.terminalRunning) "active PTY" else "stopped")
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
private fun DiffDialog(
    diff: GitDiff?,
    loading: Boolean,
    error: String?,
    reverting: Boolean,
    onClose: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRevert: () -> Unit,
) {
    var confirmRevert by remember(diff?.path) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 5.dp) {
            Column(Modifier.fillMaxSize().padding(top = 18.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(diff?.path ?: "Diff", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (diff != null) {
                            Text("${diff.status} · +${diff.additions} -${diff.deletions}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onClose) { Text("Keep") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { Text(error, color = MaterialTheme.colorScheme.error) }
                    diff != null -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest),
                        ) {
                            itemsIndexed(diff.patch.lines()) { index, line -> DiffLine(index + 1, line) }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                        if (confirmRevert) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Revert perubahan file ini?", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = { confirmRevert = false }) { Text("Cancel") }
                                    Button(onClick = onRevert, enabled = !reverting) {
                                        if (reverting) CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp) else Text("Revert")
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            OutlinedButton(onClick = { onOpenFile(diff.path) }) { Text("Open file") }
                            OutlinedButton(onClick = { confirmRevert = true }, enabled = !reverting) { Text("Revert") }
                            Button(onClick = onClose) { Text("Keep change") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(number: Int, line: String) {
    val bg = when {
        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF102617)
        line.startsWith("-") && !line.startsWith("---") -> Color(0xFF2B1518)
        line.startsWith("@@") -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        else -> Color.Transparent
    }
    val fg = when {
        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF8BD49C)
        line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF9A9A)
        line.startsWith("@@") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
        Text(number.toString(), modifier = Modifier.width(42.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(line.ifEmpty { " " }, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 5.dp) {
            when (preview.kind) {
                PreviewKind.IMAGE -> ImagePreview(preview, onClose)
                PreviewKind.TEXT -> CodeEditor(preview, draft, saving, onDraftChange, onSave, onClose)
                PreviewKind.BINARY -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    EditorHeader(preview, modified = false, onClose = onClose)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Binary preview is not available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(preview: FilePreview, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        EditorHeader(preview, modified = false, onClose = onClose)
        Spacer(Modifier.height(8.dp))
        val bitmap = remember(preview.path, preview.imageBytes?.size) { preview.imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = preview.path, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Image could not be decoded", color = Color.White)
        }
    }
}

@Composable
private fun CodeEditor(
    preview: FilePreview,
    draft: String,
    saving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var editor by remember(preview.path) { mutableStateOf(TextFieldValue(draft)) }
    var undoStack by remember(preview.path) { mutableStateOf(emptyList<String>()) }
    var redoStack by remember(preview.path) { mutableStateOf(emptyList<String>()) }
    var findOpen by rememberSaveable(preview.path) { mutableStateOf(false) }
    var findText by rememberSaveable(preview.path) { mutableStateOf("") }
    var replaceText by rememberSaveable(preview.path) { mutableStateOf("") }
    var goLine by rememberSaveable(preview.path) { mutableStateOf("") }
    val modified = draft != preview.content
    val readOnly = preview.truncated

    LaunchedEffect(draft) {
        if (editor.text != draft) editor = editor.copy(text = draft, selection = TextRange(draft.length.coerceAtMost(editor.selection.end)))
    }

    fun applyText(next: TextFieldValue, recordUndo: Boolean = true) {
        if (readOnly) return
        if (recordUndo && next.text != editor.text) {
            undoStack = (undoStack + editor.text).takeLast(120)
            redoStack = emptyList()
        }
        editor = next
        onDraftChange(next.text)
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + editor.text).takeLast(120)
        undoStack = undoStack.dropLast(1)
        editor = TextFieldValue(previous, selection = TextRange(previous.length.coerceAtMost(editor.selection.start)))
        onDraftChange(previous)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + editor.text).takeLast(120)
        redoStack = redoStack.dropLast(1)
        editor = TextFieldValue(next, selection = TextRange(next.length.coerceAtMost(editor.selection.start)))
        onDraftChange(next)
    }

    fun findNext() {
        if (findText.isBlank()) return
        val start = editor.selection.end.coerceAtMost(editor.text.length)
        var index = editor.text.indexOf(findText, startIndex = start, ignoreCase = true)
        if (index < 0) index = editor.text.indexOf(findText, startIndex = 0, ignoreCase = true)
        if (index >= 0) editor = editor.copy(selection = TextRange(index, index + findText.length))
    }

    fun replaceCurrent() {
        if (readOnly || findText.isBlank()) return
        val selected = editor.text.substring(editor.selection.min, editor.selection.max)
        if (selected.equals(findText, ignoreCase = true)) {
            val text = editor.text.replaceRange(editor.selection.min, editor.selection.max, replaceText)
            val cursor = editor.selection.min + replaceText.length
            applyText(TextFieldValue(text, selection = TextRange(cursor)))
        } else findNext()
    }

    fun replaceAll() {
        if (readOnly || findText.isBlank()) return
        val replaced = editor.text.replace(findText, replaceText, ignoreCase = true)
        if (replaced != editor.text) applyText(TextFieldValue(replaced, selection = TextRange(0)))
    }

    fun jumpToLine() {
        val line = goLine.toIntOrNull()?.coerceAtLeast(1) ?: return
        val offset = lineOffset(editor.text, line)
        editor = editor.copy(selection = TextRange(offset))
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        EditorHeader(preview, modified, onClose)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { findOpen = !findOpen }) { Text("Find") }
            TextButton(onClick = ::undo, enabled = undoStack.isNotEmpty() && !readOnly) { Text("Undo") }
            TextButton(onClick = ::redo, enabled = redoStack.isNotEmpty() && !readOnly) { Text("Redo") }
            Box(modifier = Modifier.width(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
                if (goLine.isBlank()) Text("Line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(value = goLine, onValueChange = { goLine = it.filter(Char::isDigit).take(6) }, singleLine = true, textStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace))
            }
            OutlinedButton(onClick = ::jumpToLine) { Text("Go") }
            Spacer(Modifier.width(4.dp))
            Text("Ln ${currentLine(editor.text, editor.selection.start)} · ${editor.text.length} chars", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(findOpen) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SearchBox(findText, { findText = it }, "Find text")
                SearchBox(replaceText, { replaceText = it }, "Replace with")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = ::findNext) { Text("Next") }
                    OutlinedButton(onClick = ::replaceCurrent, enabled = !readOnly) { Text("Replace") }
                    OutlinedButton(onClick = ::replaceAll, enabled = !readOnly) { Text("Replace all") }
                }
            }
        }
        if (readOnly) {
            Text("Large file preview is truncated. Editing is disabled to prevent accidental data loss.", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        EditorTextArea(
            value = editor,
            extension = preview.path.substringAfterLast('.', "").lowercase(),
            readOnly = readOnly,
            onValueChange = { next ->
                if (next.text == editor.text) editor = next else applyText(next)
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (modified) Text("● modified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Button(onClick = onSave, enabled = !saving && modified && !readOnly) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }
    }
}

@Composable
private fun EditorHeader(preview: FilePreview, modified: Boolean, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text((if (modified) "● " else "") + preview.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
            Text("${formatBytes(preview.sizeBytes)} · ${preview.path}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun EditorTextArea(
    value: TextFieldValue,
    extension: String,
    readOnly: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val baseStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
    val transform = remember(extension, MaterialTheme.colorScheme.primary) {
        CodeHighlightTransformation(
            extension = extension,
            keyword = Color(0xFFB8A1FF),
            stringColor = Color(0xFFA8D8A8),
            comment = Color(0xFF7E858F),
            number = Color(0xFFFFC98B),
        )
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))) {
        Row(
            modifier = Modifier.horizontalScroll(horizontal).verticalScroll(vertical).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                numbers,
                modifier = Modifier.width(48.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 19.sp,
            )
            Box(Modifier.widthIn(min = 900.dp).heightIn(min = 520.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !readOnly,
                    textStyle = baseStyle,
                    visualTransformation = transform,
                )
            }
        }
    }
}

private class CodeHighlightTransformation(
    private val extension: String,
    private val keyword: Color,
    private val stringColor: Color,
    private val comment: Color,
    private val number: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder(source)
        val keywords = when (extension) {
            "kt", "kts" -> "class|fun|val|var|if|else|when|for|while|return|object|data|sealed|interface|private|public|internal|override|suspend|import|package|null|true|false"
            "js", "jsx", "ts", "tsx" -> "const|let|var|function|class|if|else|for|while|return|async|await|import|export|from|new|try|catch|throw|true|false|null|undefined|interface|type"
            "py" -> "def|class|if|elif|else|for|while|return|async|await|import|from|as|try|except|raise|True|False|None|with|lambda"
            "java", "c", "h", "cpp", "cc" -> "class|struct|enum|if|else|for|while|return|public|private|protected|static|const|void|int|long|double|float|bool|true|false|null|new|try|catch|throw|include"
            else -> "true|false|null"
        }
        Regex("\\b($keywords)\\b").findAll(source).forEach { builder.addStyle(SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
        Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(source).forEach { builder.addStyle(SpanStyle(color = number), it.range.first, it.range.last + 1) }
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'").findAll(source).forEach { builder.addStyle(SpanStyle(color = stringColor), it.range.first, it.range.last + 1) }
        val commentRegex = if (extension == "py") Regex("#.*", RegexOption.MULTILINE) else Regex("//.*|/\\*[\\s\\S]*?\\*/")
        commentRegex.findAll(source).forEach { builder.addStyle(SpanStyle(color = comment), it.range.first, it.range.last + 1) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
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
private fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = if (label in setOf("Path", "HEAD")) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun CompactStatusBar(state: AgentUiState) {
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
            if (state.terminalRunning) {
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PTY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
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
            Box(modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(horizontal = 13.dp, vertical = 11.dp)) {
                if (state.prompt.isBlank()) Text(if (ready) "Ask GravityCode to inspect, edit, build…" else "Finish setup in Status first", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                BasicTextField(value = state.prompt, onValueChange = onPromptChange, modifier = Modifier.fillMaxWidth(), maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
            }
            AnimatedVisibility(state.running) { TextButton(onClick = onCancel) { Text("Stop") } }
            Button(onClick = onRun, enabled = ready && state.prompt.isNotBlank() && !state.running) {
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run")
            }
        }
    }
}

private fun toolTitle(name: String): String {
    val clean = name.trim().ifBlank { "tool" }
    return when {
        clean.equals("agy", ignoreCase = true) -> "Antigravity"
        clean.contains("read", ignoreCase = true) -> "Read · $clean"
        clean.contains("write", ignoreCase = true) || clean.contains("edit", ignoreCase = true) -> "Edit · $clean"
        clean.contains("shell", ignoreCase = true) || clean.contains("command", ignoreCase = true) || clean.contains("exec", ignoreCase = true) -> "Run · $clean"
        clean.contains("search", ignoreCase = true) -> "Search · $clean"
        else -> clean
    }
}

private fun ancestors(path: String): List<String> {
    val parts = path.split('/').dropLast(1)
    if (parts.isEmpty()) return emptyList()
    return parts.indices.map { index -> parts.take(index + 1).joinToString("/") }
}

private fun fileGlyph(extension: String?): String = when (extension) {
    "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "c", "cpp", "h" -> "◇"
    "json", "yaml", "yml", "toml" -> "◆"
    "md", "txt" -> "·"
    "png", "jpg", "jpeg", "webp", "gif" -> "▧"
    else -> "·"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun lineOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var offset = 0
    var current = 1
    while (current < line && offset < text.length) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return text.length
        offset = next + 1
        current++
    }
    return offset.coerceIn(0, text.length)
}

private fun currentLine(text: String, offset: Int): Int = text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1

private fun ansiText(input: String): AnnotatedString {
    val regex = Regex("\\u001B\\[([0-9;]*)m")
    val builder = AnnotatedString.Builder()
    var index = 0
    var foreground: Color? = null
    var bold = false

    regex.findAll(input).forEach { match ->
        if (match.range.first > index) {
            val start = builder.length
            builder.append(input.substring(index, match.range.first))
            val end = builder.length
            if (foreground != null || bold) builder.addStyle(SpanStyle(color = foreground ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), start, end)
        }
        val codes = match.groupValues[1].split(';').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
        codes.forEach { code ->
            when (code) {
                0 -> { foreground = null; bold = false }
                1 -> bold = true
                30 -> foreground = Color(0xFF111111)
                31 -> foreground = Color(0xFFFF6B6B)
                32 -> foreground = Color(0xFF8BD49C)
                33 -> foreground = Color(0xFFFFD479)
                34 -> foreground = Color(0xFF82AAFF)
                35 -> foreground = Color(0xFFC792EA)
                36 -> foreground = Color(0xFF89DDFF)
                37 -> foreground = Color(0xFFE8E8EA)
                90 -> foreground = Color(0xFF8C8C92)
                91 -> foreground = Color(0xFFFF8A8A)
                92 -> foreground = Color(0xFFA7E8B4)
                93 -> foreground = Color(0xFFFFDF91)
                94 -> foreground = Color(0xFFA7C4FF)
                95 -> foreground = Color(0xFFD7A8FF)
                96 -> foreground = Color(0xFFA8E9FF)
                97 -> foreground = Color.White
            }
        }
        index = match.range.last + 1
    }
    if (index < input.length) {
        val start = builder.length
        builder.append(input.substring(index))
        val end = builder.length
        if (foreground != null || bold) builder.addStyle(SpanStyle(color = foreground ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), start, end)
    }
    return builder.toAnnotatedString()
}