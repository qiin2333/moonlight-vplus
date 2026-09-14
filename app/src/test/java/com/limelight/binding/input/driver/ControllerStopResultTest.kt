package com.limelight.binding.input.driver

import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class ControllerStopResultTest {
    private val listener = Proxy.newProxyInstance(
        ControllerDriverListener::class.java.classLoader,
        arrayOf(ControllerDriverListener::class.java)
    ) { _, _, _ -> null } as ControllerDriverListener

    private inner class Controller(val failRelease: Boolean) : AbstractController(1, listener, 1, 2) {
        var closed = false
        override fun start() = true
        override fun stop() {
            releaseUsbResource { check(!failRelease) { "interface release failed" } }
            releaseUsbResource { closed = true }
        }
        override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = Unit
        override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit
    }

    @Test fun releaseFailureReachesLeaseDespiteCompletedStopCallback() {
        val controller = Controller(true)
        val stop = CompletableFuture<Void>()
        val registry = UsbForwardingReservations()
        val lease = registry.reserve("usb/a")
        lease.awaitStops(listOf(stop))
        controller.stopWithResult { result ->
            result.fold({ stop.complete(null) }, { stop.completeExceptionally(it) })
        }
        assertTrue(controller.closed)
        assertTrue(lease.ready.isCompletedExceptionally)
        assertFalse(lease.canRestore())
        assertTrue(registry.contains("usb/a"))
    }

    @Test fun successfulTransportReleaseReportsSuccess() {
        val controller = Controller(false)
        var succeeded = false
        controller.stopWithResult { succeeded = it.isSuccess }
        assertTrue(controller.closed)
        assertTrue(succeeded)
    }
}
