package com.limelight.binding.input.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDriverSessionHandoffTest {
    @Test
    fun latestStartRequestWinsWhileControllersStop() {
        val handoff = UsbDriverSessionHandoff<String>()
        val generation = handoff.beginStop(listOf(1))

        assertTrue(handoff.queueStart("diagnostics"))
        assertTrue(handoff.queueStart("stream"))

        val completion = handoff.completeController(generation, 1)
        assertTrue(completion.finished)
        assertEquals("stream", completion.pendingStart)
    }

    @Test
    fun waitsForEveryStoppingController() {
        val handoff = UsbDriverSessionHandoff<String>()
        val generation = handoff.beginStop(listOf(1, 2))
        handoff.queueStart("stream")

        assertFalse(handoff.completeController(generation, 1).finished)
        assertTrue(handoff.isStopping)

        val completion = handoff.completeController(generation, 2)
        assertTrue(completion.finished)
        assertEquals("stream", completion.pendingStart)
        assertFalse(handoff.isStopping)
    }

    @Test
    fun staleGenerationCannotCompleteCurrentStop() {
        val handoff = UsbDriverSessionHandoff<String>()
        val firstGeneration = handoff.beginStop(listOf(1))
        handoff.completeController(firstGeneration, 1)
        val secondGeneration = handoff.beginStop(listOf(2))

        assertFalse(handoff.completeController(firstGeneration, 2).finished)
        assertTrue(handoff.isStoppingController(2))
        assertTrue(handoff.completeController(secondGeneration, 2).finished)
    }

    @Test
    fun cancelledPendingStartIsNotRestarted() {
        val handoff = UsbDriverSessionHandoff<String>()
        val generation = handoff.beginStop(listOf(1))
        handoff.queueStart("diagnostics")

        handoff.cancelPendingStart()

        assertNull(handoff.completeController(generation, 1).pendingStart)
    }
}
