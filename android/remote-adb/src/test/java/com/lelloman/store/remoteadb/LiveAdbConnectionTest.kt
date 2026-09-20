package com.lelloman.store.remoteadb

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Opt-in protocol check against a local emulator's real adbd TCP endpoint. */
class LiveAdbConnectionTest {
    @Test fun `sequential commands against real adbd`() {
        val port = System.getenv("LELLOSTORE_TEST_ADB_PORT")?.toIntOrNull()
        assumeTrue("Set LELLOSTORE_TEST_ADB_PORT to a local emulator endpoint", port != null)
        val keyFile = File(System.getProperty("user.home"), ".android/adbkey")
        val pem = keyFile.readLines().filterNot { it.startsWith("-----") }.joinToString("")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        AdbConnection(TcpAdbTransport("127.0.0.1", port!!)).use { adb ->
            adb.authenticate(object : AdbSigner {
                override fun sign(token: ByteArray): ByteArray = AndroidPubkey.adbAuthSign(key, token)
                override fun publicKey(): ByteArray = (File(keyFile.path + ".pub").readText().trim() + "\u0000").toByteArray()
            })
            repeat(20) {
                assertThat(adb.execute("shell:echo stream-$it")).isEqualTo("stream-$it")
            }
        }
    }
}
