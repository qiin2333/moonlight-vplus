package com.limelight.binding.input.haptics

/** Owned exclusively by DeviceVibrationCoordinator's hardware worker. */
internal interface NativeAuthoredHaptics {
    val ready: Boolean
    /** Rendering gains with user strength already applied; the backend may shape/quantize/limit.
     * Returns the elapsed-realtime deadline for restoring the background after a point.
     */
    fun render(body: Float, point: Float, keepWarm: Boolean): Long?
    fun stop()
    fun close()
}
