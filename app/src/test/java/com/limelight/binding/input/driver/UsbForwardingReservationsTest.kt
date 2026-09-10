package com.limelight.binding.input.driver

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class UsbForwardingReservationsTest {
    @Test fun reservationDuringServiceStopRetainsFailureUntilStopFinishes() {
        val registry = UsbForwardingReservations()
        val stop = UsbDriverStopResult()
        val lease = registry.reserve("usb/a")
        lease.awaitStops(listOf(stop.completion))
        stop.failed(IllegalStateException("driver release failed"))
        assertFalse(lease.ready.isDone)
        stop.finish()
        assertTrue(lease.ready.isCompletedExceptionally)
        assertThrows(IllegalStateException::class.java) { lease.restore {} }
        assertTrue(registry.contains("usb/a"))
    }

    @Test fun reservationDuringCleanServiceStopBecomesReady() {
        val registry = UsbForwardingReservations()
        val stop = UsbDriverStopResult()
        val lease = registry.reserve("usb/a")
        lease.awaitStops(listOf(stop.completion))
        assertFalse(lease.ready.isDone)
        stop.finish()
        lease.ready.join()
        lease.restore {}
        assertFalse(registry.contains("usb/a"))
    }

    @Test fun waitsForEveryLocalDriverAndRestoresOnlyOnce() {
        val registry = UsbForwardingReservations()
        val lease = registry.reserve("usb/a")
        val first = CompletableFuture<Void>()
        val second = CompletableFuture<Void>()
        lease.awaitStops(listOf(first, second))
        first.complete(null)
        assertFalse(lease.ready.isDone)
        assertThrows(IllegalStateException::class.java) { lease.restore {} }
        second.complete(null)
        var restores = 0
        lease.restore { restores++ }
        lease.restore { restores++ }
        assertEquals(1, restores)
        assertFalse(registry.contains("usb/a"))
    }

    @Test fun failedStopKeepsDeviceReserved() {
        val registry = UsbForwardingReservations()
        val lease = registry.reserve("usb/a")
        val stop = CompletableFuture<Void>()
        lease.awaitStops(listOf(stop))
        stop.completeExceptionally(IllegalStateException("USB close failed"))
        assertTrue(lease.ready.isCompletedExceptionally)
        assertThrows(IllegalStateException::class.java) { lease.restore {} }
        assertTrue(registry.contains("usb/a"))
    }

    @Test fun staleRestoreCannotReleaseANewOwnerAndOtherDevicesRemainAvailable() {
        val registry = UsbForwardingReservations()
        val old = registry.reserve("usb/a")
        old.awaitStops(emptyList())
        assertFalse(registry.contains("usb/b"))
        assertThrows(IllegalStateException::class.java) { registry.reserve("usb/a") }
        old.restore {}
        val current = registry.reserve("usb/a")
        old.restore { fail("Stale restore ran") }
        assertTrue(registry.contains("usb/a"))
        current.awaitStops(emptyList())
        current.restore {}
    }

    @Test fun lateClaimCannotPassReservationWhileDriverStartupIsLocked() {
        val registry = UsbForwardingReservations()
        val waiting = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var canClaim = true
        registry.lock.withLock {
            thread {
                waiting.countDown()
                registry.lock.withLock { canClaim = !registry.contains("usb/a") }
                finished.countDown()
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            registry.reserve("usb/a").awaitStops(emptyList())
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(canClaim)
    }
}
