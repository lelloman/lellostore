package com.lelloman.store.notifications.client

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DurableRecipientTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun persistedDeliverySurvivesDatabaseReopenOutsideBackup() {
        val name = "fixture-${UUID.randomUUID()}"
        val first = PrivateStore(context, name)
        first.put("delivery", JSONObject().put("payload", "persisted before receipt"))
        first.close()
        assertTrue(java.io.File(context.noBackupFilesDir, "$name.db").exists())
        val reopened = PrivateStore(context, name)
        assertEquals("persisted before receipt", reopened.entry("delivery")!!.first.getString("payload"))
        assertEquals("pending", reopened.entry("delivery")!!.second)
        reopened.clear(); reopened.close()
    }
    @Test fun changingRecipientSessionRejectsPriorGeneration() {
        val client = NotificationClient(context, context.packageName, setOf("a".repeat(64)), "Receiver") { null }
        client.endSession()
        val old = client.beginSession("https://issuer", "alice")
        val envelope = JSONObject().put("generation", old).put("installation", client.db.setting("installation"))
        assertTrue(client.accepts(envelope))
        assertEquals(old, client.beginSession("https://issuer", "alice"))
        val next = client.beginSession("https://issuer", "bob")
        assertNotEquals(old, next)
        assertFalse(client.accepts(envelope))
        client.endSession()
        assertFalse(client.accepts(envelope))
    }
    @Test fun sharedUidAndWrongPinsCannotImpersonateStore() {
        val pins = SigningIdentity.certificates(context, context.packageName)
        assertTrue(pins.isNotEmpty())
        SigningIdentity.requireCaller(context, android.os.Process.myUid(), context.packageName, pins)
        assertThrows(IllegalArgumentException::class.java) {
            SigningIdentity.requireCaller(context, android.os.Process.myUid(), "another.application", pins)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SigningIdentity.requireCaller(context, android.os.Process.myUid(), context.packageName, setOf("0".repeat(64)))
        }
    }
}
