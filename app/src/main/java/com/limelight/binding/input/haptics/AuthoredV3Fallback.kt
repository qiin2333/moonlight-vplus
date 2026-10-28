package com.limelight.binding.input.haptics

import kotlin.math.sqrt

/** The ordinary-motor fallback needs the same perceptual makeup as the v2 fallback.
 * Raw onset levels otherwise disappear when the phone's weighted 8-bit amplitude is quantized.
 * Preset TICK and continuous body keep their own rendering semantics.
 */
internal object AuthoredV3Fallback {
    fun transientAmplitude(strength: Float): Float =
        if (strength.isFinite()) sqrt(strength.coerceIn(0f, 1f)) else 0f
}
