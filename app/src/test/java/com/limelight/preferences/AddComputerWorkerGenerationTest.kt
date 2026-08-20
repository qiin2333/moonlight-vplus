package com.limelight.preferences

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddComputerWorkerGenerationTest {
    @Test
    fun invalidationMakesPreviousWorkerStale() {
        val gate = AddComputerWorkerGenerationGate()
        val first = gate.nextGeneration()

        assertTrue(gate.isCurrent(first))
        gate.invalidate()

        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun startingNewGenerationMakesOlderWorkerStale() {
        val gate = AddComputerWorkerGenerationGate()
        val first = gate.nextGeneration()
        val second = gate.nextGeneration()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun blockedOldWorkerCannotPublishAfterServiceReconnect() {
        val gate = AddComputerWorkerGenerationGate()
        val oldGeneration = gate.nextGeneration()
        val networkStarted = CountDownLatch(1)
        val networkMayReturn = CountDownLatch(1)
        val publishedResults = AtomicInteger()
        val oldWorker = Thread {
            networkStarted.countDown()
            networkMayReturn.await()
            if (gate.isCurrent(oldGeneration)) publishedResults.incrementAndGet()
        }
        oldWorker.start()

        assertTrue(networkStarted.await(1, TimeUnit.SECONDS))
        gate.invalidate()
        val reconnectedGeneration = gate.nextGeneration()
        networkMayReturn.countDown()
        oldWorker.join(1_000)

        assertFalse(oldWorker.isAlive)
        assertEquals(0, publishedResults.get())
        assertTrue(gate.isCurrent(reconnectedGeneration))
    }
}
