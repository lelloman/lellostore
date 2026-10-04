package com.lelloman.store.notification

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
class UnifiedPushReceiverTest {
    private fun request(action: String = "REGISTER") = Intent("org.unifiedpush.android.distributor.$action").putExtra("token", "unguessable-token")
    @Test fun missingTokenAndWrongTypesAreIgnored() {
        assertFalse(UnifiedPushReceiver.validRequest(Intent("org.unifiedpush.android.distributor.REGISTER")))
        assertFalse(UnifiedPushReceiver.validRequest(request().putExtra("token", 42)))
    }
    @Test fun limitsUseUtf8BytesAndOptionalFieldsMustBeNonNull() {
        assertTrue(UnifiedPushReceiver.validRequest(request().putExtra("token", "é".repeat(50))))
        assertFalse(UnifiedPushReceiver.validRequest(request().putExtra("token", "é".repeat(51))))
        assertFalse(UnifiedPushReceiver.validRequest(request().putExtra("message", null as String?)))
        assertFalse(UnifiedPushReceiver.validRequest(request().putExtra("vapid", "invalid")))
    }
    @Test fun acknowledgmentsNeedTokenAndIdButNoIdentityExtra() {
        assertFalse(UnifiedPushReceiver.validRequest(request("MESSAGE_ACK")))
        assertTrue(UnifiedPushReceiver.validRequest(request("MESSAGE_ACK").putExtra("id", "random-message-id")))
    }
    @Test fun missingVapidReachesHandlerForStandardVapidRequiredReply() {
        assertTrue(UnifiedPushReceiver.validRequest(request()))
    }
}
