package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipInteractiveOverlayStateTest {
    @Test
    fun duplicateEnterKeepsOriginalVisibilitySnapshot() {
        val state = PipInteractiveOverlayState()
        val original = PipInteractiveOverlaySnapshot(
            virtualControllerVisible = true,
            crownControllerVisible = true,
            microphoneButtonVisible = false,
            floatBallVisible = true
        )
        assertTrue(state.enter(original))
        assertEquals(true, state.virtualControllerVisibleForStop(false))
        assertNull(state.exitIfResumed(false))
        assertTrue(state.isActive())
        assertFalse(state.enter(
            PipInteractiveOverlaySnapshot(
                virtualControllerVisible = false,
                crownControllerVisible = false,
                microphoneButtonVisible = false,
                floatBallVisible = false
            )
        ))

        assertEquals(original, state.exitIfResumed(true))
        assertEquals(false, state.virtualControllerVisibleForStop(false))
        assertFalse(state.isActive())
    }

    @Test
    fun exitWithoutEnterDoesNothing() {
        val state = PipInteractiveOverlayState()

        assertNull(state.exitIfResumed(true))
        assertFalse(state.isActive())
    }

    @Test
    fun rapidExitAndReentryCaptureFreshVisibility() {
        val state = PipInteractiveOverlayState()
        val first = PipInteractiveOverlaySnapshot(true, false, true, true)
        assertTrue(state.enter(first))
        assertTrue(state.isActive())
        assertEquals(first, state.exitIfResumed(true))

        val next = PipInteractiveOverlaySnapshot(false, true, false, false)
        assertTrue(state.enter(next))

        assertEquals(next, state.exitIfResumed(true))
    }
}
