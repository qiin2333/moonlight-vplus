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
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val parked = CountDownLatch(1)
        val releaseSchedule = CountDownLatch(1)
        // Force the named ordering: occupy the worker, park post() inside schedule() (it
        // holds the registration lock), then free the worker — the gated task is the
        // worker's next action and it cannot pass its cleanup until the poster finishes
        // registering. Both remaining interleavings must drop the entry, and an
        // implementation whose cleanup ran before registration would leave the stale
        // entry this test asserts against.
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
            executor.execute {
                workerBlocked.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
            }
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            val poster = Thread { delayer.post(task, 0) }
            poster.start()
            assertTrue(parked.await(2, TimeUnit.SECONDS))
            releaseWorker.countDown()
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
            releaseWorker.countDown()
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
