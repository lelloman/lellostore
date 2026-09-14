package com.lelloman.store.diagnostics

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.logger.AndroidLogger
import com.lelloman.store.logger.AuditLog
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class)
class AuditLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportFlushesStructuredRecordsWithoutPersistingOrdinaryMessages() {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.newFolder()
        val audit = AuditLog(context)
        val logger = AndroidLogger(audit)
        logger.i("Auth", "secret must stay out of the audit file")
        logger.audit("operation.started", mapOf("operation_id" to "test", "bytes" to 42L, "cache_hit" to false))
        logger.audit("operation.finished", mapOf("operation_id" to "test", "state" to "COMPLETED"))
        val snapshot = audit.snapshot()
        val events = snapshot.lines().filter { it.isNotBlank() }.map(::JSONObject)
        assertThat(events).hasSize(2)
        assertThat(events[0].getInt("schema")).isEqualTo(1)
        assertThat(events[0].getJSONObject("fields").getLong("bytes")).isEqualTo(42)
        assertThat(events[0].getJSONObject("fields").getBoolean("cache_hit")).isFalse()
        assertThat(events[0].getString("session_id")).isEqualTo(events[1].getString("session_id"))
        assertThat(snapshot).doesNotContain("secret")
        assertThat(AuditLog(context).snapshot()).isEqualTo(snapshot)
    }

    @Test fun serializationFailureDoesNotBreakCallerAndIsReported() {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.newFolder()
        val audit = AuditLog(context)
        audit.record("bad", mapOf("value" to Double.NaN))
        audit.record("good")
        val events = audit.snapshot().lines().filter { it.isNotBlank() }.map(::JSONObject)
        assertThat(events.map { it.getString("event") }).containsExactly("audit.dropped", "good").inOrder()
        assertThat(events[0].getLong("count")).isEqualTo(1)
    }
}
