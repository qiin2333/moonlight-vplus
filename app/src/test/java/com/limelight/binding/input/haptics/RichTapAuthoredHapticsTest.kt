package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class RichTapAuthoredHapticsTest {
    @Test fun isolatedPointsRetainGradedNativeTransients() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ packets += it }, {}, { now }, 42)
        for (gain in listOf(.06f, .15f, 1f)) {
            sink.render(0f, gain, false)
            now += 100
        }
        assertEquals(listOf(20, 50, 100), packets.map { it[12] })
        assertTrue(packets.all { it.size == 15 && it[8] == 4097 && it[14] == 0 })
        assertTrue(packets.map { it[3] }.distinct().size == 3)
        assertEquals(42, packets.first()[2])
    }

    @Test fun freshPointsBypassTheHoldAndSilenceCancelsOnlyOwnedOutput() {
        var now = 0L
        var writes = 0
        var stops = 0
        val sink = RichTapAuthoredHaptics({ writes++ }, { stops++ }, { now }, 1)
        assertEquals(40L, sink.render(0f, .15f, false))
        now = 20
        assertEquals(60L, sink.render(0f, .2f, false))
        now = 40
        assertEquals(60L, sink.render(0f, 0f, true))
        assertEquals(2, writes)
        assertEquals(0, stops)
        now = 60
        sink.render(Float.NaN, Float.NEGATIVE_INFINITY, true)
        assertEquals(1, stops)
        sink.close()
        assertFalse(sink.ready)
        sink.render(0f, .3f, false)
        assertEquals(2, writes)
    }

    @Test fun rejectedVendorWriteDisablesNativeRoutingAndCancelsPartialPlayback() {
        var stops = 0
        val sink = RichTapAuthoredHaptics({ throw IllegalStateException("vendor rejected") }, { stops++ }, { 0L }, 1)
        assertNull(sink.render(0f, .1f, false))
        assertFalse(sink.ready)
        assertEquals(1, stops)
        assertNull(sink.render(0f, .1f, false))
    }
}
