package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class RichTapAuthoredHapticsTest {
    @Test fun isolatedPointsRetainGradedNativeTransients() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { now }, 42, {})
        for (gain in listOf(.06f, .15f, 1f)) {
            sink.render(0f, gain, false)
            now += 100
        }
        assertEquals(listOf(10, 25, 100), packets.map { it[12] })
        assertTrue(packets.all { it.size == 15 && it[8] == 4097 && it[14] == 0 })
        assertTrue(packets.map { it[3] }.distinct().size == 3)
        assertEquals(42, packets.first()[2])
    }

    @Test fun freshPointsBypassTheHoldAndSilenceCancelsOnlyOwnedOutput() {
        var now = 0L
        var writes = 0
        var stops = 0
        val sink = RichTapAuthoredHaptics({ _, _ -> writes++ }, { stops++ }, { now }, 1, {})
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
        val sink = RichTapAuthoredHaptics({ _, _ -> throw IllegalStateException("vendor rejected") }, { stops++ }, { 0L }, 1, {})
        assertNull(sink.render(0f, .1f, false))
        assertFalse(sink.ready)
        assertEquals(1, stops)
        assertNull(sink.render(0f, .1f, false))
    }
    @Test fun steadyBodyIsRenewedWithoutQueuingOrCancellingEachInputUpdate() {
        val packets = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        var now = 0L
        var stops = 0
        val sink = RichTapAuthoredHaptics({ raw, gain -> packets += raw; gains += gain }, { stops++ }, { now }, 7, {})
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
        assertEquals(gains.first(), gains.last())
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
        val gains = mutableListOf<Int>()
        val updates = mutableListOf<Int>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ raw, gain -> packets += raw; gains += gain }, {}, { now }, 7, { updates += it })
        sink.render(.15f, 0f, false)
        now = 20
        assertEquals(120L, sink.render(.15f, .12f, false))
        val mixed = packets.last()
        assertEquals(4096, mixed[8])
        assertEquals(5, mixed[15])
        val levels = mixed.drop(16).chunked(3).map { it[1] }
        assertEquals(listOf(50, 60, 50, 50, 50), levels)
        now = 40
        sink.render(.21f, 0f, false)
        assertEquals(2, packets.size)
        assertTrue(updates.isEmpty())
        now = 60
        sink.render(.21f, .09f, false)
        assertEquals(3, packets.size)
        assertEquals(75, packets.last()[20])
        now = 160
        sink.render(.06f, 0f, false)
        assertEquals(4, packets.size)
        assertTrue(gains.last() < gains.first())
        now = 180
        sink.render(.15f, 0f, false)
        assertEquals(4, packets.size)
        assertEquals(gains.first(), updates.single())
    }

    @Test fun expiredCarrierStartsFreshAndSilenceResetsItsState() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { now }, 7, {})
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

    @Test fun fullBackgroundReservesGradedPointHeadroomAndThenRecovers() {
        var now = 0L
        val packets = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        val sink = RichTapAuthoredHaptics({ raw, gain -> packets += raw; gains += gain }, {}, { now }, 1, {})
        sink.render(.3f, 0f, false)
        val peaks = mutableListOf<Int>()
        for (point in listOf(.12f, .3f, .6f)) {
            now += 300
            sink.render(.3f, point, false)
            val levels = packets.last().drop(16).chunked(3).map { it[1] }
            assertEquals(70, levels[0])
            assertTrue(levels[1] > levels[0])
            assertEquals(70, levels[2])
            assertEquals(70, levels[3])
            peaks += levels[1]
        }
        assertEquals(listOf(76, 85, 100), peaks)
        now += 1500
        sink.render(.3f, 0f, false)
        assertEquals(4, packets.last()[15])
        assertEquals(100, packets.last()[20])
        assertEquals(255, gains.last().toInt())
    }

    @Test fun parameterCarrierTracksAShortBodyPulseWithoutReplacingItsEnvelope() {
        var now = 0L
        val starts = mutableListOf<Int>()
        val updates = mutableListOf<Int>()
        val sink = RichTapAuthoredHaptics(
            { raw, gain ->
                assertEquals(4, raw[15])
                assertEquals(0, raw[17])
                starts += gain
            }, {}, { now }, 1, { updates += it })
        sink.render(.03f, 0f, false)
        for ((time, body) in listOf(20L to .10f, 40L to .20f, 60L to .10f, 80L to .03f)) {
            now = time
            sink.render(body, 0f, false)
        }
        assertEquals(1, starts.size)
        assertEquals(4, updates.size)
        assertTrue(updates[0] > starts.single())
        assertTrue(updates[1] > updates[0])
        assertEquals(updates[0], updates[2])
        assertEquals(starts.single(), updates[3])
    }

    @Test fun aQuietImpactTailDoesNotStartACarrierOrChangeTheNextPointIntoAContinuousEffect() {
        var now = 0L
        val packets = mutableListOf<IntArray>()
        val sink = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { now }, 1,
            { fail("Unexpected tail update") })
        sink.render(.009f, 0f, false)
        sink.render(.009f, .12f, false)
        now = 100
        sink.render(.009f, .12f, false)
        assertEquals(listOf(4097, 4097), packets.map { it[8] })
    }

}
