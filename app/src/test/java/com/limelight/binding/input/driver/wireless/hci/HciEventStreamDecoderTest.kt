package com.limelight.binding.input.driver.wireless.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HciEventStreamDecoderTest {
    @Test
    fun reassemblesSplitEventsAndDecodesCoalescedEvents() {
        val first = byteArrayOf(0x0e, 0x04, 0x01, 0x03, 0x0c, 0x00)
        val second = byteArrayOf(0x0f, 0x04, 0x00, 0x01, 0x05, 0x04)
        val decoder = HciEventStreamDecoder()

        assertTrue(decoder.append(first.copyOfRange(0, 3)).isEmpty())
        assertEquals(3, decoder.pendingByteCount())

        val decoded = decoder.append(first.copyOfRange(3, first.size) + second)
        assertEquals(2, decoded.size)
        assertEquals(0x0e, decoded[0].eventCode)
        assertArrayEquals(byteArrayOf(0x01, 0x03, 0x0c, 0x00), decoded[0].parameters)
        assertEquals(0x0f, decoded[1].eventCode)
        assertEquals(0, decoder.pendingByteCount())
    }
}
