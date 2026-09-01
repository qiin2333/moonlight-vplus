package com.limelight.binding.input

/**
 * Detects controller gyro implementations that advertise a sensor and emit callbacks,
 * but never provide any axis data. Some controller clones expose a phantom Android
 * gyroscope whose samples remain exactly zero while timestamps continue advancing.
 */
internal class ControllerGyroLivenessTracker(
    private val zeroSampleLimit: Int = DEFAULT_ZERO_SAMPLE_LIMIT,
) {
    private var consecutiveZeroSamples = 0
    private var fallbackRequested = false

    @Synchronized
    fun onSample(x: Float, y: Float, z: Float): Boolean {
        if (fallbackRequested) return false

        if (x != 0f || y != 0f || z != 0f) {
            consecutiveZeroSamples = 0
            return false
        }

        consecutiveZeroSamples++
        if (consecutiveZeroSamples < zeroSampleLimit) return false

        fallbackRequested = true
        return true
    }

    @Synchronized
    fun reset() {
        consecutiveZeroSamples = 0
        fallbackRequested = false
    }

    companion object {
        // A real gyroscope has sensor noise even while stationary. Requiring a full
        // second of bit-for-bit zero samples avoids reacting to a short quiet period.
        private const val DEFAULT_ZERO_SAMPLE_LIMIT = 120
    }
}
