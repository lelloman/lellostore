package com.lelloman.store.remoteadb

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ADB headers and payloads are separate USB transfers, not an arbitrary byte stream. */
interface AdbTransport : Closeable {
    fun read(length: Int, timeoutMs: Int): ByteArray
    fun write(bytes: ByteArray, timeoutMs: Int)
}

class TcpAdbTransport(host: String, port: Int = 5555) : AdbTransport {
    private val socket = Socket().apply {
        try {
            connect(InetSocketAddress(host, port), 5_000)
            tcpNoDelay = true
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    override fun read(length: Int, timeoutMs: Int): ByteArray {
        socket.soTimeout = timeoutMs
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = socket.getInputStream().read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("ADB connection closed")
            offset += count
        }
        return bytes
    }
    override fun write(bytes: ByteArray, timeoutMs: Int) = socket.getOutputStream().write(bytes)
    override fun close() = socket.close()
}

// Packet layout and constants adapted from libadb-android 3.1.1 AdbProtocol.
// Copyright 2013 Cameron Gutman. See THIRD_PARTY_NOTICES.md.
internal data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf()) {
    fun header(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        .putInt(data.sumOf { it.toInt() and 255 }).putInt(command.inv()).array()

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val VERSION_SKIP_CHECKSUM = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        fun read(transport: AdbTransport, version: Int, timeoutMs: Int): AdbPacket {
            val header = ByteBuffer.wrap(transport.read(24, timeoutMs)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            if (header.int != command.inv() || length !in 0..MAX_PAYLOAD) {
                throw IOException("Invalid ADB packet header")
            }
            val data = if (length == 0) byteArrayOf() else transport.read(length, timeoutMs)
            val skipChecksum = version >= VERSION_SKIP_CHECKSUM || (command == CNXN && arg0 >= VERSION_SKIP_CHECKSUM)
            if (!skipChecksum && data.sumOf { it.toInt() and 255 } != checksum) {
                throw IOException("Invalid ADB packet checksum")
            }
            return AdbPacket(command, arg0, arg1, data)
        }
    }
}
