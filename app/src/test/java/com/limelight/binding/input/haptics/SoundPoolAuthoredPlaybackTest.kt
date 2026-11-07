package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class SoundPoolAuthoredPlaybackTest {
    @Test fun shortPointProtectsOnlyItsWaveformAndNextBodyUsesTheLatestFrequencyAndGain() {
        var now = 0L
        val assets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
        val stopped = mutableListOf<Int>()
        val sink = SoundPoolAuthoredPlayback({ assets += it; assets.size }, { stopped += it }, { now })
        assertEquals(12L, sink.render(NativeAuthoredBody.fromBands(.1f, 0f, 100f), .3f, true))
        assertEquals("mixed_4_4", assets.single().name)
        now = 5
        assertEquals(12L, sink.render(NativeAuthoredBody.fromBands(0f, .2f, 260f), 0f, true))
        assertEquals(1, assets.size)
        now = 20
        sink.render(NativeAuthoredBody.fromBands(0f, .2f, 260f), 0f, true)
        assertEquals(2, assets.size)
        assertTrue(assets.last().carrierHz > 100)
        assertEquals(0, assets.last().point)
        assertTrue(assets.last().body > assets.first().body)
        assertEquals(listOf(1), stopped)
        now = 100
        sink.render(NativeAuthoredBody.fromBands(0f, .2f, 260f), 0f, true)
        assertEquals(260, assets.last().carrierHz)
        assertEquals(1, assets.count { it.point > 0 })
    }

    @Test fun denseRainKeepsEveryFreshPointAndStopDoesNotReplayIt() {
        var now = 0L
        val assets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
        val stopped = mutableListOf<Int>()
        val sink = SoundPoolAuthoredPlayback({ assets += it; assets.size }, { stopped += it }, { now })
        repeat(40) {
            sink.render(NativeAuthoredBody.ZERO, .15f, true)
            now += if (it % 2 == 0) 10 else 15
        }
        assertEquals(40, assets.count { it.point == 2 })
        sink.stop(); sink.stop()
        assertEquals(40, stopped.size)
        sink.render(NativeAuthoredBody.ZERO, 0f, false)
        assertEquals(40, assets.size)
    }

    @Test fun continuousSweepChangesCarrierWithoutGeneratingAPoint() {
        val assets = mutableListOf<SoundPoolAuthoredPlayback.Asset>()
        var now = 0L
        val sink = SoundPoolAuthoredPlayback({ assets += it; assets.size }, {}, { now })
        for (hz in listOf(90f, 160f, 300f)) {
            sink.render(NativeAuthoredBody.fromBands(.08f, .04f, hz), 0f, true)
            now += 80
        }
        assertEquals(listOf(100, 160, 260), assets.map { it.carrierHz })
        assertTrue(assets.all { it.point == 0 })
        assertEquals(1, assets.map { it.body }.distinct().size)
        assertEquals("body_3", SoundPoolAuthoredPlayback.Asset(160, 3).name)
    }

    @Test fun badInputIsSanitizedWithoutTurningSilenceIntoAnEffect() {
        val body = NativeAuthoredBody.fromBands(Float.NaN, Float.NEGATIVE_INFINITY, Float.NaN)
        assertTrue(body.isSilent)
        assertEquals(0f, body.gain, 0f)
        assertTrue(body.frequencyHintHz.isFinite())
    }
}
