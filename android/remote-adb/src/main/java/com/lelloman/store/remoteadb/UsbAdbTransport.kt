package com.lelloman.store.remoteadb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbAdbTransport internal constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : AdbTransport {
    private val closed = AtomicBoolean(false)

    override fun read(length: Int, timeoutMs: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (offset < length) {
            val count = connection.bulkTransfer(input, bytes, offset, minOf(length - offset, CHUNK), remaining(deadline))
            if (count <= 0) throw IOException("USB read failed or timed out")
            offset += count
        }
        return bytes
    }

    override fun write(bytes: ByteArray, timeoutMs: Int) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(bytes.size - offset, CHUNK)
            val count = connection.bulkTransfer(output, bytes, offset, length, remaining(deadline))
            if (count != length) throw IOException("Incomplete USB write")
            offset += count
        }
        // Terminate endpoint-aligned transfers: adbd cannot infer their boundary.
        if (bytes.isNotEmpty() && bytes.size % output.maxPacketSize == 0) {
            if (connection.bulkTransfer(output, byteArrayOf(), 0, remaining(deadline)) != 0) {
                throw IOException("USB transfer termination failed")
            }
        }
    }

    private fun remaining(deadline: Long): Int {
        if (closed.get() || Thread.currentThread().isInterrupted) throw IOException("USB connection closed")
        val ms = (deadline - System.nanoTime()) / 1_000_000L
        if (ms <= 0) throw IOException("USB operation timed out")
        return ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    companion object {
        private const val CHUNK = 16 * 1024
        fun adbInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount)
            .map(device::getInterface).firstOrNull {
                it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1
            }

        fun open(manager: UsbManager, device: UsbDevice): UsbAdbTransport {
            check(manager.hasPermission(device)) { "USB permission is required" }
            val intf = adbInterface(device) ?: throw IOException("Device has no USB debugging interface")
            val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                ?: throw IOException("Missing USB input endpoint")
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                ?: throw IOException("Missing USB output endpoint")
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
            if (!connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("Cannot claim USB debugging interface")
            }
            return UsbAdbTransport(connection, intf, input, output)
        }
    }
}
