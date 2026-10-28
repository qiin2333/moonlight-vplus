package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrFrame
import kotlin.math.sqrt

/** Source-time onset extraction. A sustained texture never becomes a free-running tick train. */
internal class AuthoredHapticsRenderer {
    data class Output(
        val rumble: ControllerRumbleState,
        val body: ControllerRumbleState,
        val tick: Boolean,
        val ended: Boolean
    ) {
        fun usesDeviceDetails(mode: GameRumbleMode, hasController: Boolean, hasDevice: Boolean,
                              supportsTick: Boolean): Boolean =
            hasDevice && supportsTick && mode != GameRumbleMode.CONTROLLER &&
                (body.isZero || mode == GameRumbleMode.COORDINATED && hasController)
    }

    private var lastTimeUs: Long? = null
    private var lastSequence: Int? = null
    private var attackActive = false
    private var lastOnsetUs: Long? = null

    fun render(frame: Ds5HapticsIrFrame): Output? {
        val previousTime = lastTimeUs
        if (lastSequence == frame.sequenceNumber && previousTime != null &&
            frame.presentationTimeUs <= previousTime && !frame.ended
        ) return null
        if (frame.flags and Ds5HapticsIrFrame.FLAG_DISCONTINUITY != 0 ||
            previousTime != null && (frame.presentationTimeUs < previousTime ||
                frame.presentationTimeUs - previousTime > 100_000L)
        ) {
            attackActive = false
            lastOnsetUs = null
        }
        lastTimeUs = frame.presentationTimeUs
        lastSequence = frame.sequenceNumber
        if (frame.ended || frame.silent) {
            attackActive = false
            if (frame.ended) lastOnsetUs = null
            return Output(ControllerRumbleState.ZERO, ControllerRumbleState.ZERO, false, frame.ended)
        }

        var low = 0f
        var high = 0f
        var peak = 0f
        var rms = 0f
        var attack = 0f
        for (lane in arrayOf(frame.left, frame.right)) {
            val level = normalized(lane.rms)
            val ratio = normalized(lane.lowBandRatio)
            low = maxOf(low, level * sqrt(ratio))
            high = maxOf(high, level * sqrt(1f - ratio))
            peak = maxOf(peak, normalized(lane.peak))
            rms = maxOf(rms, level)
            attack = maxOf(attack, normalized(lane.transient))
        }
        val isAttack = attack >= 0.025f && peak >= 0.015f
        val onsetTime = lastOnsetUs
        val tick = isAttack && !attackActive &&
            (onsetTime == null || frame.presentationTimeUs - onsetTime >= 35_000L)
        attackActive = isAttack
        if (tick) lastOnsetUs = frame.presentationTimeUs

        // Controller-only and unsupported-device routes retain an ordinary rumble fallback.
        val rumble = ControllerRumbleState(sqrt(low), sqrt(maxOf(high, peak * attack)))
        // Weak upper-band details belong to the crisp device sink, not the large ERM motor.
        // These are signal features, not a semantic "rain detector".
        val fineTexture = rms < 0.18f && high > low
        val body = if (fineTexture) ControllerRumbleState.ZERO else ControllerRumbleState(low, high * 0.45f)
        return Output(rumble, body, tick, false)
    }

    private fun normalized(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
