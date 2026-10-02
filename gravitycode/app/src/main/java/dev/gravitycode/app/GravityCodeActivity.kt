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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import dev.gravitycode.app.core.persistence.CrashRecovery
import dev.gravitycode.app.core.runtime.AuthState
import dev.gravitycode.app.core.workspace.FilePreview
import dev.gravitycode.app.core.workspace.GitChange
import dev.gravitycode.app.core.workspace.GitDiff
import dev.gravitycode.app.core.workspace.PreviewKind
import dev.gravitycode.app.core.workspace.WorkspaceEntry
import dev.gravitycode.app.feature.agent.AgentUiState
import dev.gravitycode.app.feature.agent.AgentViewModel
import dev.gravitycode.app.feature.agent.TerminalTabState
import dev.gravitycode.app.ui.theme.GravityTheme
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GravityCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashRecovery(this).install()
        enableEdgeToEdge()
        setContent {
            GravityTheme {
                val vm: AgentViewModel = viewModel()
                val state by vm.state.collectAsState()
                GravityCodeV1(state, vm)
            }
        }
    }
}

private enum class V1Tab(val label: String) {
    AGENT("Agent"), FILES("Files"), SEARCH("Search"), TERMINAL("Terminal"), GIT("Git"), PROJECTS("Projects"), STATUS("Status")
}

@Composable
private fun GravityCodeV1(state: AgentUiState, vm: AgentViewModel) {
    var tabName by rememberSaveable { mutableStateOf(if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) V1Tab.AGENT.name else V1Tab.STATUS.name) }
    val tab = V1Tab.valueOf(tabName)

    LaunchedEffect(tab, state.runtimeStatus.available, state.repositoryStatus.workspacePath, state.activeTerminalId) {
        if (tab == V1Tab.TERMINAL && state.runtimeStatus.available) vm.startTerminalSession()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { V1Header(state) },
        bottomBar = {
            Column {
                V1StatusBar(state)
                if (tab == V1Tab.AGENT) V1Composer(state, vm)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V1Tab.entries.forEach { candidate ->
                    FilterChip(selected = candidate == tab, onClick = { tabName = candidate.name }, label = { Text(candidate.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                V1Tab.AGENT -> V1AgentPane(state, vm, Modifier.weight(1f))
                V1Tab.FILES -> V1FilesPane(state, vm, Modifier.weight(1f))
                V1Tab.SEARCH -> V1SearchPane(state, vm, Modifier.weight(1f))
                V1Tab.TERMINAL -> V1TerminalPane(state, vm, Modifier.weight(1f))
                V1Tab.GIT -> V1GitPane(state, vm, Modifier.weight(1f))
                V1Tab.PROJECTS -> V1ProjectsPane(state, vm, Modifier.weight(1f))
                V1Tab.STATUS -> V1StatusPane(state, vm, Modifier.weight(1f))
            }
        }
    }

    state.selectedFile?.let { preview ->
        V1EditorDialog(
            preview = preview,
            draft = state.fileDraft,
            saving = state.fileSaving,
            targetLine = state.selectedTargetLine,
            onDraftChange = vm::setFileDraft,
            onSave = vm::saveSelectedFile,
            onAttach = { vm.attachFile(preview.path) },
            onRename = vm::renamePath,
            onDelete = vm::deletePath,
            onClose = vm::closeFilePreview,
        )
    }
    if (state.selectedDiff != null || state.diffLoading || state.diffError != null) {
        V1DiffDialog(state.selectedDiff, state.diffLoading, state.diffError, state.diffReverting, vm)
    }
}

@Composable
private fun V1Header(state: AgentUiState) {
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GravityCode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append(state.repositoryStatus.projectName)
                        state.repositoryStatus.branch?.let { append(" · ").append(it) }
                        append(" · 1.0")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun V1AgentPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.activeSessionTitle, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.conversationId?.let { "conversation ${it.take(8)}…" } ?: "new conversation",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::newSession) { Text("New") }
            if (state.events.isNotEmpty()) TextButton(onClick = vm::clearEvents) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PermissionMode.entries.forEach { mode -> FilterChip(selected = state.permissionMode == mode, onClick = { vm.setPermissionMode(mode) }, label = { Text(mode.title) }) }
        }
        if (state.modelOptions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = state.selectedModel == null, onClick = { vm.setSelectedModel(null) }, label = { Text("Default model") })
                state.modelOptions.forEach { model -> FilterChip(selected = state.selectedModel == model, onClick = { vm.setSelectedModel(model) }, label = { Text(model) }) }
            }
        }
        Spacer(Modifier.height(6.dp))
        V1Timeline(state, Modifier.weight(1f))
    }
}

@Composable
private fun V1Timeline(state: AgentUiState, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.events.size, state.events.lastOrNull()?.let { if (it is AgentEvent.Output) it.text.length else 0 }) {
        if (state.events.isNotEmpty()) list.animateScrollToItem(state.events.lastIndex)
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.events.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ready for a task", fontWeight = FontWeight.SemiBold)
                Text("Attach files, diff, or terminal output, then ask the agent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.events) { event -> V1EventCard(event) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun V1EventCard(event: AgentEvent) {
    when (event) {
        is AgentEvent.Prompt -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(Modifier.widthIn(max = 340.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(event.text, Modifier.padding(12.dp))
            }
        }
        is AgentEvent.Output -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Text(event.text.trimEnd(), Modifier.fillMaxWidth().padding(12.dp))
        }
        is AgentEvent.Status -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
            Text(event.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is AgentEvent.Tool -> V1ToolCard(event)
        is AgentEvent.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Error", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        is AgentEvent.Conversation -> Unit
    }
}

@Composable
private fun V1ToolCard(event: AgentEvent.Tool) {
    var expanded by rememberSaveable(event.timestamp, event.name) { mutableStateOf(event.state == ToolState.FAILED) }
    val detail = event.detail.isNotBlank() && event.detail !in setOf("Completed", "Running", "Agent started", "Turn completed")
    Card(Modifier.fillMaxWidth().clickable(enabled = detail) { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.state == ToolState.RUNNING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (event.state == ToolState.SUCCESS) "✓" else "!", color = if (event.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(v1ToolTitle(event.name), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(event.state.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail) Text(if (expanded) "▴" else "▾")
            }
            AnimatedVisibility(expanded && detail) {
                Text(event.detail, Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(9.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun V1Composer(state: AgentUiState, vm: AgentViewModel) {
    val ready = state.runtimeStatus.available && state.authState is AuthState.SignedIn
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) {
            if (state.contextItems.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.contextItems.forEach { item -> FilterChip(selected = true, onClick = { vm.removeContext(item.id) }, label = { Text("${item.type.name.lowercase()}: ${item.label} ×") }) }
                }
                Spacer(Modifier.height(5.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (state.prompt.isBlank()) Text(if (ready) "Ask GravityCode…" else "Finish setup in Status", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    BasicTextField(value = state.prompt, onValueChange = vm::setPrompt, modifier = Modifier.fillMaxWidth(), maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                }
                if (state.running) TextButton(onClick = vm::cancel) { Text("Stop") }
                Button(onClick = vm::runAgent, enabled = ready && state.prompt.isNotBlank() && !state.running) {
                    if (state.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run")
                }
            }
        }
    }
}

@Composable
private fun V1FilesPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var newPath by rememberSaveable { mutableStateOf("") }
    var expanded by remember(state.repositoryStatus.projectName, state.workspaceFiles.size) { mutableStateOf(state.workspaceFiles.filter { it.directory && it.depth <= 1 }.map { it.relativePath }.toSet()) }
    val visible = remember(state.workspaceFiles, expanded, query) {
        if (query.isNotBlank()) state.workspaceFiles.filter { it.relativePath.contains(query, true) }
        else state.workspaceFiles.filter { entry -> v1Ancestors(entry.relativePath).all { it in expanded } }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.repositoryStatus.projectName, fontWeight = FontWeight.SemiBold)
                Text("${state.workspaceFiles.count { !it.directory }} files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = vm::refreshRuntime) { Text("Refresh") }
        }
        V1Input(query, { query = it }, "Filter files…")
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) { V1Input(newPath, { newPath = it }, "new/path.ext or folder") }
            OutlinedButton(onClick = { if (newPath.isNotBlank()) { vm.createPath(newPath, false); newPath = "" } }) { Text("File") }
            OutlinedButton(onClick = { if (newPath.isNotBlank()) { vm.createPath(newPath, true); newPath = "" } }) { Text("Folder") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(visible) { entry -> V1FileRow(entry, entry.relativePath in expanded, onToggle = { expanded = if (entry.relativePath in expanded) expanded - entry.relativePath else expanded + entry.relativePath }, onOpen = { vm.selectFile(entry.relativePath) }, onAttach = { vm.attachFile(entry.relativePath) }) }
        }
    }
}

@Composable
private fun V1FileRow(entry: WorkspaceEntry, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onAttach: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { if (entry.directory) onToggle() else onOpen() }.padding(start = (entry.depth.coerceAtMost(7) * 14).dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (entry.directory) if (expanded) "▾" else "▸" else v1FileGlyph(entry.extension), color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Text(entry.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!entry.directory) TextButton(onClick = onAttach) { Text("+") }
    }
}

@Composable
private fun V1SearchPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Global search", fontWeight = FontWeight.SemiBold)
        Text("Search file names and text content in the active repository.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.weight(1f)) { V1Input(state.searchQuery, vm::setSearchQuery, "Search repository…") }
            Button(onClick = vm::searchWorkspace, enabled = state.searchQuery.trim().length >= 2 && !state.searching) {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Search")
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!state.searching && state.searchResults.isEmpty() && state.searchQuery.isNotBlank()) item { Text("No results yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp)) }
            items(state.searchResults) { hit ->
                Card(Modifier.fillMaxWidth().clickable { vm.selectFile(hit.path, hit.line) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(if (hit.line > 0) "${hit.path}:${hit.line}" else hit.path, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun V1TerminalPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val active = state.terminalTabs.firstOrNull { it.id == state.activeTerminalId } ?: return
    val scroll = rememberScrollState()
    LaunchedEffect(active.output.length) { if (active.output.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            state.terminalTabs.forEach { tab -> FilterChip(selected = tab.id == state.activeTerminalId, onClick = { vm.selectTerminal(tab.id) }, label = { Text(tab.title + if (tab.running) " ●" else "") }) }
            OutlinedButton(onClick = vm::newTerminal, enabled = state.terminalTabs.size < 4) { Text("+") }
            TextButton(onClick = { vm.closeTerminal(active.id) }) { Text("Close") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = vm::terminalInterrupt, enabled = active.running) { Text("Ctrl+C") }
            OutlinedButton(onClick = vm::terminalTab, enabled = active.running) { Text("Tab") }
            OutlinedButton(onClick = vm::terminalHistoryPrevious) { Text("↑") }
            OutlinedButton(onClick = vm::terminalHistoryNext) { Text("↓") }
            OutlinedButton(onClick = { vm.restartTerminalSession() }, enabled = state.runtimeStatus.available) { Text("Restart") }
            OutlinedButton(onClick = vm::attachTerminal, enabled = active.output.isNotBlank()) { Text("Attach") }
            TextButton(onClick = vm::clearTerminal) { Text("Clear") }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0B0D), RoundedCornerShape(12.dp)).padding(10.dp)) {
            Text(v1AnsiText(active.output.ifBlank { "Starting shell…" }), Modifier.fillMaxSize().verticalScroll(scroll), color = Color(0xFFE8E8EA), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                V1Input(active.command, vm::setTerminalCommand, "type command…", imeAction = ImeAction.Send, onIme = vm::submitTerminalCommand)
            }
            Button(onClick = vm::submitTerminalCommand, enabled = active.command.isNotBlank() && active.running) { Text("Enter") }
        }
    }
}

@Composable
private fun V1GitPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val repo = state.repositoryStatus
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Source control", fontWeight = FontWeight.SemiBold)
                    Text(repo.branch ?: "not a git repository", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.gitBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = vm::refreshRuntime) { Text("Refresh") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    V1StatusLine("HEAD", repo.head ?: "—")
                    V1StatusLine("Latest", repo.latestCommit ?: "—")
                    V1StatusLine("Sync", if (repo.ahead == 0 && repo.behind == 0) "up to date" else "↑${repo.ahead} ↓${repo.behind}")
                    V1StatusLine("Changes", repo.changedFiles.toString())
                    state.remoteUrl?.let { V1StatusLine("Remote", it) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = vm::gitStageAll, enabled = !state.gitBusy && repo.changedFiles > 0) { Text("Stage all") }
                OutlinedButton(onClick = vm::gitUnstageAll, enabled = !state.gitBusy) { Text("Unstage all") }
                OutlinedButton(onClick = vm::gitFetch, enabled = !state.gitBusy) { Text("Fetch") }
                OutlinedButton(onClick = vm::gitPull, enabled = !state.gitBusy) { Text("Pull") }
                OutlinedButton(onClick = vm::gitPush, enabled = !state.gitBusy) { Text("Push") }
            }
        }
        if (state.gitNotice.isNotBlank()) item { Text(state.gitNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.weight(1f)) { V1Input(state.commitMessage, vm::setCommitMessage, "Commit message") }
                OutlinedButton(onClick = vm::suggestCommitMessage, enabled = !state.gitBusy && repo.changedFiles > 0) { Text("Suggest") }
                Button(onClick = vm::gitCommit, enabled = state.commitMessage.isNotBlank() && !state.gitBusy) { Text("Commit") }
            }
        }
        item {
            Text("Branches", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.gitBranches.forEach { branch -> FilterChip(selected = branch == repo.branch, onClick = { if (branch != repo.branch) vm.gitSwitchBranch(branch) }, label = { Text(branch) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.weight(1f)) { V1Input(state.newBranch, vm::setNewBranch, "new-branch") }
                OutlinedButton(onClick = vm::gitCreateBranch, enabled = state.newBranch.isNotBlank() && !state.gitBusy) { Text("Create") }
            }
        }
        item { Text("Changes", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
        if (repo.changedPaths.isEmpty()) item { Text("Working tree clean", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        items(repo.changedPaths) { change -> V1GitChangeRow(change, vm) }
        item { Text("Recent commits", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
        items(state.gitHistory) { commit ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(commit.hash, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Column(Modifier.weight(1f)) {
                    Text(commit.subject, style = MaterialTheme.typography.bodySmall)
                    Text(commit.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun V1GitChangeRow(change: GitChange, vm: AgentViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(change.status, Modifier.width(28.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text(change.path, Modifier.weight(1f).clickable { vm.selectDiff(change.path, change.status) }, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (change.staged) TextButton(onClick = { vm.gitUnstage(change.path) }) { Text("Unstage") }
            else TextButton(onClick = { vm.gitStage(change.path) }) { Text("Stage") }
            TextButton(onClick = { vm.selectDiff(change.path, change.status) }) { Text("Diff") }
        }
    }
}

@Composable
private fun V1ProjectsPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    var rename by remember(state.activeSessionId, state.activeSessionTitle) { mutableStateOf(state.activeSessionTitle) }
    var deleteProject by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        Text("Projects", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.weight(1f)) { V1Input(state.cloneUrl, vm::setCloneUrl, "https://github.com/owner/repo.git") }
            Button(onClick = vm::cloneRepository, enabled = state.cloneUrl.isNotBlank() && !state.cloning) {
                if (state.cloning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Clone")
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(state.projects) { project ->
                Card(colors = CardDefaults.cardColors(containerColor = if (project.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(project.name, fontWeight = FontWeight.SemiBold)
                            Text(project.branch ?: "detached", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!project.active) TextButton(onClick = { vm.switchProject(project.name) }) { Text("Open") }
                        if (!project.active) TextButton(onClick = { deleteProject = project.name }) { Text("Delete") }
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent sessions", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = vm::newSession) { Text("New") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.weight(1f)) { V1Input(rename, { rename = it }, "Session title") }
                    OutlinedButton(onClick = { state.activeSessionId?.let { vm.renameSession(it, rename) } }, enabled = state.activeSessionId != null && rename.isNotBlank()) { Text("Rename") }
                }
            }
            items(state.sessions) { session ->
                Card(colors = CardDefaults.cardColors(containerColor = if (session.id == state.activeSessionId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(v1Time(session.updatedAt) + (session.conversationId?.let { " · ${it.take(8)}…" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (session.id != state.activeSessionId) TextButton(onClick = { vm.openSession(session.id) }) { Text("Open") }
                        TextButton(onClick = { vm.deleteSession(session.id) }) { Text("Delete") }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    deleteProject?.let { name ->
        AlertDialog(onDismissRequest = { deleteProject = null }, title = { Text("Delete $name?") }, text = { Text("The local project folder and its uncommitted files will be removed from GravityCode.") }, confirmButton = { TextButton(onClick = { vm.deleteProject(name); deleteProject = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteProject = null }) { Text("Cancel") } })
    }
}

@Composable
private fun V1StatusPane(state: AgentUiState, vm: AgentViewModel, modifier: Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runtime & account", fontWeight = FontWeight.SemiBold)
                Text(state.runtimeStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.installing) {
                    LinearProgressIndicator(progress = { state.installProgress }, modifier = Modifier.fillMaxWidth())
                    Text(state.installMessage, style = MaterialTheme.typography.bodySmall)
                } else if (!state.runtimeStatus.available) {
                    Button(onClick = vm::setupRuntime, modifier = Modifier.fillMaxWidth()) { Text("Setup runtime") }
                } else when (val auth = state.authState) {
                    AuthState.SignedOut -> Button(onClick = vm::beginAuth, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                    AuthState.Starting -> V1Busy("Preparing sign-in…")
                    is AuthState.AwaitingCode -> {
                        Button(onClick = { uri.openUri(auth.url) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                        V1Input(state.authCode, vm::setAuthCode, "Paste authorization code")
                        Button(onClick = vm::submitAuthCode, enabled = state.authCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit code") }
                    }
                    AuthState.Verifying -> V1Busy("Verifying account…")
                    AuthState.SignedIn -> V1StatusLine("Account", "Google connected")
                    is AuthState.Error -> {
                        Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = vm::beginAuth, modifier = Modifier.fillMaxWidth()) { Text("Retry sign in") }
                    }
                }
                V1StatusLine("Project", state.repositoryStatus.projectName)
                V1StatusLine("Path", state.repositoryStatus.workspacePath)
                V1StatusLine("Permission", state.permissionMode.title)
                V1StatusLine("Model", state.selectedModel ?: "Antigravity default")
                V1StatusLine("Agent", if (state.running) "running in foreground service" else "idle")
            }
        }
        state.lastCrash?.let { crash ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recovered crash report", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = vm::clearCrash) { Text("Clear") }
                    }
                    Text(crash.take(5000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Production safeguards", fontWeight = FontWeight.SemiBold)
                Text("Atomic text saves · path traversal guard · .git protection · large-file read-only · session recovery · foreground agent execution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun V1DiffDialog(diff: GitDiff?, loading: Boolean, error: String?, reverting: Boolean, vm: AgentViewModel) {
    Dialog(onDismissRequest = vm::closeDiff, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), tonalElevation = 5.dp) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(diff?.path ?: "Diff", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        diff?.let { Text("+${it.additions}  -${it.deletions} · ${it.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = vm::closeDiff) { Text("Close") }
                }
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(error, color = MaterialTheme.colorScheme.error) }
                    diff != null -> {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0D0D0F), RoundedCornerShape(10.dp)).padding(vertical = 6.dp)) {
                            items(diff.patch.lines()) { line -> V1DiffLine(line) }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = vm::attachSelectedDiff) { Text("Attach diff") }
                            OutlinedButton(onClick = { vm.closeDiff(); vm.selectFile(diff.path) }) { Text("Open file") }
                            Button(onClick = vm::revertSelectedDiff, enabled = !reverting) { if (reverting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Revert") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V1DiffLine(line: String) {
    val bg = when {
        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF102617)
        line.startsWith("-") && !line.startsWith("---") -> Color(0xFF2B1518)
        line.startsWith("@@") -> Color(0xFF211B31)
        else -> Color.Transparent
    }
    val fg = when {
        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF8BD49C)
        line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF9A9A)
        line.startsWith("@@") -> Color(0xFFC9B5FF)
        else -> Color(0xFFE5E5E8)
    }
    Text(line.ifEmpty { " " }, Modifier.fillMaxWidth().background(bg).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 1.dp), color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun V1EditorDialog(
    preview: FilePreview,
    draft: String,
    saving: Boolean,
    targetLine: Int,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onAttach: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var renameTo by remember(preview.path) { mutableStateOf(preview.path) }
    var manageOpen by remember(preview.path) { mutableStateOf(false) }
    var confirmDelete by remember(preview.path) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), tonalElevation = 5.dp) {
            Box(Modifier.fillMaxSize()) {
                when (preview.kind) {
                    PreviewKind.IMAGE -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                        V1EditorHeader(preview, false, onAttach, onClose)
                        val bitmap = remember(preview.path, preview.imageBytes?.size) { preview.imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), preview.path, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Text("Image could not be decoded", color = Color.White)
                        }
                    }
                    PreviewKind.BINARY -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                        V1EditorHeader(preview, false, onAttach, onClose)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Binary preview is not available.") }
                    }
                    PreviewKind.TEXT -> V1CodeEditor(preview, draft, saving, targetLine, onDraftChange, onSave, onAttach, onClose)
                }
                OutlinedButton(
                    onClick = { manageOpen = true },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                ) { Text("Manage") }
            }
        }
    }
    if (manageOpen) {
        AlertDialog(
            onDismissRequest = { manageOpen = false },
            title = { Text("Manage file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    V1Input(renameTo, { renameTo = it }, "new/path.ext")
                    Text("Rename keeps the file inside the active repository. Delete removes it from the local workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            val next = renameTo.trim()
                            if (next.isNotBlank() && next != preview.path) onRename(preview.path, next)
                            manageOpen = false
                        },
                        enabled = renameTo.trim().isNotBlank() && renameTo.trim() != preview.path,
                    ) { Text("Rename") }
                    TextButton(onClick = { manageOpen = false; confirmDelete = true }) { Text("Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { manageOpen = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${preview.path}?") },
            text = { Text("This removes the local file or folder. Git can still restore tracked files from the Git tab.") },
            confirmButton = { TextButton(onClick = { onDelete(preview.path); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun V1CodeEditor(preview: FilePreview, draft: String, saving: Boolean, targetLine: Int, onDraftChange: (String) -> Unit, onSave: () -> Unit, onAttach: () -> Unit, onClose: () -> Unit) {
    val initialOffset = remember(preview.path, targetLine) { v1LineOffset(draft, targetLine.coerceAtLeast(1)) }
    var editor by remember(preview.path) { mutableStateOf(TextFieldValue(draft, selection = TextRange(initialOffset))) }
    var undo by remember(preview.path) { mutableStateOf(emptyList<String>()) }
    var redo by remember(preview.path) { mutableStateOf(emptyList<String>()) }
    var findOpen by rememberSaveable(preview.path) { mutableStateOf(false) }
    var find by rememberSaveable(preview.path) { mutableStateOf("") }
    var replace by rememberSaveable(preview.path) { mutableStateOf("") }
    var goLine by rememberSaveable(preview.path) { mutableStateOf(if (targetLine > 0) targetLine.toString() else "") }
    var previewMode by rememberSaveable(preview.path) { mutableStateOf(false) }
    val ext = preview.path.substringAfterLast('.', "").lowercase()
    val readOnly = preview.truncated
    val modified = draft != preview.content

    LaunchedEffect(draft) { if (editor.text != draft) editor = editor.copy(text = draft, selection = TextRange(editor.selection.start.coerceAtMost(draft.length))) }
    fun apply(next: TextFieldValue) {
        if (readOnly) return
        if (next.text != editor.text) { undo = (undo + editor.text).takeLast(100); redo = emptyList() }
        editor = next; onDraftChange(next.text)
    }
    fun undoNow() { val previous = undo.lastOrNull() ?: return; redo = (redo + editor.text).takeLast(100); undo = undo.dropLast(1); editor = TextFieldValue(previous, TextRange(previous.length.coerceAtMost(editor.selection.start))); onDraftChange(previous) }
    fun redoNow() { val next = redo.lastOrNull() ?: return; undo = (undo + editor.text).takeLast(100); redo = redo.dropLast(1); editor = TextFieldValue(next, TextRange(next.length.coerceAtMost(editor.selection.start))); onDraftChange(next) }
    fun findNext() { if (find.isBlank()) return; var index = editor.text.indexOf(find, editor.selection.end.coerceAtMost(editor.text.length), true); if (index < 0) index = editor.text.indexOf(find, 0, true); if (index >= 0) editor = editor.copy(selection = TextRange(index, index + find.length)) }
    fun replaceOne() { if (readOnly || find.isBlank()) return; val selected = editor.text.substring(editor.selection.min, editor.selection.max); if (selected.equals(find, true)) { val text = editor.text.replaceRange(editor.selection.min, editor.selection.max, replace); apply(TextFieldValue(text, TextRange(editor.selection.min + replace.length))) } else findNext() }
    fun replaceAll() { if (!readOnly && find.isNotBlank()) { val text = editor.text.replace(find, replace, true); if (text != editor.text) apply(TextFieldValue(text)) } }
    fun jump() { val line = goLine.toIntOrNull() ?: return; editor = editor.copy(selection = TextRange(v1LineOffset(editor.text, line))) }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        V1EditorHeader(preview, modified, onAttach, onClose)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { findOpen = !findOpen }) { Text("Find") }
            TextButton(onClick = ::undoNow, enabled = undo.isNotEmpty() && !readOnly) { Text("Undo") }
            TextButton(onClick = ::redoNow, enabled = redo.isNotEmpty() && !readOnly) { Text("Redo") }
            if (ext in setOf("md", "markdown", "json", "yaml", "yml")) FilterChip(selected = previewMode, onClick = { previewMode = !previewMode }, label = { Text("Preview") })
            V1MiniInput(goLine, { goLine = it.filter(Char::isDigit).take(6) }, "Line")
            OutlinedButton(onClick = ::jump) { Text("Go") }
            Text("Ln ${v1CurrentLine(editor.text, editor.selection.start)} · ${editor.text.length} chars", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(findOpen) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                V1Input(find, { find = it }, "Find text")
                V1Input(replace, { replace = it }, "Replace with")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = ::findNext) { Text("Next") }
                    OutlinedButton(onClick = ::replaceOne, enabled = !readOnly) { Text("Replace") }
                    OutlinedButton(onClick = ::replaceAll, enabled = !readOnly) { Text("All") }
                }
            }
        }
        if (readOnly) Text("Large file preview is truncated. Editing disabled to prevent data loss.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (previewMode && ext in setOf("md", "markdown")) V1MarkdownPreview(editor.text, Modifier.weight(1f).fillMaxWidth())
        else if (previewMode && ext == "json") V1JsonPreview(editor.text, Modifier.weight(1f).fillMaxWidth())
        else if (previewMode && ext in setOf("yaml", "yml")) V1YamlPreview(editor.text, Modifier.weight(1f).fillMaxWidth())
        else V1EditorArea(editor, ext, readOnly, { next -> if (next.text == editor.text) editor = next else apply(next) }, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (modified) Text("● modified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = modified && !saving && !readOnly) { if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save") }
        }
    }
}

@Composable
private fun V1EditorHeader(preview: FilePreview, modified: Boolean, onAttach: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (modified) "● " else "") + preview.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
            Text("${v1FormatBytes(preview.sizeBytes)} · ${preview.path}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAttach) { Text("Attach") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun V1EditorArea(value: TextFieldValue, extension: String, readOnly: Boolean, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier) {
    val vertical = rememberScrollState(); val horizontal = rememberScrollState(); val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val numbers = remember(lines) { (1..lines).joinToString("\n") }
    val transform = remember(extension) { V1Highlight(extension) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))) {
        Row(Modifier.horizontalScroll(horizontal).verticalScroll(vertical).padding(9.dp), verticalAlignment = Alignment.Top) {
            Text(numbers, Modifier.width(46.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp)
            Box(Modifier.widthIn(min = 900.dp).heightIn(min = 520.dp)) {
                BasicTextField(value = value, onValueChange = onValueChange, enabled = !readOnly, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, lineHeight = 19.sp), visualTransformation = transform)
            }
        }
    }
}

private class V1Highlight(private val ext: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text; val builder = AnnotatedString.Builder(source)
        val keywords = when (ext) {
            "kt", "kts" -> "class|fun|val|var|if|else|when|for|while|return|object|data|sealed|interface|private|public|internal|override|suspend|import|package|null|true|false"
            "js", "jsx", "ts", "tsx" -> "const|let|var|function|class|if|else|for|while|return|async|await|import|export|from|new|try|catch|throw|true|false|null|undefined|interface|type"
            "py" -> "def|class|if|elif|else|for|while|return|async|await|import|from|as|try|except|raise|True|False|None|with|lambda"
            "java", "c", "h", "cpp", "cc" -> "class|struct|enum|if|else|for|while|return|public|private|protected|static|const|void|int|long|double|float|bool|true|false|null|new|try|catch|throw|include"
            else -> "true|false|null"
        }
        Regex("\\b($keywords)\\b").findAll(source).forEach { builder.addStyle(SpanStyle(color = Color(0xFFB8A1FF), fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
        Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(source).forEach { builder.addStyle(SpanStyle(color = Color(0xFFFFC98B)), it.range.first, it.range.last + 1) }
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'").findAll(source).forEach { builder.addStyle(SpanStyle(color = Color(0xFFA8D8A8)), it.range.first, it.range.last + 1) }
        val comments = if (ext == "py") Regex("#.*", RegexOption.MULTILINE) else Regex("//.*|/\\*[\\s\\S]*?\\*/")
        comments.findAll(source).forEach { builder.addStyle(SpanStyle(color = Color(0xFF7E858F)), it.range.first, it.range.last + 1) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
private fun V1MarkdownPreview(text: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        text.lines().forEach { line ->
            when {
                line.startsWith("### ") -> Text(line.removePrefix("### "), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                line.startsWith("## ") -> Text(line.removePrefix("## "), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                line.startsWith("# ") -> Text(line.removePrefix("# "), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                line.startsWith("- ") || line.startsWith("* ") -> Text("• " + line.drop(2))
                line.startsWith("> ") -> Text(line.drop(2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(line.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun V1JsonPreview(text: String, modifier: Modifier) {
    val pretty = remember(text) { v1PrettyJson(text) }
    Text(pretty, modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun V1YamlPreview(text: String, modifier: Modifier) {
    val lines = remember(text) { text.lines() }
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line ->
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            val rendered = when {
                trimmed.startsWith("#") -> trimmed
                ':' in trimmed -> {
                    val key = trimmed.substringBefore(':')
                    val value = trimmed.substringAfter(':', "")
                    " ".repeat(indent) + key + ":" + value
                }
                else -> line
            }
            Text(
                rendered.ifBlank { " " },
                color = if (trimmed.startsWith("#")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun V1StatusBar(state: AgentUiState) {
    val activeTerminal = state.terminalTabs.firstOrNull { it.id == state.activeTerminalId }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(state.repositoryStatus.branch ?: state.repositoryStatus.projectName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            Text("· ${if (state.repositoryStatus.changedFiles == 0) "clean" else "${state.repositoryStatus.changedFiles} changes"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${state.selectedModel ?: "default model"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ctx ${state.contextItems.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${if (activeTerminal?.running == true) "PTY" else "terminal off"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${if (state.running) "agent running" else "idle"}", style = MaterialTheme.typography.labelSmall, color = if (state.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun V1Input(value: String, onValueChange: (String) -> Unit, placeholder: String, imeAction: ImeAction = ImeAction.Default, onIme: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(13.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = imeAction), keyboardActions = KeyboardActions(onSend = { onIme?.invoke() }), textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
private fun V1MiniInput(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(Modifier.width(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        BasicTextField(value = value, onValueChange = onValueChange, singleLine = true, textStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun V1Busy(text: String) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(text, style = MaterialTheme.typography.bodySmall) }

@Composable
private fun V1StatusLine(label: String, value: String) = Row(Modifier.fillMaxWidth()) { Text(label, Modifier.width(86.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = if (label in setOf("Path", "HEAD", "Remote")) FontFamily.Monospace else FontFamily.Default) }

private fun v1Ancestors(path: String): List<String> { val parts = path.split('/').dropLast(1); return parts.indices.map { parts.take(it + 1).joinToString("/") } }
private fun v1FileGlyph(ext: String?): String = when (ext) { "js", "ts", "kt", "java", "py", "c", "cpp", "h" -> "◇"; "json", "yaml", "yml", "toml" -> "◆"; "png", "jpg", "jpeg", "webp", "gif" -> "▧"; else -> "·" }
private fun v1FormatBytes(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0) }
private fun v1LineOffset(text: String, line: Int): Int { if (line <= 1) return 0; var current = 1; text.forEachIndexed { index, c -> if (c == '\n') { current++; if (current == line) return index + 1 } }; return text.length }
private fun v1CurrentLine(text: String, offset: Int): Int = 1 + text.take(offset.coerceIn(0, text.length)).count { it == '\n' }
private fun v1Time(timestamp: Long): String = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun v1ToolTitle(name: String): String = name.replace('_', ' ').replace('-', ' ').trim().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
private fun v1PrettyJson(text: String): String = runCatching { when (val parsed = JSONTokener(text).nextValue()) { is JSONObject -> parsed.toString(2); is JSONArray -> parsed.toString(2); else -> text } }.getOrDefault(text)

private fun v1AnsiText(raw: String): AnnotatedString {
    val pattern = Regex("\\u001B\\[([0-9;]*)m")
    val out = AnnotatedString.Builder(); var index = 0; var color: Color? = null
    pattern.findAll(raw).forEach { match ->
        if (match.range.first > index) { val start = out.length; out.append(raw.substring(index, match.range.first)); color?.let { out.addStyle(SpanStyle(color = it), start, out.length) } }
        val codes = match.groupValues[1].split(';').mapNotNull(String::toIntOrNull)
        if (codes.isEmpty() || 0 in codes || 39 in codes) color = null
        codes.forEach { code -> color = when (code) { 30 -> Color(0xFF55555B); 31 -> Color(0xFFFF7B7B); 32 -> Color(0xFF7BD88F); 33 -> Color(0xFFFFD866); 34 -> Color(0xFF78A9FF); 35 -> Color(0xFFD2A8FF); 36 -> Color(0xFF74D7EC); 37 -> Color(0xFFE5E5E8); 90 -> Color(0xFF8B8B91); else -> color } }
        index = match.range.last + 1
    }
    if (index < raw.length) { val start = out.length; out.append(raw.substring(index)); color?.let { out.addStyle(SpanStyle(color = it), start, out.length) } }
    return out.toAnnotatedString()
}
