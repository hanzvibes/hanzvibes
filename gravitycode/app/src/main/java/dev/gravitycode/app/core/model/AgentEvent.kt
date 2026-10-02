package dev.gravitycode.app.core.model

sealed interface AgentEvent {
    val timestamp: Long

    data class Status(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class Output(
        val text: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class Tool(
        val name: String,
        val detail: String,
        val state: ToolState,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent

    data class Error(
        val message: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : AgentEvent
}

enum class ToolState { RUNNING, SUCCESS, FAILED }

enum class PermissionMode(val title: String) {
    PLAN_ONLY("Plan only"),
    ACCEPT_EDITS("Accept edits"),
    FULL_ACCESS("Full access"),
}
