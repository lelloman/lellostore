package com.lelloman.store.remoteadb

import com.lelloman.store.remoteadb.AdbPacket.Companion.AUTH
import com.lelloman.store.remoteadb.AdbPacket.Companion.CLSE
import com.lelloman.store.remoteadb.AdbPacket.Companion.CNXN
import com.lelloman.store.remoteadb.AdbPacket.Companion.OKAY
import com.lelloman.store.remoteadb.AdbPacket.Companion.OPEN
import com.lelloman.store.remoteadb.AdbPacket.Companion.WRTE
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** One command at a time; callers serialize operations and may close from another thread. */
class AdbConnection(private val transport: AdbTransport) : Closeable {
    private var version = AdbPacket.VERSION
    private var maxData = 4096
    private var nextId = 1
    private var connected = false

    fun authenticate(identity: AdbSigner, timeoutMs: Int = 60_000, awaitingAuthorization: () -> Unit = {}) {
        send(AdbPacket(CNXN, AdbPacket.VERSION_SKIP_CHECKSUM, AdbPacket.MAX_PAYLOAD, "host::\u0000".toByteArray()))
        var signed = false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) throw IOException("ADB authorization timed out")
            val packet = AdbPacket.read(transport, version, remaining)
            when (packet.command) {
                CNXN -> {
                    if (packet.arg0 < AdbPacket.VERSION || packet.arg1 <= 0) throw IOException("Unsupported ADB protocol")
                    version = minOf(packet.arg0, AdbPacket.VERSION_SKIP_CHECKSUM)
                    maxData = minOf(packet.arg1, AdbPacket.MAX_PAYLOAD)
                    connected = true
                    return
                }
                AUTH -> {
                    if (packet.arg0 != 1 || packet.data.size != 20) throw IOException("Invalid ADB challenge")
                    if (!signed) {
                        send(AdbPacket(AUTH, 2, 0, identity.sign(packet.data)))
                        signed = true
                    } else {
                        awaitingAuthorization()
                        send(AdbPacket(AUTH, 3, 0, identity.publicKey()))
                    }
                }
                else -> throw IOException("Unsupported ADB authentication response")
            }
        }
    }

    fun execute(
        service: String,
        input: InputStream? = null,
        size: Long = 0,
        timeoutMs: Int = 30_000,
        onProgress: (Long) -> Unit = {},
    ): String {
        check(connected) { "ADB is not connected" }
        require(!service.contains('\u0000') && service.toByteArray().size < 4096)
        require(size >= 0 && (size == 0L || input != null))
        val local = nextId++
        val output = ByteArrayOutputStream()
        send(AdbPacket(OPEN, local, 0, (service + "\u0000").toByteArray()))
        var remote = 0
        var sent = 0L
        var awaitingWriteAck = false
        var opened = false
        while (true) {
            val packet = AdbPacket.read(transport, version, timeoutMs)
            if (packet.arg1 != local) throw IOException("Unexpected ADB stream")
            if (opened && packet.arg0 != remote) throw IOException("Unexpected ADB peer stream")
            when (packet.command) {
                OKAY -> {
                    remote = packet.arg0
                    if (remote == 0) throw IOException("Invalid ADB stream")
                    opened = true
                    awaitingWriteAck = false
                    if (sent < size) {
                        val bytes = ByteArray(minOf(maxData.toLong(), size - sent).toInt())
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input!!.read(bytes, offset, bytes.size - offset)
                            if (count <= 0) throw IOException("APK ended before its declared size")
                            offset += count
                        }
                        send(AdbPacket(WRTE, local, remote, bytes))
                        sent += bytes.size
                        awaitingWriteAck = true
                        onProgress(sent)
                    }
                }
                WRTE -> {
                    if (!opened) throw IOException("ADB wrote before opening a stream")
                    if (output.size() + packet.data.size > MAX_OUTPUT) throw IOException("ADB response too large")
                    output.write(packet.data)
                    send(AdbPacket(OKAY, local, remote))
                }
                CLSE -> {
                    if (opened) send(AdbPacket(CLSE, local, remote))
                    if (!opened) throw IOException("ADB service was rejected")
                    // A final package-manager response may precede the final write acknowledgement.
                    if (sent < size || (awaitingWriteAck && output.size() == 0)) {
                        throw IOException("ADB stream closed before transfer completed")
                    }
                    return output.toString(Charsets.UTF_8.name()).trim()
                }
                else -> throw IOException("Unexpected ADB response")
            }
        }
    }

    private fun send(packet: AdbPacket) {
        transport.write(packet.header(), 30_000)
        if (packet.data.isNotEmpty()) transport.write(packet.data, 30_000)
    }

    override fun close() {
        connected = false
        transport.close()
    }

    companion object { private const val MAX_OUTPUT = 1024 * 1024 }
}
