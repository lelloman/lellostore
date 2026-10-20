package com.lelloman.store.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lelloman.authenticator.localdata.PhoneKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Isolated emulator only. Keys sign without a system prompt; separation and deletion still hold. */
@RunWith(AndroidJUnit4::class)
class PhoneKeyDeviceTest {
    @Test fun independentKeysSignWithoutPromptAndCannotStandInForEachOther() {
        val keys = PhoneKey()
        val first = keys.create(); val second = keys.create()
        try {
            assertNotEquals(first.second, second.second)
            assertTrue(runCatching { keys.begin(first.first, second.second) }.isFailure)
            val message = "synthetic enrollment proof".toByteArray()
            val output = keys.begin(first.first, first.second).apply { update(message) }.sign()
            for ((key, accepted) in listOf(first to true, second to false)) {
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(key.second)))
                val verified = Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(message) }.verify(output)
                assertEquals(accepted, verified)
            }
            keys.delete(first.first)
            assertTrue(runCatching { keys.begin(first.first, first.second) }.isFailure)
        } finally { keys.delete(first.first); keys.delete(second.first) }
    }
}
