package com.limelight.nvstream

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ConnectionLifecycleTest {
    @Test fun failureOrRepeatedStopCannotCreateAdditionalConnectionSlots() {
        val semaphore = Semaphore(1)
        val cancelled = ConnectionSlot(semaphore)
        cancelled.revoke(); cancelled.release(); cancelled.release()
        assertFalse(cancelled.acquire())
        assertEquals(1, semaphore.availablePermits())
        val live = ConnectionSlot(semaphore)
        assertTrue(live.acquire())
        live.revoke()
        assertEquals(0, semaphore.availablePermits())
        live.release(); live.release()
        assertEquals(1, semaphore.availablePermits())
    }

    @Test fun aCancelledWaiterNeverOwnsOrReleasesAnotherConnection() {
        val semaphore = Semaphore(0)
        val waiter = ConnectionSlot(semaphore)
        val waiting = CountDownLatch(1)
        val owned = AtomicBoolean(true)
        val worker = thread { waiting.countDown(); owned.set(waiter.acquire()) }
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        awaitQueued(semaphore)
        waiter.revoke(); waiter.release()
        assertEquals(0, semaphore.availablePermits())
        semaphore.release()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertFalse(owned.get())
        assertEquals(1, semaphore.availablePermits())
    }

    @Test fun anInterruptedWaiterCannotReleaseTheActiveConnectionsSlot() {
        val semaphore = Semaphore(0)
        val waiter = ConnectionSlot(semaphore)
        val interrupted = AtomicBoolean(false)
        val worker = thread {
            try { waiter.acquire() }
            catch (_: InterruptedException) { interrupted.set(true) }
            finally { waiter.revoke(); waiter.release() }
        }
        awaitQueued(semaphore)
        worker.interrupt(); worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(interrupted.get())
        assertEquals(0, semaphore.availablePermits())
        semaphore.release()
        assertEquals(1, semaphore.availablePermits())
    }

    private fun awaitQueued(semaphore: Semaphore) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!semaphore.hasQueuedThreads() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Worker must actually be queued before cancellation", semaphore.hasQueuedThreads())
    }

    @Test fun oldHttpMustBecomeTerminalBeforeNewHostNegotiationCanStart() {
        val semaphore = Semaphore(1)
        val old = ConnectionSlot(semaphore)
        assertTrue(old.acquire())
        val entered = CountDownLatch(1)
        val finishHttp = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val bitrate = LegacyBitrateWorker {
            entered.countDown()
            check(finishHttp.await(3, TimeUnit.SECONDS))
            events += "old host commit"
            true
        }
        val callback = AtomicBoolean(false)
        val replacement = ConnectionSlot(semaphore)
        val launched = CountDownLatch(1)
        var next: Thread? = null
        try {
            assertTrue(bitrate.submit(4500, { callback.set(true) }, { callback.set(true) }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            old.revoke(); bitrate.close()
            assertFalse(bitrate.awaitClosed(20, TimeUnit.MILLISECONDS))
            next = thread {
                check(replacement.acquire())
                events += "new launch"
                launched.countDown()
                replacement.release()
            }
            assertFalse(launched.await(20, TimeUnit.MILLISECONDS))
            finishHttp.countDown()
            assertTrue(bitrate.awaitClosed(2, TimeUnit.SECONDS))
            assertFalse(callback.get())
            old.release()
            assertTrue(launched.await(2, TimeUnit.SECONDS))
            next.join(2000)
            assertEquals(listOf("old host commit", "new launch"), events.toList())
            assertEquals(1, semaphore.availablePermits())
        } finally {
            finishHttp.countDown(); bitrate.close()
            bitrate.awaitClosed(2, TimeUnit.SECONDS)
            old.release(); replacement.revoke()
            next?.join(2000)
        }
    }

    @Test fun closingCancelsQueuedWritesAndRejectsFutureSubmissions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val bitrate = LegacyBitrateWorker { k ->
            writes += k; entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); true
        }
        val callbacks = AtomicBoolean(false)
        try {
            assertTrue(bitrate.submit(4000, { callbacks.set(true) }, { callbacks.set(true) }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(bitrate.submit(8000, { callbacks.set(true) }, { callbacks.set(true) }))
            bitrate.close()
            assertFalse(bitrate.submit(12000, {}, {}))
            release.countDown()
            assertTrue(bitrate.awaitClosed(2, TimeUnit.SECONDS))
            assertEquals(listOf(4000), writes.toList())
            assertFalse(callbacks.get())
        } finally { release.countDown(); bitrate.close(); bitrate.awaitClosed(2, TimeUnit.SECONDS) }
    }

    @Test fun serialFailuresAndCallbackExceptionsDoNotLoseLaterUserRequests() {
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val completed = CountDownLatch(3)
        val bitrate = LegacyBitrateWorker { k -> writes += k; if (k == 4000) throw java.io.IOException("reply lost"); k != 5000 }
        try {
            for (k in listOf(4000,5000,6000)) assertTrue(bitrate.submit(k,
                { assertEquals(6000, it); completed.countDown() },
                { completed.countDown(); throw IllegalStateException("closed UI") }))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(4000,5000,6000), writes.toList())
        } finally { bitrate.close(); assertTrue(bitrate.awaitClosed(2, TimeUnit.SECONDS)) }
    }
}
