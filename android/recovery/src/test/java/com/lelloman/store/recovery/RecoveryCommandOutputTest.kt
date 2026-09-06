package com.lelloman.store.recovery

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryCommandOutputTest {
    @Test
    fun `complete command response is preserved up to the byte limit`() {
        val response = "x".repeat(RecoveryCommandOutput.MAX_BYTES)
        assertThat(RecoveryCommandOutput.read(response.byteInputStream())).isEqualTo(response)
        assertThat(RecoveryCommandOutput.read("Success\n".byteInputStream())).isEqualTo("Success\n")
    }

    @Test
    fun `oversized response fails and closes the stream`() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(RecoveryCommandOutput.MAX_BYTES + 1)) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IOException::class.java) { RecoveryCommandOutput.read(input) }
        assertThat(closed).isTrue()
    }

    @Test
    fun `transport failure after a success or failure prefix remains uncertain`() {
        for (prefix in listOf("Success", "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")) {
            var closed = false
            val input = object : InputStream() {
                var emitted = false
                override fun read(): Int = throw IOException("Disconnected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (emitted) throw IOException("Disconnected")
                    emitted = true
                    prefix.toByteArray().copyInto(buffer, offset)
                    return prefix.length
                }
                override fun close() { closed = true }
            }
            assertThrows(IOException::class.java) { RecoveryCommandOutput.read(input) }
            assertThat(closed).isTrue()
        }
    }
}
