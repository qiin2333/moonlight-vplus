package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RumbleEnvelopeAnalyzerTest {
    private class ManualClock {
        var nowMs = 0L
    }

    private fun analyzer(clock: ManualClock) = RumbleEnvelopeAnalyzer(clockMs = { clock.nowMs })

    private fun ManualClock.decompose(analyzer: RumbleEnvelopeAnalyzer, low: Float, high: Float) =
        analyzer.decompose(ControllerRumbleState(lowFrequency = low, highFrequency = high))

    @Test
    fun decompositionAlwaysSumsBackToTheInput() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)
        val samples = listOf(
            0L to (0.0f to 0.0f),
            5L to (0.9f to 0.6f),
            25L to (0.9f to 0.0f),
            45L to (0.4f to 0.8f),
            200L to (0.0f to 0.0f),
            260L to (1.0f to 1.0f),
            700L to (0.2f to 0.1f)
        )
        samples.forEach { (timeMs, channels) ->
            clock.nowMs = timeMs
            val decomposition = clock.decompose(analyzer, channels.first, channels.second)
            assertEquals(channels.first, decomposition.sustainedLow + decomposition.transientLow, 1e-4f)
            assertEquals(channels.second, decomposition.sustainedHigh + decomposition.transientHigh, 1e-4f)
            assertTrue(decomposition.sustainedLow >= 0f && decomposition.transientLow >= 0f)
            assertTrue(decomposition.sustainedHigh >= 0f && decomposition.transientHigh >= 0f)
        }
    }

    @Test
    fun zeroInputProducesZeroOutput() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)
        listOf(0L, 50L, 120L, 300L).forEach { timeMs ->
            clock.nowMs = timeMs
            val decomposition = clock.decompose(analyzer, 0f, 0f)
            assertEquals(0f, decomposition.sustainedLow, 0f)
            assertEquals(0f, decomposition.transientLow, 0f)
            assertEquals(0f, decomposition.sustainedHigh, 0f)
            assertEquals(0f, decomposition.transientHigh, 0f)
        }
    }

    @Test
    fun stepOnsetIsFullyTransientThenSettlesIntoTheEnvelope() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)

        // First sample: the envelope is at silence, so the whole step is transient.
        clock.nowMs = 0
        assertEquals(
            RumbleDecomposition(0f, 0.9f, 0f, 0.6f),
            clock.decompose(analyzer, 0.9f, 0.6f)
        )

        // 20ms later nothing has bled into the envelope yet (alpha(20ms) applies after).
        clock.nowMs = 20
        assertEquals(
            RumbleDecomposition(0f, 0.9f, 0f, 0.6f),
            clock.decompose(analyzer, 0.9f, 0.6f)
        )

        // 40ms: envelope holds 0.9 * (1 - e^-0.5) = 0.354 / 0.6 * ... = 0.236.
        clock.nowMs = 40
        val settling = clock.decompose(analyzer, 0.9f, 0.6f)
        assertEquals(0.354f, settling.sustainedLow, 0.01f)
        assertEquals(0.546f, settling.transientLow, 0.01f)
        assertEquals(0.236f, settling.sustainedHigh, 0.01f)
        assertEquals(0.364f, settling.transientHigh, 0.01f)

        // After ~8 time constants held, the step is entirely sustained.
        var timeMs = 60L
        while (timeMs <= 320) {
            clock.nowMs = timeMs
            clock.decompose(analyzer, 0.9f, 0.6f)
            timeMs += 20
        }
        val settled = clock.decompose(analyzer, 0.9f, 0.6f)
        assertTrue(settled.transientLow < 0.01f)
        assertTrue(settled.transientHigh < 0.01f)
        assertEquals(0.9f, settled.sustainedLow, 0.01f)
        assertEquals(0.6f, settled.sustainedHigh, 0.01f)
    }

    @Test
    fun slowSwellProducesNoTransientSpikes() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)
        clock.nowMs = 0
        clock.decompose(analyzer, 0f, 0f)

        // Ramp 0 -> 1.0 in 0.025 steps every 20ms (20x the time constant overall).
        var level = 0f
        var timeMs = 20L
        while (level < 1.0f) {
            level += 0.025f
            clock.nowMs = timeMs
            val decomposition = clock.decompose(analyzer, 0f, level)
            // Steady-state lag of a linear ramp is rate * tau = 0.05: a swell must never
            // read as a click.
            assertTrue(
                "transient spike ${decomposition.transientHigh} at level $level",
                decomposition.transientHigh <= 0.07f
            )
            timeMs += 20
        }

        // Let it settle, then verify the full swell landed in the sustained component.
        timeMs += 200
        clock.nowMs = timeMs
        val settled = clock.decompose(analyzer, 0f, 1.0f)
        assertTrue(settled.transientHigh < 0.07f)
        assertTrue(settled.sustainedHigh > 0.9f)
    }

    @Test
    fun pulseTrainKeepsEveryHitAtFullTransientStrength() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)

        var timeMs = 0L
        repeat(8) {
            clock.nowMs = timeMs
            val onSample = clock.decompose(analyzer, 0f, 0.8f)
            assertEquals(0.8f, onSample.transientHigh, 1e-4f)
            assertEquals(0f, onSample.sustainedHigh, 1e-4f)

            timeMs += 20
            clock.nowMs = timeMs
            val offSample = clock.decompose(analyzer, 0f, 0f)
            assertEquals(0f, offSample.transientHigh, 0f)
            assertEquals(0f, offSample.sustainedHigh, 0f)

            timeMs += 20
        }
    }

    @Test
    fun secondHitOfADoubleTapReadsAtFullTransientStrength() {
        val clock = ManualClock()
        val analyzer = analyzer(clock)

        var timeMs = 0L
        while (timeMs <= 45) {
            clock.nowMs = timeMs
            clock.decompose(analyzer, 0f, 0.9f)
            timeMs += 15
        }
        clock.nowMs = 60
        val stopped = clock.decompose(analyzer, 0f, 0f)
        assertEquals(0f, stopped.transientHigh + stopped.sustainedHigh, 0f)

        clock.nowMs = 160
        val secondHit = clock.decompose(analyzer, 0f, 0.9f)
        assertEquals(0.9f, secondHit.transientHigh, 1e-4f)
    }

    @Test
    fun unsettledTransientsFlagFollowsTheEpsilon() {
        assertFalse(RumbleDecomposition(0.5f, 0.01f, 0.5f, 0.019f).hasUnsettledTransients)
        assertTrue(RumbleDecomposition(0.5f, 0.03f, 0.5f, 0.0f).hasUnsettledTransients)
        assertTrue(RumbleDecomposition(0.5f, 0.0f, 0.5f, 0.5f).hasUnsettledTransients)
    }
}
