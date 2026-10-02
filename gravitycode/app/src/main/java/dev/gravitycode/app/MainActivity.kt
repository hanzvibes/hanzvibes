package dev.gravitycode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
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
                    onRefreshRuntime = vm::refreshRuntime,
                )
            }
        }
    }
}

@Composable
private fun GravityCodeScreen(
    state: AgentUiState,
    onPromptChange: (String) -> Unit,
    onPermissionChange: (PermissionMode) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onRefreshRuntime: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GravityCode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text("Native agentic workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RuntimeDot(available = state.runtimeStatus.available)
                }
            }
        },
        bottomBar = {
            Composer(
                state = state,
                onPromptChange = onPromptChange,
                onRun = onRun,
                onCancel = onCancel,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            RuntimeCard(state, onRefreshRuntime)
            Spacer(Modifier.height(10.dp))
            PermissionRow(state.permissionMode, onPermissionChange)
            Spacer(Modifier.height(10.dp))
            WorkspaceStrip(state)
            Spacer(Modifier.height(10.dp))
            EventTimeline(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RuntimeDot(available: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .size(9.dp)
                .background(
                    if (available) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    RoundedCornerShape(50),
                ),
        )
        Text(if (available) "agy ready" else "runtime missing", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RuntimeCard(state: AgentUiState, onRefreshRuntime: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Antigravity runtime", fontWeight = FontWeight.SemiBold)
                Text(
                    state.runtimeStatus.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefreshRuntime) { Text("Refresh") }
        }
    }
}

@Composable
private fun PermissionRow(selected: PermissionMode, onSelected: (PermissionMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PermissionMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = { Text(mode.title) },
            )
        }
    }
}

@Composable
private fun WorkspaceStrip(state: AgentUiState) {
    val summary = state.workspaceFiles.take(5).joinToString("  ·  ") {
        if (it.directory) "${it.relativePath}/" else it.relativePath
    }.ifBlank { "Empty workspace" }

    Column {
        Text("Workspace", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.events.isEmpty()) {
            item { EmptyState() }
        }
        items(state.events) { event -> EventRow(event) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Agent session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Prompt akan mengalir ke Antigravity CLI dan output tampil inline di sini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(event: AgentEvent) {
    val (prefix, text) = when (event) {
        is AgentEvent.Status -> "●" to event.text
        is AgentEvent.Output -> "" to event.text
        is AgentEvent.Tool -> "◆" to "${event.name}: ${event.detail} [${event.state.name.lowercase()}]"
        is AgentEvent.Error -> "!" to event.message
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(prefix, fontFamily = FontFamily.Monospace, color = if (event is AgentEvent.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontFamily = if (event is AgentEvent.Output || event is AgentEvent.Tool) FontFamily.Monospace else FontFamily.Default,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Composer(
    state: AgentUiState,
    onPromptChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (state.prompt.isBlank()) {
                    Text("Ask GravityCode to inspect, edit, build…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 5,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(state.running) {
                    TextButton(onClick = onCancel) { Text("Stop") }
                }
                Button(
                    onClick = onRun,
                    enabled = state.prompt.isNotBlank() && !state.running,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (state.running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Run agent")
                    }
                }
            }
        }
    }
}
