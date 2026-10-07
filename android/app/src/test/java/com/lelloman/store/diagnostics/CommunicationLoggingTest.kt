package com.lelloman.store.diagnostics

import android.app.Application
import android.content.Context
import android.content.Intent
import com.lelloman.store.logger.AuditLog
import com.lelloman.store.remoteapi.AuditHttpInterceptor
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CommunicationLoggingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun audit(): AuditLog {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.newFolder()
        return AuditLog(context)
    }

    @Test fun httpMetadataCorrelatesRequestsWithoutReadingBodiesOrLeakingSecrets() {
        val audit = audit()
        val request = Request.Builder().url("https://example.test/api/push/v1/subscriptions/path-secret?key=query-secret")
            .header("Authorization", "Bearer header-secret").post("body-secret".toRequestBody()).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("response-secret".toResponseBody()).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response
        assertEquals("response-secret", AuditHttpInterceptor(audit).intercept(chain).body!!.string())
        val snapshot = audit.snapshot()
        assertFalse(snapshot.contains("secret"))
        val events = snapshot.trim().lines().map(::JSONObject)
        assertEquals(listOf("http.started", "http.response"), events.map { it.getString("event") })
        assertEquals(events[0].getJSONObject("fields").getString("request_id"), events[1].getJSONObject("fields").getString("request_id"))
        assertEquals(200, events[1].getJSONObject("fields").getInt("status"))
        every { chain.proceed(request) } throws IOException("exception-secret")
        assertThrows(IOException::class.java) { AuditHttpInterceptor(audit).intercept(chain) }
        assertFalse(audit.snapshot().contains("secret"))
        assertTrue(audit.snapshot().contains("http.failed"))
    }

    @Test fun clearFlushesEarlierWritesAndAllowsNewHistory() {
        val audit = audit()
        repeat(100) { audit.record("old.event") }
        audit.clear()
        assertEquals("", audit.snapshot())
        audit.record("new.event")
        assertTrue(audit.snapshot().contains("new.event"))
        assertFalse(audit.snapshot().contains("old.event"))
    }

    @Test fun systemObserverRecordsInitialStateAndScreenTransitions() {
        val context = RuntimeEnvironment.getApplication()
        val audit = audit()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        SystemConnectivityLog(context, audit).start()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val snapshot = audit.snapshot()
        assertTrue(snapshot.contains("system.network"))
        assertTrue(snapshot, snapshot.contains("system.power"))
        assertTrue(snapshot.contains(Intent.ACTION_SCREEN_OFF))
        assertTrue(snapshot.contains("validated"))
    }
}
