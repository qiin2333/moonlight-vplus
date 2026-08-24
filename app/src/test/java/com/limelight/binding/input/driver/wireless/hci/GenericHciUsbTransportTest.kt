package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GenericHciUsbTransportTest {
    @Test
    fun opensDispatchesBothChannelsAndSerializesWrites() {
        val io = FakeHciUsbIo()
        val events = Collections.synchronizedList(ArrayList<HciEventPacket>())
        val aclPackets = Collections.synchronizedList(ArrayList<HciAclPacket>())
        val received = CountDownLatch(2)
        val transport = GenericHciUsbTransport(io, HciUsbAdapterProfile.GENERIC)
        transport.setListener(object : HciPacketListener {
            override fun onEvent(packet: HciEventPacket) {
                events.add(packet)
                received.countDown()
            }

            override fun onAcl(packet: HciAclPacket) {
                aclPackets.add(packet)
                received.countDown()
            }

            override fun onTransportFailure(failure: HciTransportFailure) = Unit
        })

        assertTrue(transport.open())
        assertEquals(HciTransportState.OPEN, transport.state)
        assertTrue(transport.configureAclOutput(1019, 8))
        assertTrue(transport.sendCommand(HciCommandPacket(HciOpcodes.RESET)))
        assertTrue(transport.sendAcl(HciAclPacket(0x12, 0x02, 0, byteArrayOf(0x41))))

        io.emit(HciUsbInputChannel.EVENT, byteArrayOf(0x0e, 0x01, 0x00))
        val acl = HciPacketCodec.encodeAcl(
            HciAclPacket(0x23, 0x02, 0, byteArrayOf(0x51, 0x52, 0x53))
        )
        io.emit(HciUsbInputChannel.ACL, acl.copyOfRange(0, 5))
        io.emit(HciUsbInputChannel.ACL, acl.copyOfRange(5, acl.size))

        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals(1, events.size)
        assertEquals(0x0e, events[0].eventCode)
        assertEquals(1, aclPackets.size)
        assertEquals(0x23, aclPackets[0].connectionHandle)
        assertArrayEquals(byteArrayOf(0x51, 0x52, 0x53), aclPackets[0].payload)
        assertArrayEquals(byteArrayOf(0x03, 0x0c, 0x00), io.commandWrites.single())
        assertArrayEquals(
            HciPacketCodec.encodeAcl(HciAclPacket(0x12, 0x02, 0, byteArrayOf(0x41))),
            io.aclWrites.single()
        )

        transport.close()
        assertEquals(HciTransportState.CLOSED, transport.state)
        assertTrue(io.closed)
        assertTrue(io.finished)
        assertFalse(io.finishedWhileReading)
    }

    @Test
    fun malformedInputFailsOnceAndClosesIo() {
        val io = FakeHciUsbIo()
        val failures = Collections.synchronizedList(ArrayList<HciTransportFailure>())
        val failed = CountDownLatch(1)
        val transport = GenericHciUsbTransport(io, HciUsbAdapterProfile.GENERIC)
        transport.setListener(object : HciPacketListener {
            override fun onEvent(packet: HciEventPacket) = Unit
            override fun onAcl(packet: HciAclPacket) = Unit

            override fun onTransportFailure(failure: HciTransportFailure) {
                failures.add(failure)
                failed.countDown()
            }
        })

        assertTrue(transport.open())
        io.emit(HciUsbInputChannel.EVENT, byteArrayOf(0x0e, 0x02, 0x00))

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(HciTransportState.FAILED, transport.state)
        assertEquals(HciTransportErrorCode.MALFORMED_EVENT, failures.single().code)
        assertTrue(io.closed)
        assertTrue(io.awaitFinished())

        transport.close()
        assertEquals(HciTransportState.CLOSED, transport.state)
    }

    @Test
    fun fragmentsAclOutputAndConsumesCompletedPacketsEvent() {
        val io = FakeHciUsbIo()
        val forwardedEvents = Collections.synchronizedList(ArrayList<HciEventPacket>())
        val transport = GenericHciUsbTransport(io, HciUsbAdapterProfile.GENERIC)
        transport.setListener(object : HciPacketListener {
            override fun onEvent(packet: HciEventPacket) {
                forwardedEvents.add(packet)
            }

            override fun onAcl(packet: HciAclPacket) = Unit
            override fun onTransportFailure(failure: HciTransportFailure) = Unit
        })

        assertTrue(transport.open())
        assertTrue(transport.configureAclOutput(maxPayloadLength = 2, packetCredits = 1))
        assertTrue(transport.sendAcl(
            HciAclPacket(0x12, 0x02, 0, byteArrayOf(1, 2, 3, 4, 5))
        ))
        assertTrue(awaitWriteCount(io, 1))
        assertFalse(transport.flushAcl(10))

        io.emit(HciUsbInputChannel.EVENT, completedPacketsEvent(0x12, 1))
        assertTrue(awaitWriteCount(io, 2))
        io.emit(HciUsbInputChannel.EVENT, completedPacketsEvent(0x12, 1))
        assertTrue(awaitWriteCount(io, 3))
        assertTrue(transport.flushAcl(100))

        val decoded = io.aclWrites.map { HciPacketCodec.decodeAcl(it)!! }
        assertArrayEquals(byteArrayOf(1, 2), decoded[0].payload)
        assertArrayEquals(byteArrayOf(3, 4), decoded[1].payload)
        assertArrayEquals(byteArrayOf(5), decoded[2].payload)
        assertEquals(listOf(0x02, 0x01, 0x01), decoded.map { it.packetBoundaryFlag })
        assertTrue(forwardedEvents.isEmpty())
        transport.close()
    }

    @Test
    fun failedOpenHasStableFailureAndRejectsOutput() {
        val io = FakeHciUsbIo(openResult = false)
        val failures = ArrayList<HciTransportFailure>()
        val transport = GenericHciUsbTransport(io, HciUsbAdapterProfile.CSR)
        transport.setListener(object : HciPacketListener {
            override fun onEvent(packet: HciEventPacket) = Unit
            override fun onAcl(packet: HciAclPacket) = Unit
            override fun onTransportFailure(failure: HciTransportFailure) {
                failures.add(failure)
            }
        })

        assertFalse(transport.open())
        assertEquals(HciTransportState.FAILED, transport.state)
        assertEquals(HciTransportErrorCode.INTERFACE_CLAIM_FAILED, failures.single().code)
        assertFalse(transport.sendCommand(HciCommandPacket(HciOpcodes.RESET)))
        assertTrue(io.closed)
    }

    private class FakeHciUsbIo(
        private val openResult: Boolean = true
    ) : HciUsbIo {
        private val reads = LinkedBlockingQueue<HciUsbReadResult>()
        private val closeFinished = CountDownLatch(1)
        val commandWrites = Collections.synchronizedList(ArrayList<ByteArray>())
        val aclWrites = Collections.synchronizedList(ArrayList<ByteArray>())

        @Volatile
        var closed = false
            private set
        @Volatile
        var finished = false
            private set
        @Volatile
        var finishedWhileReading = false
            private set
        @Volatile
        private var reading = false

        override fun open(): Boolean = openResult

        override fun read(): HciUsbReadResult {
            reading = true
            return try {
                reads.poll(2, TimeUnit.SECONDS) ?: HciUsbReadResult.Timeout
            } finally {
                reading = false
            }
        }

        override fun sendCommand(encodedCommand: ByteArray): Boolean {
            if (closed) return false
            commandWrites.add(encodedCommand.copyOf())
            return true
        }

        override fun sendAcl(encodedPacket: ByteArray): Boolean {
            if (closed) return false
            aclWrites.add(encodedPacket.copyOf())
            return true
        }

        override fun close() {
            closed = true
            reads.offer(HciUsbReadResult.Closed)
        }

        override fun finishClose() {
            finishedWhileReading = reading
            finished = true
            closeFinished.countDown()
        }

        fun awaitFinished(): Boolean = closeFinished.await(2, TimeUnit.SECONDS)

        fun emit(channel: HciUsbInputChannel, bytes: ByteArray) {
            reads.put(HciUsbReadResult.Packet(channel, bytes))
        }
    }

    private fun completedPacketsEvent(handle: Int, count: Int): ByteArray {
        return byteArrayOf(
            0x13, 0x05,
            0x01,
            handle.toByte(), (handle ushr 8).toByte(),
            count.toByte(), (count ushr 8).toByte()
        )
    }

    private fun awaitWriteCount(io: FakeHciUsbIo, count: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (io.aclWrites.size >= count) {
                return true
            }
            Thread.yield()
        }
        return false
    }
}
