package dev.gravitycode.app.core.runtime

import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.PermissionMode
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AgentRuntime {
    fun status(): RuntimeStatus

    fun run(
        prompt: String,
        workspace: File,
        permissionMode: PermissionMode,
    ): Flow<AgentEvent>

    fun cancel()
}

data class RuntimeStatus(
    val available: Boolean,
    val executable: String?,
    val message: String,
)
