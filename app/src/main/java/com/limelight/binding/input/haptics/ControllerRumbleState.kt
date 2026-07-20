package com.limelight.binding.input.haptics

/**
 * Platform-neutral controller rumble amplitudes.
 *
 * Values are normalized to [0, 1]. Invalid floating-point values are treated as zero. Trigger
 * amplitudes are optional for callers because they default to zero; sinks may ignore them when the
 * target controller does not expose trigger motors.
 */
class ControllerRumbleState(
    lowFrequency: Float = 0f,
    highFrequency: Float = 0f,
    leftTrigger: Float = 0f,
    rightTrigger: Float = 0f
) {
    val lowFrequency: Float = normalizeAmplitude(lowFrequency)
    val highFrequency: Float = normalizeAmplitude(highFrequency)
    val leftTrigger: Float = normalizeAmplitude(leftTrigger)
    val rightTrigger: Float = normalizeAmplitude(rightTrigger)

    val isZero: Boolean
        get() = lowFrequency == 0f &&
            highFrequency == 0f &&
            leftTrigger == 0f &&
            rightTrigger == 0f

    internal fun scaled(gain: Float): ControllerRumbleState {
        val normalizedGain = normalizeAmplitude(gain)
        return ControllerRumbleState(
            lowFrequency * normalizedGain,
            highFrequency * normalizedGain,
            leftTrigger * normalizedGain,
            rightTrigger * normalizedGain
        )
    }

    internal fun maxWith(other: ControllerRumbleState): ControllerRumbleState =
        ControllerRumbleState(
            maxOf(lowFrequency, other.lowFrequency),
            maxOf(highFrequency, other.highFrequency),
            maxOf(leftTrigger, other.leftTrigger),
            maxOf(rightTrigger, other.rightTrigger)
        )

    override fun equals(other: Any?): Boolean =
        other is ControllerRumbleState &&
            lowFrequency == other.lowFrequency &&
            highFrequency == other.highFrequency &&
            leftTrigger == other.leftTrigger &&
            rightTrigger == other.rightTrigger

    override fun hashCode(): Int {
        var result = lowFrequency.hashCode()
        result = 31 * result + highFrequency.hashCode()
        result = 31 * result + leftTrigger.hashCode()
        result = 31 * result + rightTrigger.hashCode()
        return result
    }

    override fun toString(): String =
        "ControllerRumbleState(lowFrequency=$lowFrequency, highFrequency=$highFrequency, " +
            "leftTrigger=$leftTrigger, rightTrigger=$rightTrigger)"

    companion object {
        val ZERO = ControllerRumbleState()

        private fun normalizeAmplitude(value: Float): Float = when {
            !value.isFinite() || value <= 0f -> 0f
            value >= 1f -> 1f
            else -> value
        }
    }
}
