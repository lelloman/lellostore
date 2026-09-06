package com.lelloman.store.recovery

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryCommandOutputTest {
    private val marker = "\ncompletion-test\n"
    @Test
    fun `complete command response is preserved up to the byte limit`() {
        val response = "x".repeat(RecoveryCommandOutput.MAX_BYTES - marker.length)
        assertThat(RecoveryCommandOutput.read((response + marker).byteInputStream(), marker)).isEqualTo(response)
        assertThat(RecoveryCommandOutput.read(("Success\n" + marker).byteInputStream(), marker)).isEqualTo("Success\n")
    }


    @Test
    fun `completion marker avoids reading a normal ADB close error`() {
        val response = ("Success\n" + marker).byteInputStream()
        val input = object : InputStream() {
            override fun read(): Int = response.read().also { if (it < 0) throw IOException("Stream closed") }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Split the marker across reads to exercise ADB packet boundaries.
                return response.read(buffer, offset, minOf(length, 3)).also {
                    if (it < 0) throw IOException("Stream closed")
                }
            }
        }
        assertThat(RecoveryCommandOutput.read(input, marker)).isEqualTo("Success\n")
    }

    @Test
    fun `EOF and another commands marker cannot confirm completion`() {
        for (response in listOf("Success\n", "Success\n\nother-marker\n")) {
            assertThrows(IOException::class.java) {
                RecoveryCommandOutput.read(response.byteInputStream(), marker)
            }
        }
    }

    @Test
    fun `oversized response fails and closes the stream`() {
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(RecoveryCommandOutput.MAX_BYTES + 1)) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(IOException::class.java) { RecoveryCommandOutput.read(input, marker) }
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
            assertThrows(IOException::class.java) { RecoveryCommandOutput.read(input, marker) }
            assertThat(closed).isTrue()
        }
    }
}
