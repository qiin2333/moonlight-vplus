package com.limelight.computers

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class StunClientTest {
    private val transaction = ByteArray(12) { it.toByte() }

    private fun response(transaction: ByteArray = this.transaction, prefix: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(32 + prefix.size).putShort(0x0101).putShort((12 + prefix.size).toShort())
            .putInt(0x2112a442).put(transaction).put(prefix)
            .putShort(0x0020).putShort(8).put(0).put(1).putShort(1234)
            .putInt(0xcb007101.toInt() xor 0x2112a442).array()

    @Test fun decodesXorMappedIpv4AndPaddedUnknownAttribute() {
        assertEquals("203.0.113.1", StunClient.decode(response(), transaction))
        val unknown = ByteBuffer.allocate(8).putShort(0x8022.toShort()).putShort(1).put(65).put(ByteArray(3)).array()
        assertEquals("203.0.113.1", StunClient.decode(response(prefix = unknown), transaction))
    }

    @Test fun rejectsTruncatedAndMalformedHeaders() {
        val valid = response()
        assertNull(StunClient.decode(valid.copyOf(19), transaction))
        assertNull(StunClient.decode(valid.copyOf(31), transaction))
        assertNull(StunClient.decode(valid.clone().also { it[1] = 0x11 }, transaction))
        assertNull(StunClient.decode(valid.clone().also { it[4] = 0 }, transaction))
        assertNull(StunClient.decode(valid.clone().also { it[3] = 11 }, transaction))
        assertNull(StunClient.decode(valid, ByteArray(12) { 42 }))
    }

    @Test fun rejectsAttributeOverrunAndWrongFamily() {
        assertNull(StunClient.decode(response().also { it[23] = 16 }, transaction))
        assertNull(StunClient.decode(response().also { it[25] = 2 }, transaction))
    }

    @Test fun exchangeUsesBoundSocketAndChecksActualResponseTransaction() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2000
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    val request = DatagramPacket(ByteArray(100), 100)
                    server.receive(request)
                    val header = ByteBuffer.wrap(request.data, 0, request.length)
                    assertEquals(1, header.short.toInt())
                    assertEquals(0, header.short.toInt())
                    assertEquals(0x2112a442, header.int)
                    val id = ByteArray(12).also { header.get(it) }
                    val reply = response(id)
                    server.send(DatagramPacket(reply, reply.size, request.address, request.port))
                } catch (e: Throwable) { failure.set(e) }
            }
            thread.start()
            var bound = false
            val result = StunClient.query("test.invalid", server.localPort, 1500,
                { arrayOf(loopback) }, { bound = true }, {})
            thread.join(2500)
            assertFalse(thread.isAlive)
            failure.get()?.let { throw it }
            assertTrue(bound)
            assertEquals("203.0.113.1", result)
        }
    }

    @Test fun unavailableServerHasBoundedWait() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            val start = System.nanoTime()
            assertNull(StunClient.query("test.invalid", server.localPort, 100,
                { arrayOf(loopback) }, {}, {}))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
        }
    }

    @Test fun lateDnsCannotStartUdpAfterDeadline() {
        var bound = false
        assertNull(StunClient.query("test.invalid", 3478, 1,
            { Thread.sleep(20); arrayOf(InetAddress.getByName("127.0.0.1")) }, { bound = true }, {}))
        assertFalse(bound)
    }

    @Test fun cancellationAfterDnsCannotOpenSocket() {
        var resolved = false
        var bound = false
        try {
            StunClient.query("test.invalid", 3478, 1000,
                { resolved = true; arrayOf(InetAddress.getByName("127.0.0.1")) }, { bound = true },
                { if (resolved) throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertFalse(bound)
    }
}
