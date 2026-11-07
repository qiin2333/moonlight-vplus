package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import kotlin.math.sqrt
import kotlin.math.exp
import kotlin.math.ln

/** Continuous source features, not motor commands. Keep both RMS bands until rendering.
 * The legacy 40 ms sign-transition rate supplies a frequency *hint*, not a pitch estimate
 * for noise or mixtures. No normalization of quiet passages to full scale is performed.
 */
@ConsistentCopyVisibility
internal data class NativeAuthoredBody private constructor(
    val lowRms: Float, val highRms: Float, val frequencyHintHz: Float
) {
    val gain: Float get() = sqrt(2f * (lowRms * lowRms + highRms * highRms)).coerceAtMost(1f)
    val isSilent: Boolean get() = lowRms == 0f && highRms == 0f

    companion object {
        val ZERO = fromBands(0f, 0f)
        fun fromBands(low: Float, high: Float, frequencyHintHz: Float = 0f): NativeAuthoredBody {
            val l = ControllerRumbleState.normalizeAmplitude(low)
            val h = ControllerRumbleState.normalizeAmplitude(high)
            val energy = l * l + h * h
            val fallback = if (energy > 0f) (100f * l * l + 260f * h * h) / energy else 160f
            val hint = if (frequencyHintHz.isFinite() && frequencyHintHz > 0f)
                frequencyHintHz.coerceIn(50f, 400f) else fallback
            return NativeAuthoredBody(l, h, hint)
        }

        fun frequencyHint(frame: Ds5HapticsIrExtendedFrame): Float {
            var weighted = 0f
            var weight = 0f
            for ((body, base) in listOf(frame.left to frame.base.left, frame.right to frame.base.right)) {
                val l = ControllerRumbleState.normalizeAmplitude(body.continuousLow)
                val h = ControllerRumbleState.normalizeAmplitude(body.continuousHigh)
                val energy = l * l + h * h
                // There are two sign transitions per cycle of a sinusoid.
                if (base.zeroCrossingRate.isFinite() && base.zeroCrossingRate > 0f) {
                    weighted += energy * (base.zeroCrossingRate / 2f).coerceIn(50f, 400f)
                    weight += energy
                }
            }
            return if (weight > 0f) weighted / weight else 0f
        }
    }
}

/** Presentation state only: ZCR is a rough texture hint, not an instantaneous carrier.
 * Smooth proportional frequency changes over 40 ms before backend quantization. This
 * never delays/filters the source gain or event stream. Reset at a voice boundary so
 * a new effect does not inherit the preceding effect's timbre.
 */
internal class NativeAuthoredFrequency {
    private var logHz = 0.0
    private var lastMs: Long? = null

    fun advance(hintHz: Float, nowMs: Long): Float {
        val target = ln(hintHz.coerceIn(50f, 400f).toDouble())
        val previous = lastMs
        logHz = if (previous == null || nowMs < previous) target else
            logHz + (1.0 - exp(-(nowMs - previous) / 40.0)) * (target - logHz)
        lastMs = nowMs
        return exp(logHz).toFloat()
    }

    fun reset() { lastMs = null }
}
