package com.limelight.binding.input.haptics

import android.content.Context

/** Owned exclusively by DeviceVibrationCoordinator's hardware worker. */
internal interface NativeAuthoredHaptics {
    val ready: Boolean
    val backendName: String get() = "native"
    val supportsContinuous: Boolean get() = true
    /** Rendering gains with user strength already applied; the backend may shape/quantize/limit.
     * Returns the elapsed-realtime deadline for restoring the background after a point.
     */
    fun render(body: Float, point: Float, keepWarm: Boolean): Long?
    fun stop()
    fun close()

    companion object {
        fun supported(): Boolean = SoundPoolAuthoredHaptics.supported() || RichTapAuthoredHaptics.supported()

        // Keep the public audio route on devices where it already works. Vendor extensions
        // are capability-probed and remain behind the same default-off experiment.
        fun create(context: Context): NativeAuthoredHaptics? =
            SoundPoolAuthoredHaptics.create(context) ?: RichTapAuthoredHaptics.create(context)
    }
}
