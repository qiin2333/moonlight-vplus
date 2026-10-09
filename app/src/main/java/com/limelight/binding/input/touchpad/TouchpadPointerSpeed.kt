package com.limelight.binding.input.touchpad

import com.limelight.preferences.PreferenceConfiguration

/**
 * Device testing shows the host moves the pointer farther when the reported
 * touchpad is larger. Growing that size with the slider makes the same swipe
 * travel farther. 100% leaves the size unchanged.
 */
object TouchpadPointerSpeed {
    fun scaledSizeMm(sizeMm: Int, speedPercent: Int): Int {
        val bounded = speedPercent.coerceIn(
            PreferenceConfiguration.MIN_HARDWARE_TOUCHPAD_POINTER_SPEED,
            PreferenceConfiguration.MAX_HARDWARE_TOUCHPAD_POINTER_SPEED
        )
        if (bounded == PreferenceConfiguration.DEFAULT_HARDWARE_TOUCHPAD_POINTER_SPEED || sizeMm <= 0) {
            return sizeMm
        }
        return (sizeMm.toLong() * bounded / PreferenceConfiguration.DEFAULT_HARDWARE_TOUCHPAD_POINTER_SPEED)
            .toInt()
            .coerceAtLeast(1)
    }

    /**
     * Non-takeover touchpads already arrive as relative mouse counts, so the
     * same percentage scales those counts directly. Fractional counts are kept
     * until they add up to a whole count.
     */
    fun scaleRelativeDelta(delta: Int, remainder: Float, speedPercent: Int): Pair<Int, Float> {
        val bounded = speedPercent.coerceIn(
            PreferenceConfiguration.MIN_HARDWARE_TOUCHPAD_POINTER_SPEED,
            PreferenceConfiguration.MAX_HARDWARE_TOUCHPAD_POINTER_SPEED
        )
        val scaled = delta * (bounded / 100f) +
            if (bounded == PreferenceConfiguration.DEFAULT_HARDWARE_TOUCHPAD_POINTER_SPEED) 0f else remainder
        val whole = scaled.toInt()
        return whole to (scaled - whole)
    }
}
