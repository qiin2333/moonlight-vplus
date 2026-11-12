package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutorDelayerTest {
    @Test
    fun aTaskStartingDuringItsOwnRegistrationStillDropsIt() {
        val real = Executors.newSingleThreadScheduledExecutor()
        val parked = CountDownLatch(1)
        val releaseSchedule = CountDownLatch(1)
        // Parks post() inside schedule(): the test observes the pre-registration state,
        // then releases into an idle worker so the wrapper starts before registration
        // finishes. Both orderings of that race must leave no registration behind.
        val executor = object : ScheduledExecutorService by real {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                parked.countDown()
                releaseSchedule.await(2, TimeUnit.SECONDS)
                return real.schedule(command, delay, unit)
            }
        }
        val delayer = ExecutorDelayer(executor)
        val runs = AtomicInteger()
        val task = Runnable { runs.incrementAndGet() }
        try {
            val poster = Thread { delayer.post(task, 0) }
            poster.start()
            assertTrue(parked.await(2, TimeUnit.SECONDS))
            // The poster holds the registration lock while parked inside schedule(), so the
            // pre-registration window is unobservable to other threads by construction; the
            // deterministic part is releasing into an idle worker: the wrapper then races the
            // registration and must still drop its own entry.
            releaseSchedule.countDown()
            poster.join(2_000)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((runs.get() == 0 || delayer.pendingCount != 0) && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, runs.get())
            assertEquals("A run that started before its registration completed must drop it",
                0, delayer.pendingCount)
        } finally {
            releaseSchedule.countDown()
            real.shutdownNow()
        }
    }

    @Test
    fun removeCancelsThePendingTask() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val delayer = ExecutorDelayer(executor)
        val runs = AtomicInteger()
        val task = Runnable { runs.incrementAndGet() }
        try {
            delayer.post(task, 100)
            delayer.remove(task)
            Thread.sleep(300)
            assertEquals(0, runs.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun postingTheSameTaskReplacesThePendingRun() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val delayer = ExecutorDelayer(executor)
        val runs = AtomicInteger()
        val task = Runnable { runs.incrementAndGet() }
        try {
            delayer.post(task, 400)
            delayer.post(task, 20)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runs.get() == 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, runs.get())
            // Wait past the ORIGINAL 400ms deadline too: an implementation that never
            // cancelled the replaced task would run it here.
            Thread.sleep(500)
            assertEquals("A replaced task must not run twice", 1, runs.get())
            assertEquals("Both the replaced and the completed registration must be gone",
                0, delayer.pendingCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aCompletedRunDropsItsOwnRegistration() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val delayer = ExecutorDelayer(executor)
        val runs = AtomicInteger()
        val task = Runnable { runs.incrementAndGet() }
        try {
            delayer.post(task, 0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((runs.get() == 0 || delayer.pendingCount != 0) && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, runs.get())
            assertEquals("A completed run must not retain its registration",
                0, delayer.pendingCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun postAfterShutdownIsDroppedSilently() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.shutdown()
        val delayer = ExecutorDelayer(executor)
        val runs = AtomicInteger()
        delayer.post(Runnable { runs.incrementAndGet() }, 0)
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(0, runs.get())
    }
}
