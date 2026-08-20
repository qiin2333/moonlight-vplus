package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestWinsDispatcherTest {
    @Test
    fun blockedSinkKeepsOnlyLatestPendingValue() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val twoDelivered = CountDownLatch(2)
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = LatestWinsDispatcher<Int>(
            minimumIntervalMs = 0,
            executor = executor,
            dispatch = { value: Int ->
                delivered += value
                if (value == 1) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                twoDelivered.countDown()
            }
        )

        dispatcher.submit(1)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        dispatcher.submit(2)
        dispatcher.submit(3)
        releaseFirst.countDown()

        assertTrue(twoDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 3), delivered)
        dispatcher.close()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun unchangedValueIsNotDispatchedTwice() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val firstDelivered = CountDownLatch(1)
        val workerDrained = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = LatestWinsDispatcher<Int>(
            minimumIntervalMs = 0,
            executor = executor,
            dispatch = { value: Int ->
                delivered += value
                firstDelivered.countDown()
            }
        )

        dispatcher.submit(7)
        assertTrue(firstDelivered.await(2, TimeUnit.SECONDS))
        dispatcher.submit(7)
        executor.execute { workerDrained.countDown() }

        assertTrue(workerDrained.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(7), delivered)
        dispatcher.close()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun clearingPendingWorkDoesNotWaitForBlockedSink() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val workerDrained = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = LatestWinsDispatcher<Int>(
            minimumIntervalMs = 0,
            executor = executor,
            dispatch = { value: Int ->
                delivered += value
                firstStarted.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
        )

        dispatcher.submit(1)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        dispatcher.submit(2)
        dispatcher.clearPending()
        releaseFirst.countDown()
        executor.execute { workerDrained.countDown() }

        assertTrue(workerDrained.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1), delivered)
        dispatcher.close()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }
}
