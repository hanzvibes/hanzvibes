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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import dev.gravitycode.app.core.runtime.AuthState
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
                    onSetupRuntime = vm::setupRuntime,
                    onBeginAuth = vm::beginAuth,
                    onAuthCodeChange = vm::setAuthCode,
                    onSubmitAuthCode = vm::submitAuthCode,
                    onCloneUrlChange = vm::setCloneUrl,
                    onClone = vm::cloneRepository,
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
    onSetupRuntime: () -> Unit,
    onBeginAuth: () -> Unit,
    onAuthCodeChange: (String) -> Unit,
    onSubmitAuthCode: () -> Unit,
    onCloneUrlChange: (String) -> Unit,
    onClone: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GravityCode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text("Native agentic workspace · v0.2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RuntimeDot(state.runtimeStatus.available, state.authState is AuthState.SignedIn)
                }
            }
        },
        bottomBar = { Composer(state, onPromptChange, onRun, onCancel) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            RuntimeCard(state, onRefreshRuntime, onSetupRuntime, onBeginAuth, onAuthCodeChange, onSubmitAuthCode)
            if (state.runtimeStatus.available && state.authState is AuthState.SignedIn) {
                Spacer(Modifier.height(10.dp))
                CloneCard(state, onCloneUrlChange, onClone)
            }
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
private fun RuntimeDot(runtimeReady: Boolean, signedIn: Boolean) {
    val ready = runtimeReady && signedIn
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(9.dp).background(if (ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, RoundedCornerShape(50)))
        Text(if (ready) "agent ready" else if (runtimeReady) "login needed" else "setup needed", style = MaterialTheme.typography.labelMedium)
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
                    Text("Antigravity runtime", fontWeight = FontWeight.SemiBold)
                    Text(state.runtimeStatus.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }

            if (state.installing) {
                LinearProgressIndicator(progress = { state.installProgress }, modifier = Modifier.fillMaxWidth())
                Text(state.installMessage, style = MaterialTheme.typography.bodySmall)
            } else if (!state.runtimeStatus.available) {
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Setup runtime") }
                Text("Sekali setup. GravityCode akan memasang Linux runtime dan agy resmi di storage privat aplikasi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                when (val auth = state.authState) {
                    AuthState.SignedOut -> Button(onClick = onBeginAuth, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                    AuthState.Starting -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Menyiapkan Google sign-in…", style = MaterialTheme.typography.bodySmall)
                    }
                    is AuthState.AwaitingCode -> {
                        Button(onClick = { uriHandler.openUri(auth.url) }, modifier = Modifier.fillMaxWidth()) { Text("Open Google sign-in") }
                        Text("Setelah Google memberi authorization code, paste di bawah.", style = MaterialTheme.typography.bodySmall)
                        InputSurface(state.authCode, onAuthCodeChange, "Paste authorization code")
                        Button(onClick = onSubmitAuthCode, enabled = state.authCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit code") }
                    }
                    AuthState.Verifying -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Verifying Antigravity account…", style = MaterialTheme.typography.bodySmall)
                    }
                    AuthState.SignedIn -> Text("Google account connected. Agent siap dipakai.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                    is AuthState.Error -> {
                        Text(auth.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onBeginAuth, modifier = Modifier.fillMaxWidth()) { Text("Retry sign in") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloneCard(state: AgentUiState, onCloneUrlChange: (String) -> Unit, onClone: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open repository", fontWeight = FontWeight.SemiBold)
            InputSurface(state.cloneUrl, onCloneUrlChange, "https://github.com/owner/repo.git")
            Button(onClick = onClone, enabled = state.cloneUrl.isNotBlank() && !state.cloning, modifier = Modifier.fillMaxWidth()) {
                if (state.cloning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Clone into workspace")
            }
        }
    }
}

@Composable
private fun InputSurface(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true)
    }
}

@Composable
private fun PermissionRow(selected: PermissionMode, onSelected: (PermissionMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PermissionMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelected(mode) }, label = { Text(mode.title) })
        }
    }
}

@Composable
private fun WorkspaceStrip(state: AgentUiState) {
    val summary = state.workspaceFiles.take(5).joinToString("  ·  ") { if (it.directory) "${it.relativePath}/" else it.relativePath }.ifBlank { "Empty workspace" }
    Column {
        Text("Workspace", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventTimeline(state: AgentUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size) { if (state.events.isNotEmpty()) listState.animateScrollToItem(state.events.lastIndex) }
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.events.isEmpty()) item { EmptyState(state) }
        items(state.events) { event -> EventRow(event) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EmptyState(state: AgentUiState) {
    val hint = when {
        !state.runtimeStatus.available -> "Tap Setup runtime di atas untuk memasang engine lokal."
        state.authState !is AuthState.SignedIn -> "Hubungkan akun Google Antigravity, lalu agent siap jalan."
        else -> "Clone repository atau langsung beri task ke Antigravity."
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Agent session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(text, modifier = Modifier.weight(1f), fontFamily = if (event is AgentEvent.Output || event is AgentEvent.Tool) FontFamily.Monospace else FontFamily.Default, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Composer(state: AgentUiState, onPromptChange: (String) -> Unit, onRun: () -> Unit, onCancel: () -> Unit) {
    val ready = state.runtimeStatus.available && state.authState is AuthState.SignedIn
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            InputSurface(state.prompt, onPromptChange, if (ready) "Ask GravityCode to inspect, edit, build…" else "Finish runtime setup above first")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(state.running) { TextButton(onClick = onCancel) { Text("Stop") } }
                Button(onClick = onRun, enabled = ready && state.prompt.isNotBlank() && !state.running, colors = ButtonDefaults.buttonColors()) {
                    if (state.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Run agent")
                }
            }
        }
    }
}
