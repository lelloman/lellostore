package com.lelloman.store.remoteadb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UsbAdbTransportTest {
    private val connection = mockk<UsbDeviceConnection>(relaxed = true)
    private val intf = mockk<UsbInterface>()
    private val input = mockk<UsbEndpoint>()
    private val output = mockk<UsbEndpoint> { every { maxPacketSize } returns 512 }
    private val transport = UsbAdbTransport(connection, intf, input, output)

    @Test fun `writes use API24 compatible chunks and terminate aligned payloads`() {
        val sizes = mutableListOf<Int>()
        every { connection.bulkTransfer(output, any(), any(), any(), any()) } answers { arg<Int>(3).also { sizes += it } }
        every { connection.bulkTransfer(output, any(), 0, any()) } returns 0
        transport.write(ByteArray(32768), 1000)
        assertThat(sizes).containsExactly(16384, 16384).inOrder()
        verify(exactly = 1) { connection.bulkTransfer(output, any(), 0, any()) }
    }

    @Test fun `unaligned transfer does not append zero packet`() {
        every { connection.bulkTransfer(output, any(), any(), any(), any()) } answers { arg(3) }
        transport.write(ByteArray(513), 1000)
        verify(exactly = 0) { connection.bulkTransfer(output, any(), 0, any()) }
    }

    @Test fun `partial reads are accumulated without losing bytes`() {
        every { connection.bulkTransfer(input, any(), any(), any(), any()) } answers {
            val bytes = arg<ByteArray>(1)
            val offset = arg<Int>(2)
            val length = minOf(arg<Int>(3), 7)
            repeat(length) { bytes[offset + it] = (offset + it).toByte() }
            length
        }
        assertThat(transport.read(24, 1000)).isEqualTo(ByteArray(24) { it.toByte() })
    }

    @Test fun `failed transfers do not spin and close releases resources once`() {
        every { connection.bulkTransfer(input, any(), any(), any(), any()) } returns -1
        assertThrows(IOException::class.java) { transport.read(24, 1000) }
        transport.close()
        transport.close()
        verify(exactly = 1) { connection.releaseInterface(intf) }
        verify(exactly = 1) { connection.close() }
        assertThrows(IOException::class.java) { transport.write(ByteArray(24), 1000) }
    }
}
