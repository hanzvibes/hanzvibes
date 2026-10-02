package dev.gravitycode.app.core.persistence

import android.content.Context
import dev.gravitycode.app.core.model.AgentEvent
import dev.gravitycode.app.core.model.ToolState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SessionStore(context: Context) {
    private val root = File(context.filesDir, "agent-sessions").apply { mkdirs() }
    private val deletedOnce = mutableSetOf<String>()

    fun list(projectKey: String): List<SessionSummary> = sessionDir(projectKey)
        .listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension == "json" }
        ?.mapNotNull { read(it)?.summary }
        ?.sortedByDescending { it.updatedAt }
        ?.toList()
        .orEmpty()

    fun load(projectKey: String, id: String): SavedSession? = read(File(sessionDir(projectKey), "$id.json"))

    fun newest(projectKey: String): SavedSession? = list(projectKey).firstOrNull()?.let { load(projectKey, it.id) }

    fun create(projectKey: String, title: String = "New session"): SavedSession {
        val now = System.currentTimeMillis()
        return SavedSession(
            summary = SessionSummary(UUID.randomUUID().toString(), projectKey, title, null, now, now),
            events = emptyList(),
        ).also(::save)
    }

    fun save(session: SavedSession) {
        val key = tombstoneKey(session.summary.projectKey, session.summary.id)
        if (deletedOnce.remove(key)) return
        val dir = sessionDir(session.summary.projectKey)
        val summary = session.summary.copy(updatedAt = System.currentTimeMillis())
        val json = JSONObject().apply {
            put("id", summary.id)
            put("projectKey", summary.projectKey)
            put("title", summary.title)
            put("conversationId", summary.conversationId ?: JSONObject.NULL)
            put("createdAt", summary.createdAt)
            put("updatedAt", summary.updatedAt)
            put("events", JSONArray().apply {
                session.events.takeLast(240).forEach { event -> eventToJson(event)?.let(::put) }
            })
        }
        val target = File(dir, "${summary.id}.json")
        val temp = File(dir, "${summary.id}.json.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(json.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    fun rename(projectKey: String, id: String, title: String) {
        val session = load(projectKey, id) ?: return
        save(session.copy(summary = session.summary.copy(title = title.trim().ifBlank { "Untitled session" })))
    }

    fun delete(projectKey: String, id: String) {
        deletedOnce += tombstoneKey(projectKey, id)
        File(sessionDir(projectKey), "$id.json").delete()
        File(sessionDir(projectKey), "$id.json.tmp").delete()
    }

    private fun sessionDir(projectKey: String): File = File(root, sanitize(projectKey)).apply { mkdirs() }
    private fun tombstoneKey(projectKey: String, id: String): String = "${sanitize(projectKey)}/$id"

    private fun read(file: File): SavedSession? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val summary = SessionSummary(
            id = json.getString("id"),
            projectKey = json.optString("projectKey"),
            title = json.optString("title", "Session"),
            conversationId = json.optString("conversationId").takeIf { it.isNotBlank() && it != "null" },
            createdAt = json.optLong("createdAt", file.lastModified()),
            updatedAt = json.optLong("updatedAt", file.lastModified()),
        )
        val eventsArray = json.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until eventsArray.length()) jsonToEvent(eventsArray.optJSONObject(i))?.let(::add)
        }
        SavedSession(summary, events)
    }.getOrNull()

    private fun eventToJson(event: AgentEvent): JSONObject? = when (event) {
        is AgentEvent.Prompt -> JSONObject().put("type", "prompt").put("text", event.text).put("ts", event.timestamp)
        is AgentEvent.Output -> JSONObject().put("type", "output").put("text", event.text).put("ts", event.timestamp)
        is AgentEvent.Status -> JSONObject().put("type", "status").put("text", event.text).put("ts", event.timestamp)
        is AgentEvent.Tool -> JSONObject().put("type", "tool").put("name", event.name).put("detail", event.detail).put("state", event.state.name).put("ts", event.timestamp)
        is AgentEvent.Error -> JSONObject().put("type", "error").put("text", event.message).put("ts", event.timestamp)
        is AgentEvent.Conversation -> null
    }

    private fun jsonToEvent(json: JSONObject?): AgentEvent? {
        json ?: return null
        val ts = json.optLong("ts", System.currentTimeMillis())
        return when (json.optString("type")) {
            "prompt" -> AgentEvent.Prompt(json.optString("text"), ts)
            "output" -> AgentEvent.Output(json.optString("text"), ts)
            "status" -> AgentEvent.Status(json.optString("text"), ts)
            "tool" -> AgentEvent.Tool(
                json.optString("name", "tool"),
                json.optString("detail"),
                runCatching { ToolState.valueOf(json.optString("state")) }.getOrDefault(ToolState.SUCCESS),
                ts,
            )
            "error" -> AgentEvent.Error(json.optString("text"), ts)
            else -> null
        }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "workspace" }
}

data class SessionSummary(
    val id: String,
    val projectKey: String,
    val title: String,
    val conversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SavedSession(
    val summary: SessionSummary,
    val events: List<AgentEvent>,
)
