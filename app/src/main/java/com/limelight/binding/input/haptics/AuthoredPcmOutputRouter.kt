package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame

/** Selection used by the coordinator; transport success and energy fallback are mutually exclusive. */
internal class AuthoredPcmOutputRouter {
    data class Delivery(val direct: Boolean, val fallback: ControllerRumbleState, val failedSink: Boolean)
    private val reducer = AuthoredPcmFallback()

    fun reset(controllerNumber: Short) = reducer.reset(controllerNumber)

    fun submit(frame: Ds5HapticsPcmFrame, sink: WaveformHapticsSink?, controllerEnabled: Boolean): Delivery? {
        val reduced = reducer.accept(frame) ?: return null
        val direct = controllerEnabled && sink?.isOperational == true &&
            runCatching { sink.submit(frame) }.isSuccess && sink.isOperational
        val failed = sink != null && (!sink.isOperational || controllerEnabled && !direct)
        return Delivery(direct, if (direct) ControllerRumbleState.ZERO else reduced, failed)
    }
}
