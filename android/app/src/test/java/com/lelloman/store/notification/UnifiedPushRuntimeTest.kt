package com.lelloman.store.notification

import android.app.Application
import android.content.Intent
import com.lelloman.store.domain.auth.AuthStore
import io.mockk.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UnifiedPushRuntimeTest {
    @Test fun receiptPruningPreservesLongLivedRegistrationsAndPendingCleanup() {
        val db = PrivateStore(RuntimeEnvironment.getApplication(), "prune-test")
        try {
            listOf("registration", "pending-registration", "unregister", "offered", "ack", "endpoint").forEach {
                db.put(it, JSONObject(), it)
            }
            db.writableDatabase.execSQL("UPDATE entries SET at=1")
            db.prune()
            assertEquals(setOf("registration", "pending-registration", "unregister"), db.entries().map { it.first }.toSet())
        } finally { db.close() }
    }
    @Test fun ackIsBoundToBothRegistrationAndOfferedMessageAndPersistsOffline() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val runtime = NotificationBrokerRuntime(context, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), backgroundScope)
        val db = runtime.db
        db.clear()
        db.put("registration:token", JSONObject().put("token", "token").put("package", "app.test"), "registration")
        db.put("message:id", JSONObject().put("token", "token"), "offered")
        val ack = Intent("org.unifiedpush.android.distributor.MESSAGE_ACK").putExtra("token", "wrong-token").putExtra("id", "id")
        runtime.process("", ack)
        assertEquals("offered", db.entry("message:id")!!.second)
        runtime.process("", ack.putExtra("token", "token"))
        assertEquals("ack", db.entry("message:id")!!.second)
        val reopened = PrivateStore(context, "unifiedpush-broker")
        assertEquals("receipt", reopened.entry("message:id")!!.first.getString("kind"))
        assertEquals("token", reopened.entry("message:id")!!.first.getString("token"))
        reopened.close()
    }
    @Test fun unofferedMessageCannotBeAcknowledged() = runTest {
        val runtime = NotificationBrokerRuntime(RuntimeEnvironment.getApplication(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), backgroundScope)
        runtime.db.clear()
        runtime.db.put("registration:token", JSONObject().put("token", "token").put("package", "app.test"), "registration")
        runtime.process("", Intent("org.unifiedpush.android.distributor.MESSAGE_ACK").putExtra("token", "token").putExtra("id", "invented-id"))
        assertNull(runtime.db.entry("message:invented-id"))
    }
    @Test fun signedOutStoreDoesNotCreateRegistration() = runTest {
        val auth = mockk<AuthStore> { coEvery { getAccessToken() } returns null }
        val context = RuntimeEnvironment.getApplication()
        val runtime = NotificationBrokerRuntime(context, auth, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), backgroundScope)
        runtime.db.clear()
        runtime.process("app.test", Intent("org.unifiedpush.android.distributor.REGISTER").putExtra("token", "token").putExtra("vapid", "B".repeat(87)))
        assertTrue(runtime.registeredApps().isEmpty())
        val sent = org.robolectric.Shadows.shadowOf(context).broadcastIntents.last()
        assertEquals("org.unifiedpush.android.connector.REGISTRATION_FAILED", sent.action)
        assertEquals("ACTION_REQUIRED", sent.getStringExtra("reason"))
    }
}
