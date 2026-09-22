package com.limelight.binding.input.driver

import com.limelight.utils.CompletionSignal
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class UsbInterfaceClaimsTest {
    private data class Interface(val id: Int, val alternate: Int = 0)
    private val descriptors = listOf(Interface(0), Interface(1), Interface(1, 1),
        Interface(2), Interface(2, 1), Interface(3))

    @Test fun alternateSettingsClaimAndReleaseEachInterfaceNumberOnce() {
        val claims = UsbInterfaceClaims<Interface> { it.id }
        val acquired = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val owned = mutableSetOf<Int>()
        descriptors.forEach { iface ->
            assertTrue(claims.claim(iface) { acquired.add(it.id); owned.add(it.id); true })
        }
        assertEquals(listOf(0, 1, 2, 3), acquired)
        claims.releaseAll({ released.add(it.id); owned.remove(it.id) }) { _, _ -> fail() }
        assertEquals(listOf(3, 2, 1, 0), released)
        assertTrue(owned.isEmpty())
        claims.releaseAll({ fail("Released twice"); false }) { _, _ -> fail() }
    }

    @Test fun failedClaimOnlyUnwindsInterfacesActuallyOwned() {
        val claims = UsbInterfaceClaims<Interface> { it.id }
        assertTrue(claims.claim(Interface(0)) { true })
        assertFalse(claims.claim(Interface(1)) { false })
        val released = mutableListOf<Int>()
        claims.releaseAll({ released.add(it.id); true }) { _, _ -> fail() }
        assertEquals(listOf(0), released)
        assertTrue(claims.claim(Interface(1)) { true })
    }

    @Test fun falseAndThrowingInterfaceReleaseStillAllowCloseAndRepeatedForwarding() {
        for (throws in listOf(false, true)) {
            val registry = UsbForwardingReservations()
            repeat(3) {
                val controller = Controller(throws, false)
                assertTrue(controller.start())
                val lease = registry.reserve("usb/a")
                val stop = CompletionSignal()
                lease.awaitStops(listOf(stop))
                controller.stopWithResult { result ->
                    result.fold({ stop.complete() }, { stop.completeExceptionally(it) })
                }
                assertTrue(controller.closed)
                assertEquals(4, controller.releaseAttempts)
                assertEquals(1, controller.warnings)
                assertTrue(lease.canRestore())
                lease.restore {}
                assertFalse(registry.contains("usb/a"))
            }
        }
    }

    @Test fun actualConnectionCloseFailureStillBlocksHandoff() {
        val controller = Controller(false, true)
        controller.start()
        var failure: Throwable? = null
        controller.stopWithResult { failure = it.exceptionOrNull() }
        assertNotNull(failure)
        assertEquals("connection close failed", failure!!.message)
        assertFalse(controller.closed)
    }

    private val listener = Proxy.newProxyInstance(
        ControllerDriverListener::class.java.classLoader,
        arrayOf(ControllerDriverListener::class.java)
    ) { _, _, _ -> null } as ControllerDriverListener

    private inner class Controller(private val throwRelease: Boolean, private val failClose: Boolean) :
        AbstractController(1, listener, 0x054c, 0x0ce6) {
        private val claims = UsbInterfaceClaims<Interface> { it.id }
        var closed = false
        var warnings = 0
        var releaseAttempts = 0
        override fun start() = descriptors.all { claims.claim(it) { true } }
        override fun stop() {
            claims.releaseAll({
                releaseAttempts++
                if (it.id == 1 && throwRelease) throw IllegalStateException("disconnected")
                it.id != 1
            }) { _, _ -> warnings++ }
            releaseUsbResource {
                check(!failClose) { "connection close failed" }
                closed = true
            }
        }
        override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) = Unit
        override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) = Unit
    }
}
