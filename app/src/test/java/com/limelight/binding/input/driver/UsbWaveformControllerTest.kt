package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.*
import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.*
import org.junit.Test

class UsbWaveformControllerTest {
    private class Sink : WaveformHapticsSink {
        val completions = mutableListOf<() -> Unit>()
        override var releaseFailure: Throwable? = null
        override fun start() = true
        override fun submit(frame: Ds5HapticsPcmFrame) = Unit
        override fun stop() = Unit
        override fun stopAndThen(onStopped: () -> Unit) { completions.add(onStopped) }
        fun complete() { completions.toList().also { completions.clear() }.forEach { it() } }
    }

    private class Listener : ControllerDriverListener {
        val events = mutableListOf<String>()
        var retry: (() -> Unit)? = null
        override fun onSystemWaveformSinkAvailable(route: HapticRouteSnapshot, sink: WaveformHapticsSink,
                                                   onAssociationLost: () -> Unit) {
            events += "waveform:${route.id}"
            retry = onAssociationLost
        }
        override fun onSystemWaveformSinkGone(routeId: Int) { events += "detach:$routeId" }
        override fun deviceAdded(controller: AbstractController) { events += "input-added" }
        override fun deviceRemoved(controller: AbstractController) { events += "released" }
        override fun reportControllerState(controllerId: Int, buttonFlags: Int, leftStickX: Float,
            leftStickY: Float, rightStickX: Float, rightStickY: Float, leftTrigger: Float, rightTrigger: Float) {
            events += "input"
        }
        override fun reportControllerMotion(controllerId: Int, motionType: Byte, x: Float, y: Float, z: Float) = Unit
    }

    private fun route() = HapticRouteSnapshot(7, HapticDeviceIdentity("usb/1", "Test", 1, 2, HapticTransport.USB),
        ControllerHapticsCapability("test", HapticOutput.WAVEFORM_STREAM, HapticAvailability.INITIALIZING,
            HapticEvidence.VALIDATED_PROTOCOL, WaveformFormat(4000)))

    @Test fun companionDoesNotCreateInputEventsOrAllocateAPlayer() {
        val listener = Listener()
        val owner = UsbWaveformController(route(), Sink(), listener) {}
        assertTrue(owner.start())
        owner.rumble(1, 1)
        assertEquals(listOf("waveform:7"), listener.events)
    }

    @Test fun detachPrecedesReleaseAndRepeatedStopCompletesOncePerCaller() {
        val listener = Listener()
        val sink = Sink()
        val owner = UsbWaveformController(route(), sink, listener) {}
        var completions = 0
        owner.stopAndThen { completions++ }
        owner.stopAndThen { completions++ }
        assertEquals(listOf("detach:7"), listener.events)
        assertEquals(0, completions)
        sink.complete()
        assertEquals(listOf("detach:7", "released"), listener.events)
        assertEquals(2, completions)
    }

    @Test fun failedReleaseIsReportedToForwardingInsteadOfClaimingSuccess() {
        val sink = Sink().apply { releaseFailure = IllegalStateException("close failed") }
        val owner = UsbWaveformController(route(), sink, Listener()) {}
        var result: Result<Unit>? = null
        owner.stopWithResult { result = it }
        assertNull(result)
        sink.complete()
        assertTrue(result!!.isFailure)
    }

    @Test fun associationRecoveryIsDelegatedToTheTransportOwner() {
        val listener = Listener()
        var retried = 0
        val owner = UsbWaveformController(route(), Sink(), listener) { retried++ }
        owner.start()
        listener.retry!!.invoke()
        assertEquals(1, retried)
    }
}
