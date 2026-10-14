package com.lelloman.store.diagnostics

import android.app.Application
import com.lelloman.androidoscopy.Androidoscopy
import com.lelloman.androidoscopy.tools.Tool
import com.lelloman.androidoscopy.tools.ToolResult
import com.lelloman.store.logger.AuditLog
import com.lelloman.store.notification.NotificationBrokerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Androidoscopy tools over the local audit log and push broker state, so the logs
 * can be read from a paired desktop or MCP client instead of the phone's screen.
 * Release builds only expose them during an explicitly started session.
 */
object StoreDiagnostics {
    private const val MAX_EVENTS = 2000

    fun init(application: Application, auditLog: AuditLog, push: NotificationBrokerRuntime) {
        Androidoscopy.init(application) {
            appName = "LelloStore"
            tool(auditTool(auditLog))
            tool(Tool("push.status", "Push broker connection, active routes and app registrations (no tokens)",
                readOnly = true) {
                ToolResult.json(Json.parseToJsonElement(push.diagnostics().toString()) as JsonObject)
            })
        }
    }

    internal fun auditTool(auditLog: AuditLog) = Tool(
        name = "audit.query",
        description = "Latest local audit events (push, IPC, HTTP, updates), newest last. " +
            "Optionally filter by text and by minimum timestamp.",
        inputSchema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("filter") { put("type", "string"); put("description", "Case-insensitive text every event must contain") }
                putJsonObject("since_ms") { put("type", "integer"); put("description", "Only events at or after this epoch millisecond") }
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", MAX_EVENTS) }
            }
        },
        readOnly = true,
    ) { args ->
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val since = args["since_ms"]?.jsonPrimitive?.longOrNull ?: 0
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 200
        val snapshot = withContext(Dispatchers.IO) { auditLog.snapshot() }
        ToolResult.text(query(snapshot, filter, since, limit))
    }

    /** Filters the NDJSON snapshot; returns the newest [limit] matches as NDJSON. */
    internal fun query(snapshot: String, filter: String?, since: Long, limit: Int): String =
        snapshot.lineSequence().filter { it.isNotBlank() }
            .filter { filter == null || it.contains(filter, ignoreCase = true) }
            .filter { since <= 0 || timestamp(it)?.let { at -> at >= since } == true }
            .toList().takeLast(limit.coerceIn(1, MAX_EVENTS)).joinToString("\n")

    private fun timestamp(line: String): Long? = runCatching {
        (Json.parseToJsonElement(line) as JsonObject)["timestamp_ms"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
}
