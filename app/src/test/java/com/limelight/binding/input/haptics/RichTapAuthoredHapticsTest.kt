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

    @Test fun compactMixedPointDoesNotBlockChangingBackgroundOrReplayThePoint() {
        val packets = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        val updates = mutableListOf<Int>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ raw, gain -> packets += raw; gains += gain }, {}, { now }, 7, { updates += it })
        sink.render(.15f, 0f, false)
        now = 20
        assertEquals(32L, sink.render(.15f, .12f, false))
        assertEquals(listOf(50, 60, 50, 50, 50), packets.last().drop(16).chunked(3).map { it[1] })
        now = 25
        assertEquals(32L, sink.render(.21f, 0f, false))
        assertEquals(2, packets.size) // Actual short waveform is still protected.
        now = 40
        sink.render(.21f, 0f, false)
        assertEquals(3, packets.size)
        assertEquals(100, packets.last()[17]) // No new zero-to-peak envelope.
        assertTrue(gains.last() > gains.first())
        now = 60
        sink.render(.06f, 0f, false)
        assertEquals(3, packets.size)
        assertTrue(updates.single() < gains.first())
        assertEquals(1, packets.count { it[15] == 5 })
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

    @Test fun pointDoesNotDuckTheBackgroundForOneAndAHalfSeconds() {
        var now = 0L
        val packets = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        val sink = RichTapAuthoredHaptics({ raw, gain -> packets += raw; gains += gain }, {}, { now }, 1, {})
        sink.render(.3f, 0f, false)
        now = 20
        sink.render(.3f, .12f, false)
        assertTrue(packets.last().drop(16).chunked(3).all { it[1] == 100 })
        // Saturation may mask an added point at full scale. Do not invent a lasting
        // background dip to make it conspicuous: fidelity takes priority over enhancement.
        now = 40
        sink.render(.3f, 0f, false)
        assertEquals(255, gains.last().toInt())
    }

    @Test fun equalEnergySweepChangesFrequencyWithoutInventingShortPoints() {
        val packets = mutableListOf<IntArray>()
        var now = 0L
        val sink = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { now }, 1, {})
        for (hz in listOf(90f, 150f, 210f, 320f)) {
            sink.render(NativeAuthoredBody.fromBands(.05f, .05f, hz), 0f, false)
            now += 20
        }
        assertTrue(packets.size in 3..4)
        assertTrue(packets.last()[13] - packets.first()[13] >= 20)
        assertTrue(packets.all { it[8] == 4096 && it[15] == 4 })
        assertTrue(packets.zipWithNext().all { (a, b) -> b[13] > a[13] })
        assertTrue(packets.drop(1).all { it[17] == 100 && it[18] < 0 && it[19] == 20 })
    }

    @Test fun newBackgroundDoesNotWaitForTheIsolatedPresetCleanupTail() {
        var now = 0L
        val packets = mutableListOf<IntArray>()
        val sink = RichTapAuthoredHaptics({ raw, _ -> packets += raw }, {}, { now }, 1, {})
        sink.render(0f, .12f, false)
        now = 20
        sink.render(.15f, 0f, false)
        assertEquals(listOf(4097, 4096), packets.map { it[8] })
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
