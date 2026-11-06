package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class NativeAuthoredFrequencyTest {
    @Test fun changingHintWaitsForTheCurrentFrequencyRampButNotForGainOrPoints() {
        var now = 0L
        val he = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        val sink = RichTapAuthoredHaptics({ raw, _ -> he += raw }, {}, { now }, 1, { gains += it })
        sink.render(NativeAuthoredBody.fromBands(.05f, 0f, 100f), 0f, false)
        now = 10
        assertEquals(20L, sink.render(NativeAuthoredBody.fromBands(.1f, 0f, 400f), 0f, false))
        assertEquals(1, he.size)
        assertEquals(1, gains.size)
        now = 20
        sink.render(NativeAuthoredBody.fromBands(.1f, 0f, 400f), 0f, false)
        assertEquals(2, he.size)
        now = 21
        sink.render(NativeAuthoredBody.fromBands(.1f, 0f, 100f), .03f, false)
        assertEquals(5, he.last()[15])
    }

    @Test fun roughHintJitterDoesNotRepeatedlyReplaceAContinuousVoice() {
        var now = 0L
        val he = mutableListOf<IntArray>()
        val assets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
        val richTap = RichTapAuthoredHaptics({ raw, _ -> he += raw }, {}, { now }, 1, {})
        val soundPool = SoundPoolAuthoredPlayback({ assets += it; assets.size }, {}, { now })
        for (step in 0..100) {
            now = step * 5L
            val body = NativeAuthoredBody.fromBands(.08f, .04f, if (step % 2 == 0) 140f else 180f)
            richTap.render(body, 0f, false)
            soundPool.render(body, 0f, false)
        }
        // A fluctuating ZCR hint alone should settle to one coarse timbre instead
        // of creating 100 carrier restarts; no source gain or points are discarded.
        assertTrue(he.size <= 3)
        assertEquals(1, assets.size)
        assertTrue(he.all { it[8] == 4096 && it[15] == 4 })
        assertTrue(assets.all { it.point == 0 })
    }

    @Test fun frequencySmoothingDoesNotDelayWeakPointsOrBodyGainAndResetsBetweenVoices() {
        var now = 0L
        val he = mutableListOf<IntArray>()
        val gains = mutableListOf<Int>()
        val richTap = RichTapAuthoredHaptics({ raw, _ -> he += raw }, {}, { now }, 1, { gains += it })
        richTap.render(NativeAuthoredBody.fromBands(.05f, 0f, 100f), 0f, false)
        now = 5
        richTap.render(NativeAuthoredBody.fromBands(.1f, 0f, 110f), 0f, false)
        assertEquals(1, gains.size)
        now = 10
        richTap.render(NativeAuthoredBody.fromBands(.1f, 0f, 320f), .03f, false)
        assertEquals(5, he.last()[15]) // Mixed weak point is emitted on this call.
        richTap.stop()
        now = 11
        richTap.render(NativeAuthoredBody.fromBands(0f, .1f, 260f), 0f, false)
        assertEquals(75, he.last()[13]) // Fresh voice, no inherited low carrier.
    }
}
