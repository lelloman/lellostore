package com.lelloman.store.remoteadb

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class AdbConnectionTest {
    private val signer = object : AdbSigner {
        override fun sign(token: ByteArray) = ByteArray(256) { 7 }
        override fun publicKey() = "public-key controller\u0000".toByteArray()
    }
    private fun connected(maxData: Int = 4096) = AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, maxData, "device::\u0000".toByteArray())
    private fun okay(local: Int = 1) = AdbPacket(AdbPacket.OKAY, 10, local)
    private fun close(local: Int = 1) = AdbPacket(AdbPacket.CLSE, 10, local)
    private fun response(text: String, local: Int = 1) = AdbPacket(AdbPacket.WRTE, 10, local, text.toByteArray())

    @Test fun `first authorization signs challenge then offers public key`() {
        val transport = FakeTransport(AdbPacket(AdbPacket.AUTH, 1, 0, ByteArray(20)),
            AdbPacket(AdbPacket.AUTH, 1, 0, ByteArray(20)), connected())
        var prompted = false
        AdbConnection(transport).authenticate(signer) { prompted = true }
        val sent = transport.packets()
        assertThat(sent.map { it.command }).containsExactly(AdbPacket.CNXN, AdbPacket.AUTH, AdbPacket.AUTH).inOrder()
        assertThat(sent[1].arg0).isEqualTo(2)
        assertThat(sent[2].arg0).isEqualTo(3)
        assertThat(prompted).isTrue()
    }

    @Test fun `invalid authentication challenge is rejected`() {
        val transport = FakeTransport(AdbPacket(AdbPacket.AUTH, 1, 0, ByteArray(19)))
        assertThrows(IOException::class.java) { AdbConnection(transport).authenticate(signer) }
    }

    @Test fun `modern authentication omits checksums before peer CNXN arrives`() {
        val challenge = AdbPacket(AdbPacket.AUTH, 1, 0, ByteArray(20) { (it + 1).toByte() })
        fun withoutChecksum(packet: AdbPacket) = packet.header().apply { fill(0, 16, 20) } + packet.data
        val transport = FakeTransport(withoutChecksum(challenge) + withoutChecksum(challenge) +
            withoutChecksum(connected().copy(arg0 = AdbPacket.VERSION_SKIP_CHECKSUM)) +
            withoutChecksum(okay()) + withoutChecksum(response("uid=2000(shell)")) + withoutChecksum(close()))
        val adb = AdbConnection(transport)
        var prompted = false
        adb.authenticate(signer) { prompted = true }
        assertThat(prompted).isTrue()
        assertThat(adb.execute("shell:id")).isEqualTo("uid=2000(shell)")
        assertThat(transport.packets().filter { it.command == AdbPacket.AUTH }.map { it.arg0 })
            .containsExactly(2, 3).inOrder()
    }

    @Test fun `legacy authentication still validates nonzero checksums`() {
        val challenge = AdbPacket(AdbPacket.AUTH, 1, 0, ByteArray(20) { 7 })
        AdbConnection(FakeTransport(challenge, connected())).authenticate(signer)
        val corrupt = challenge.header() + challenge.data.copyOf().apply { this[0] = 8 }
        assertThrows(IOException::class.java) { AdbConnection(FakeTransport(corrupt)).authenticate(signer) }
    }

    @Test fun `omitted checksum remains invalid after negotiating legacy protocol`() {
        val packet = response("Success")
        val wire = connected().let { it.header() + it.data } + okay().header() +
            packet.header().apply { fill(0, 16, 20) } + packet.data
        val adb = AdbConnection(FakeTransport(wire))
        adb.authenticate(signer)
        assertThrows(IOException::class.java) { adb.execute("shell:id") }
    }

    @Test fun `APK writes respect peer payload limit and tolerate final response before ack`() {
        val transport = FakeTransport(connected(4), okay(), okay(),
            response("Success\n"), close())
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        val progress = mutableListOf<Long>()
        val bytes = "abcdefg".toByteArray()
        val result = adb.execute("exec:install", bytes.inputStream(), bytes.size.toLong(), onProgress = progress::add)
        assertThat(result).isEqualTo("Success")
        assertThat(progress).containsExactly(4L, 7L).inOrder()
        assertThat(transport.packets().filter { it.command == AdbPacket.WRTE }.map { it.data.toString(Charsets.UTF_8) })
            .containsExactly("abcd", "efg").inOrder()
    }

    @Test fun `truncated final response never becomes success`() {
        val transport = FakeTransport(connected(), okay(), response("Suc"))
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        assertThrows(IOException::class.java) { adb.execute("exec:install") }
    }

    @Test fun `early success cannot conceal incomplete upload`() {
        val transport = FakeTransport(connected(4), okay(), response("Success"), close())
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        assertThrows(IOException::class.java) { adb.execute("exec:install", ByteArray(8).inputStream(), 8) }
    }

    @Test fun `rejected service and mismatched stream IDs fail`() {
        for (packet in listOf(AdbPacket(AdbPacket.CLSE, 0, 1), okay(3))) {
            val transport = FakeTransport(connected(), packet)
            val adb = AdbConnection(transport)
            adb.authenticate(signer)
            assertThrows(IOException::class.java) { adb.execute("shell:id") }
        }
    }

    @Test fun `negotiated checksum skipping accepts modern peer responses`() {
        val packet = connected().copy(arg0 = AdbPacket.VERSION_SKIP_CHECKSUM)
        val wire = packet.header().apply { fill(0, 16, 20) } + packet.data
        AdbConnection(FakeTransport(wire)).authenticate(signer)
    }

    @Test fun `malformed headers rejected before allocating their payload`() {
        for (offset in listOf(12, 20)) {
            val header = connected().header().apply { this[offset] = 0xff.toByte(); this[offset + 3] = 0xff.toByte() }
            assertThrows(IOException::class.java) { AdbConnection(FakeTransport(header)).authenticate(signer) }
        }
    }

    @Test fun `checksum corruption is rejected for legacy peers`() {
        val packet = connected()
        val wire = packet.header() + packet.data.copyOf().apply { this[0] = 0 }
        assertThrows(IOException::class.java) { AdbConnection(FakeTransport(wire)).authenticate(signer) }
    }

    @Test fun `sequential commands allocate new stream IDs`() {
        val transport = FakeTransport(connected(), okay(), response("one"), close(), okay(2), response("two", 2), close(2))
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        assertThat(adb.execute("shell:one")).isEqualTo("one")
        assertThat(adb.execute("shell:two")).isEqualTo("two")
        assertThat(transport.packets().filter { it.command == AdbPacket.OPEN }.map { it.arg0 }).containsExactly(1, 2).inOrder()
        adb.close()
        assertThat(transport.closed).isTrue()
    }

    @Test fun `late duplicate closes do not break the next command`() {
        val transport = FakeTransport(connected(), okay(), response("one"), close(),
            close(), okay(2), close(), response("two", 2), close(2))
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        assertThat(adb.execute("shell:one")).isEqualTo("one")
        assertThat(adb.execute("shell:two")).isEqualTo("two")
        assertThat(transport.packets().count { it.command == AdbPacket.CLSE }).isEqualTo(2)
    }

    @Test fun `legacy zero peer close can finish an opened stream`() {
        val transport = FakeTransport(connected(), okay(), response("one"), close().copy(arg0 = 0))
        val adb = AdbConnection(transport)
        adb.authenticate(signer)
        assertThat(adb.execute("shell:one")).isEqualTo("one")
    }

    @Test fun `stale data future closes and incorrect active peer are rejected`() {
        for (packet in listOf(response("stale"), close(3), close(2).copy(arg0 = 99))) {
            val transport = FakeTransport(connected(), okay(), response("one"), close(), okay(2), packet)
            val adb = AdbConnection(transport)
            adb.authenticate(signer)
            adb.execute("shell:one")
            assertThrows(IOException::class.java) { adb.execute("shell:two") }
        }
    }

    private class FakeTransport(wire: ByteArray) : AdbTransport {
        constructor(vararg packets: AdbPacket) : this(packets.fold(byteArrayOf()) { bytes, packet -> bytes + packet.header() + packet.data })
        private val incoming = ByteArrayInputStream(wire)
        private val outgoing = mutableListOf<ByteArray>()
        var closed = false
        override fun read(length: Int, timeoutMs: Int): ByteArray {
            val bytes = ByteArray(length)
            if (incoming.read(bytes) != length) throw EOFException()
            return bytes
        }
        override fun write(bytes: ByteArray, timeoutMs: Int) { outgoing += bytes.copyOf() }
        override fun close() { closed = true }
        fun packets(): List<AdbPacket> {
            val wire = outgoing.fold(byteArrayOf()) { a, b -> a + b }
            val reader = FakeTransport(wire)
            return buildList { while (reader.incoming.available() > 0) add(AdbPacket.read(reader, AdbPacket.VERSION, 100)) }
        }
    }
}
