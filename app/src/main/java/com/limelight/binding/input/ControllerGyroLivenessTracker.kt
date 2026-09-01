package com.limelight.binding.input

/**
 * Detects controller gyro implementations that advertise a sensor and emit callbacks,
 * but never provide any axis data. Some controller clones expose a phantom Android
 * gyroscope whose samples remain exactly zero while timestamps continue advancing.
 */
internal class ControllerGyroLivenessTracker(
    private val zeroDurationNanos: Long = DEFAULT_ZERO_DURATION_NANOS,
) {
    private var zeroRunStartedAtNanos = 0L
    private var fallbackRequested = false

    @Synchronized
    fun onSample(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        if (fallbackRequested) return false

        if (x != 0f || y != 0f || z != 0f) {
            zeroRunStartedAtNanos = 0L
            return false
        }

        if (zeroRunStartedAtNanos == 0L || timestampNanos < zeroRunStartedAtNanos) {
            zeroRunStartedAtNanos = timestampNanos
            return false
        }
        if (timestampNanos - zeroRunStartedAtNanos < zeroDurationNanos) return false

        fallbackRequested = true
        return true
    }

    @Synchronized
    fun reset() {
        zeroRunStartedAtNanos = 0L
        fallbackRequested = false
    }

    companion object {
        // A real gyroscope has sensor noise even while stationary. Requiring a full
        // second of bit-for-bit zero samples avoids reacting to a short quiet period
        // without depending on the source's report rate.
        private const val DEFAULT_ZERO_DURATION_NANOS = 1_000_000_000L
    }
}
