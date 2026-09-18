package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.HapticRouteSnapshot
import com.limelight.binding.input.haptics.WaveformHapticsSink
import java.util.concurrent.atomic.AtomicBoolean

/** Output-only USB lifetime owner. Input events and player numbering stay with Android. */
internal class UsbWaveformController(
    val route: HapticRouteSnapshot,
    val sink: WaveformHapticsSink,
    listener: ControllerDriverListener,
    private val onAssociationLost: () -> Unit
) : AbstractController(route.id, listener, route.device.vendorId, route.device.productId) {
    private val detached = AtomicBoolean()
    private val removed = AtomicBoolean()
    val isStopping: Boolean get() = detached.get()
    override fun start(): Boolean { announce(); return true }
    fun announce() = listener.onSystemWaveformSinkAvailable(route, sink, onAssociationLost)
    override fun stop() = stopAndThen {}
    override fun stopAndThen(onStopped: () -> Unit) {
        if (detached.compareAndSet(false, true)) listener.onSystemWaveformSinkGone(deviceId)
        sink.stopAndThen {
            releaseUsbResource { sink.releaseFailure?.let { throw it } }
            if (removed.compareAndSet(false, true)) notifyDeviceRemoved()
            onStopped()
        }
    }
    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = Unit
    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit
}
