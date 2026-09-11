package com.limelight.binding.input.haptics

import android.os.SystemClock
import kotlin.math.exp

/**
 * Per-channel split of a rumble level into a slowly tracking envelope (sustained) and a
 * positive fast-rise residual (transient):  T = max(0, x - LPF[x]),  B = x - T.
 *
 * This is an intensity-envelope decomposition, NOT an audio crossover: the low-pass runs on the
 * rumble LEVEL over time (time constant ~40ms), so a sharp onset reads as transient while a
 * slow swell or a held level reads as sustained. T + B always equals the input and zero input
 * yields zero on both components, so nothing synthetic is ever appended after the signal ends.
 *
 * The analyzer is event-driven but time-aware: the envelope only advances when [decompose]
 * runs, so the coordinator re-runs it on a short ticker while transients are converging.
 */
internal class RumbleEnvelopeAnalyzer(
    private val timeConstantMs: Float = DEFAULT_TIME_CONSTANT_MS,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var lastSampleMs: Long? = null
    private var lowEnvelope = 0f
    private var highEnvelope = 0f

    fun decompose(input: ControllerRumbleState): RumbleDecomposition {
        val nowMs = clockMs()
        val dtMs = lastSampleMs
            ?.let { (nowMs - it).coerceIn(0L, MAX_TRACKING_GAP_MS).toFloat() }
            ?: 0f
        lastSampleMs = nowMs
        val alpha = 1f - exp(-dtMs / timeConstantMs)

        // Residuals are read before the envelope update: a step onset must read as a full
        // transient first and only then bleed into the envelope over ~tau.
        val transientLow = positiveResidual(input.lowFrequency, lowEnvelope)
        val transientHigh = positiveResidual(input.highFrequency, highEnvelope)

        if (input.isZero) {
            // An explicit zero is authoritative silence: drop the tracked history so the next
            // onset reads at full transient strength. The second hit of a double-tap must not
            // be softened by the first hit's residual envelope.
            lowEnvelope = 0f
            highEnvelope = 0f
        } else {
            lowEnvelope += alpha * (input.lowFrequency - lowEnvelope)
            highEnvelope += alpha * (input.highFrequency - highEnvelope)
        }

        return RumbleDecomposition(
            sustainedLow = input.lowFrequency - transientLow,
            transientLow = transientLow,
            sustainedHigh = input.highFrequency - transientHigh,
            transientHigh = transientHigh
        )
    }

    private fun positiveResidual(value: Float, envelope: Float): Float =
        (value - envelope).coerceAtLeast(0f)

    companion object {
        // Experimental starting point from the coordinated-vibration design notes: it
        // separates "sharp onset" from "slow swell" and is a tuning knob, not a hardware
        // constant.
        const val DEFAULT_TIME_CONSTANT_MS = 40f
        private const val MAX_TRACKING_GAP_MS = 500L
    }
}

/** Envelope/transient split of one host rumble state, both channels in [0, 1]. */
internal data class RumbleDecomposition(
    val sustainedLow: Float,
    val transientLow: Float,
    val sustainedHigh: Float,
    val transientHigh: Float
) {
    /** True while transient residuals are still decaying into the envelope and need re-routing. */
    val hasUnsettledTransients: Boolean
        get() = transientLow > TRANSIENT_EPSILON || transientHigh > TRANSIENT_EPSILON

    companion object {
        const val TRANSIENT_EPSILON = 0.02f
    }
}
