package com.lelloman.store.installation

import com.google.common.truth.Truth.assertThat
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyAdbInstallationChannelTest {
    @Test(timeout = 5000)
    fun `queued libadb payload and remote close do not hang installation`() {
        val connection = mockk<AdbConnection>()
        every { connection.maxData } returns 4096
        val stream = AdbStream::class.java
            .getDeclaredConstructor(AdbConnection::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(connection, 1)
        // Reproduce the daemon delivering WRTE and CLSE before the caller consumes WRTE.
        AdbStream::class.java.getDeclaredMethod("addPayload", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(stream, "Success\n".toByteArray())
        AdbStream::class.java.getDeclaredMethod("notifyClose", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(stream, true)

        assertThat(readAdbInstallResponse(stream.openInputStream())).isEqualTo("Success\n")
    }

    @Test
    fun `install result returns without another read after fragmented success`() {
        val input = chunks("Suc", "cess", "\n")
        assertThat(readAdbInstallResponse(input)).isEqualTo("Success\n")
    }

    @Test
    fun `install rejection returns without waiting for stream close`() {
        val input = chunks("Failure [INSTALL_FAILED_", "INVALID_APK]\r\n")
        assertThat(parseAdbInstallResponse(readAdbInstallResponse(input).trim()))
            .isEqualTo(ChannelInstallationResult.Failed(
                "Failure [INSTALL_FAILED_INVALID_APK]", canTryNextChannel = false))
    }

    @Test(timeout = 5000)
    fun `install timeout interrupts a blocked libadb style read`() {
        val interrupted = CountDownLatch(1)
        assertThrows(IOException::class.java) {
            runBlocking {
                withAdbInstallTimeout(100) {
                    try {
                        CountDownLatch(1).await()
                    } catch (error: InterruptedException) {
                        interrupted.countDown()
                        throw IOException(error)
                    }
                }
            }
        }
        assertThat(interrupted.count).isEqualTo(0)
    }

    @Test(timeout = 5000)
    fun `caller cancellation interrupts install instead of waiting for timeout`() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val task = async(Dispatchers.Default) {
            withAdbInstallTimeout {
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } finally {
                    interrupted.countDown()
                }
            }
        }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
        task.cancelAndJoin()
        assertThat(task.isCancelled).isTrue()
        assertThat(interrupted.count).isEqualTo(0)
    }

    private fun chunks(vararg chunks: String): InputStream = object : InputStream() {
        var index = 0
        override fun read(): Int = error("Bulk read expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(index < chunks.size) { "Reading past the terminal response would hang" }
            val bytes = chunks[index++].toByteArray()
            bytes.copyInto(buffer, offset)
            return bytes.size
        }
    }

    @Test
    fun `ADB install command permits upgrading an installed package`() {
        assertThat(adbInstallCommand(1234))
            .isEqualTo("exec:cmd package install -r -S 1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ADB install command rejects an empty APK`() {
        adbInstallCommand(0)
    }

    @Test
    fun `successful package manager response is installed`() {
        assertThat(parseAdbInstallResponse("Success"))
            .isEqualTo(ChannelInstallationResult.Installed)
    }

    @Test
    fun `package manager failure is definitive`() {
        val result = parseAdbInstallResponse("Failure [INSTALL_FAILED_INVALID_APK]")

        assertThat(result).isEqualTo(
            ChannelInstallationResult.Failed(
                reason = "Failure [INSTALL_FAILED_INVALID_APK]",
                canTryNextChannel = false,
            )
        )
    }

    @Test
    fun `empty package manager response is reported`() {
        assertThat(parseAdbInstallResponse(""))
            .isEqualTo(
                ChannelInstallationResult.Failed(
                    reason = "ADB package manager returned no result",
                    canTryNextChannel = false,
                )
            )
    }

    @Test
    fun `ADB text reader preserves payload when remote close is reported as exception`() {
        val input = object : InputStream() {
            private val payload = "uid=2000(shell)".toByteArray()
            private var consumed = false

            override fun read(): Int = error("Bulk read expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed) throw IOException("Stream closed.")
                consumed = true
                payload.copyInto(buffer, offset)
                return payload.size
            }
        }

        assertThat(readAdbText(input)).isEqualTo("uid=2000(shell)")
    }
}
