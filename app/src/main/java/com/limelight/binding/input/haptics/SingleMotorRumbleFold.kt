package com.limelight.binding.input.haptics

/**
 * Single owner of the two-motor -> single-motor fold shared by every single-vibrator sink
 * (the phone body and single-vibrator controllers).
 *
 * Routing gains in [GameRumbleRouter] are applied to the two channels before this fold, so the
 * effective body amplitude is exactly fold(router output) x strength. Nothing downstream of this
 * fold may apply channel weights again; that double application is how the old chain silently
 * turned "20% low + 100% high" into 0.16 low + 0.33 high.
 */
internal object SingleMotorRumbleFold {
    const val LOW_CHANNEL_WEIGHT = 0.80f
    const val HIGH_CHANNEL_WEIGHT = 0.33f

    /** Channels are normalized to [0, 1]; the result is a 0-255 amplitude. */
    fun amplitude(lowFrequency: Float, highFrequency: Float): Int {
        val mixed = lowFrequency.coerceIn(0f, 1f) * LOW_CHANNEL_WEIGHT +
            highFrequency.coerceIn(0f, 1f) * HIGH_CHANNEL_WEIGHT
        return (mixed * 255f).toInt().coerceIn(0, 255)
    }

    fun amplitude(lowMotor: Short, highMotor: Short): Int {
        val lowMotorMsb = (lowMotor.toInt() and 0xFFFF) ushr 8
        val highMotorMsb = (highMotor.toInt() and 0xFFFF) ushr 8
        val mixed = lowMotorMsb * LOW_CHANNEL_WEIGHT + highMotorMsb * HIGH_CHANNEL_WEIGHT
        return mixed.toInt().coerceIn(0, 255)
    }
}
