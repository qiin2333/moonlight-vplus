package com.limelight.binding.input.haptics

import android.content.Context

/** Owned exclusively by DeviceVibrationCoordinator's hardware worker. */
internal interface NativeAuthoredHaptics {
    val ready: Boolean
    /** False while loading; true only after an unrecoverable initialization/playback failure. */
    val failed: Boolean get() = false
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
        fun create(context: Context): NativeAuthoredHaptics? {
            val preferred = SoundPoolAuthoredHaptics.create(context)
            // RichTap construction probes interfaces without loading assets or driving the motor.
            val fallback = RichTapAuthoredHaptics.create(context)
            return if (preferred == null) fallback else if (fallback == null) preferred
                else NativeAuthoredFallback(preferred, fallback)
        }
    }
}

/** One-way failover; loading never changes selection. Lifecycle calls stay on the device worker. */
internal class NativeAuthoredFallback(
    private val preferred: NativeAuthoredHaptics,
    private val fallback: NativeAuthoredHaptics
) : NativeAuthoredHaptics {
    @Volatile private var closed = false
    private var preferredClosed = false
    private val selected get() = if (preferred.failed) fallback else preferred
    override val ready get() = !closed && selected.ready
    override val failed get() = closed || selected.failed
    override val backendName get() = selected.backendName
    override val supportsContinuous get() = selected.supportsContinuous

    override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
        if (closed) return null
        if (preferred.failed && !preferredClosed) {
            preferredClosed = true
            preferred.close()
        }
        val target = selected
        // Capability may change between routing on the main loop and dispatch on this worker.
        if (!target.ready || !target.supportsContinuous && ControllerRumbleState.normalizeAmplitude(body) > 0f)
            return null
        // A rejected write is not replayed: the next fresh source state can use the fallback.
        return target.render(body, point, keepWarm)
    }

    override fun stop() {
        if (closed) return
        if (!preferredClosed) preferred.stop()
        fallback.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!preferredClosed) { preferredClosed = true; preferred.close() }
        } finally { fallback.close() }
    }
}
