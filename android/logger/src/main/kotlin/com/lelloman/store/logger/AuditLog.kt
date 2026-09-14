package com.lelloman.store.logger

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Only explicitly structured events are persisted; ordinary log messages remain in logcat. */
@Singleton
class AuditLog @Inject constructor(@ApplicationContext context: Context) {
    private val ring = AuditRing(File(context.noBackupFilesDir, "audit"))
    private val session = UUID.randomUUID().toString()
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { task -> Thread(task, "store-audit").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        val record = try {
            val json = JSONObject().apply {
                put("schema", 1)
                put("timestamp_ms", System.currentTimeMillis())
                put("elapsed_ms", SystemClock.elapsedRealtime())
                put("session_id", session)
                put("event", event.take(120))
                put("fields", JSONObject(fields.entries.take(24).associate { (key, value) ->
                    key.take(80) to when (value) {
                        is Number, is Boolean -> value
                        null -> JSONObject.NULL
                        else -> value.toString().take(512)
                    }
                }))
            }
            checkNotNull(json.toString())
        } catch (_: Exception) {
            dropped.incrementAndGet()
            return
        }
        try {
            executor.execute {
                val lost = dropped.getAndSet(0)
                var reportedLost = false
                try {
                    if (lost > 0) ring.append(JSONObject(mapOf(
                        "schema" to 1, "event" to "audit.dropped", "session_id" to session,
                        "timestamp_ms" to System.currentTimeMillis(), "count" to lost,
                    )).toString())
                    reportedLost = true
                    ring.append(record)
                } catch (_: Exception) {
                    dropped.addAndGet(1 + if (reportedLost) 0 else lost)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            dropped.incrementAndGet()
        }
    }

    /** Call off the UI thread. Queued records are flushed before taking the snapshot. */
    fun snapshot(): String = executor.submit<String> { ring.snapshot() }.get(15, TimeUnit.SECONDS)
}
