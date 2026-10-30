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
    @Test fun steadyBodyIsRenewedWithoutQueuingOrCancellingEachInputUpdate() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        var stops = 0
        val sink = RichTapAuthoredHaptics({ packets += it }, { stops++ }, { now }, 7)
        assertEquals(4000L, sink.render(.15f, 0f, false))
        for (time in 20L..3980L step 20L) {
            now = time
            sink.render(if (time % 40 == 0L) .158f else .161f, 0f, false)
        }
        assertEquals(1, packets.size)
        assertEquals(4096, packets.single()[8])
        assertEquals(5000, packets.single()[14])
        now = 4000
        sink.render(.15f, 0f, false)
        assertEquals(2, packets.size)
        assertEquals(50, packets.last()[17])
        assertEquals(0, stops)
        now = 4010
        sink.render(0f, 0f, false)
        sink.stop()
        assertEquals(1, stops)
        now = 4020
        sink.render(.15f, 0f, false)
        assertEquals(0, packets.last()[17])
    }

    @Test fun mixedEnvelopePreservesTheBodyAndProtectsFreshPointsFromBodyRefresh() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ packets += it }, {}, { now }, 7)
        sink.render(.15f, 0f, false)
        now = 20
        assertEquals(120L, sink.render(.15f, .12f, false))
        val mixed = packets.last()
        assertEquals(4096, mixed[8])
        assertEquals(5, mixed[15])
        val levels = mixed.drop(16).chunked(3).map { it[1] }
        assertEquals(listOf(50, 90, 50, 50, 50), levels)
        now = 40
        sink.render(.21f, 0f, false)
        assertEquals(2, packets.size)
        now = 60
        sink.render(.21f, .09f, false)
        assertEquals(3, packets.size)
        assertEquals(100, packets.last()[20])
        now = 160
        sink.render(.06f, 0f, false)
        assertEquals(4, packets.size)
        assertEquals(20, packets.last()[20])
    }

    @Test fun expiredCarrierStartsFreshAndSilenceResetsItsState() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ packets += it }, {}, { now }, 7)
        sink.render(.15f, 0f, false)
        now = 6000
        sink.render(.21f, 0f, false)
        assertEquals(0, packets.last()[17])
        sink.render(Float.NaN, Float.NEGATIVE_INFINITY, false)
        assertTrue(sink.ready)
        now += 20
        sink.render(.15f, 0f, false)
        assertEquals(0, packets.last()[17])
    }

}
