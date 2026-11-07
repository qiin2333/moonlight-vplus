package com.limelight.binding.input.haptics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Testable scheduling for the finite audio-haptic bank; no Android calls or clocks here. */
internal class SoundPoolAuthoredPlayback(
    private val play: (Asset) -> Int,
    private val stopStream: (Int) -> Unit,
    private val clockMs: () -> Long
) {
    data class Asset(val carrierHz: Int, val body: Int, val point: Int = 0) {
        val name: String get() {
            val suffix = if (body == 0 || carrierHz == 160) "" else "${carrierHz}_"
            return if (point == 0) "body_$suffix$body" else "mixed_$suffix${body}_$point"
        }
    }
    private var stream = 0
    private var asset: Asset? = null
    private var renewAt = 0L
    private var attackUntil = 0L
    private val textureFrequency = NativeAuthoredFrequency()

    fun render(body: NativeAuthoredBody, point: Float, keepWarm: Boolean): Long? {
        val now = clockMs()
        val bodyLevel = level(body.gain, BODY_LEVELS)
        val pointLevel = level(point, POINT_LEVELS)
        val hz = carrier(textureFrequency.advance(body.frequencyHintHz, now), asset?.carrierHz)
        if (pointLevel > 0) {
            replace(Asset(hz, bodyLevel, pointLevel))
            attackUntil = now + ATTACK_MS
            return attackUntil
        }
        // Protect only the compact waveform and codec tail, not the old 180 ms lease.
        // The worker owns the latest body; it resubmits it at this deadline.
        if (now < attackUntil) return attackUntil
        if (bodyLevel == 0 && !keepWarm) { stop(); return null }
        val target = Asset(if (bodyLevel == 0) asset?.carrierHz ?: 160 else hz, bodyLevel)
        if (target != asset || now >= renewAt) {
            replace(target)
            renewAt = now + BODY_RENEW_MS
        }
        return renewAt
    }

    private fun replace(next: Asset) {
        val nextStream = play(next)
        check(nextStream != 0) { "SoundPool rejected native haptics" }
        if (stream != 0) stopStream(stream)
        stream = nextStream
        asset = next
    }

    fun stop() {
        if (stream != 0) stopStream(stream)
        stream = 0
        asset = null
        renewAt = 0
        attackUntil = 0
        textureFrequency.reset()
    }

    companion object {
        val CARRIERS = listOf(100, 160, 260)
        const val BODY_LEVELS = 16
        const val POINT_LEVELS = 8
        const val ATTACK_MS = 12L
        private const val BODY_RENEW_MS = 750L
        private fun level(value: Float, levels: Int) =
            (ControllerRumbleState.normalizeAmplitude(value) / .6f * levels).roundToInt().coerceIn(0, levels)

        internal fun carrier(hz: Float, previous: Int? = null): Int {
            val hint = hz.coerceIn(50f, 400f)
            val next = CARRIERS.minBy { abs(ln(hint / it)) }
            // Ten percent hysteresis around geometric boundaries, not a time/rate gate.
            return if (previous != null && previous in CARRIERS &&
                abs(ln(hint / previous)) <= abs(ln(hint / next)) + .1f) previous else next
        }
    }
}
