package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciAclOutputSchedulerTest {
    @Test
    fun fragmentsAtNegotiatedMtuAndWaitsForCompletedPacketCredits() {
        val sent = ArrayList<HciAclPacket>()
        val scheduler = HciAclOutputScheduler()
        assertTrue(scheduler.configure(maxPayloadLength = 3, packetCredits = 2))

        val result = scheduler.enqueue(
            HciAclPacket(
                connectionHandle = 0x012,
                packetBoundaryFlag = 0x02,
                broadcastFlag = 0,
                payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            )
        ) { packet -> sent.add(packet); true }

        assertTrue(result.accepted)
        assertFalse(result.transferFailed)
        assertEquals(2, sent.size)
        assertEquals(0x02, sent[0].packetBoundaryFlag)
        assertEquals(0x01, sent[1].packetBoundaryFlag)
        assertArrayEquals(byteArrayOf(1, 2, 3), sent[0].payload)
        assertArrayEquals(byteArrayOf(4, 5, 6), sent[1].payload)
        assertEquals(1, scheduler.queuedPacketCount())
        assertEquals(0, scheduler.availablePacketCredits())

        val completion = scheduler.onEvent(
            completedPacketsEvent(handle = 0x012, count = 2)
        ) { packet -> sent.add(packet); true }
        assertEquals(HciAclCompletionEventResult.HANDLED, completion)
        assertEquals(3, sent.size)
        assertEquals(0x01, sent[2].packetBoundaryFlag)
        assertArrayEquals(byteArrayOf(7, 8), sent[2].payload)
        assertEquals(0, scheduler.queuedPacketCount())
        assertEquals(1, scheduler.availablePacketCredits())
    }

    @Test
    fun rejectsOverflowWithoutPartiallyQueueingAndValidatesCompletionEvent() {
        val scheduler = HciAclOutputScheduler(maxQueuedPackets = 2)
        assertTrue(scheduler.configure(maxPayloadLength = 2, packetCredits = 1))

        val rejected = scheduler.enqueue(
            HciAclPacket(1, 2, 0, ByteArray(6))
        ) { true }
        assertFalse(rejected.accepted)
        assertEquals(0, scheduler.queuedPacketCount())

        assertEquals(
            HciAclCompletionEventResult.MALFORMED,
            scheduler.onEvent(HciEventPacket(0x13, byteArrayOf(0x01, 0x01))) { true }
        )
        assertEquals(
            HciAclCompletionEventResult.NOT_HANDLED,
            scheduler.onEvent(HciEventPacket(0xff, ByteArray(0))) { true }
        )
    }

    private fun completedPacketsEvent(handle: Int, count: Int): HciEventPacket {
        return HciEventPacket(
            eventCode = 0x13,
            parameters = byteArrayOf(
                0x01,
                handle.toByte(), (handle ushr 8).toByte(),
                count.toByte(), (count ushr 8).toByte()
            )
        )
    }
}
