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
            microphoneButtonVisible = false
        )
        assertTrue(state.enter(original))
        assertFalse(state.enter(
            PipInteractiveOverlaySnapshot(
                virtualControllerVisible = false,
                crownControllerVisible = false,
                microphoneButtonVisible = false
            )
        ))

        assertEquals(original, state.exit())
        assertFalse(state.isActive())
    }

    @Test
    fun exitWithoutEnterDoesNothing() {
        val state = PipInteractiveOverlayState()

        assertNull(state.exit())
        assertFalse(state.isActive())
    }

    @Test
    fun rapidExitAndReentryCaptureFreshVisibility() {
        val state = PipInteractiveOverlayState()
        val first = PipInteractiveOverlaySnapshot(true, false, true)
        assertTrue(state.enter(first))
        assertTrue(state.isActive())
        assertEquals(first, state.exit())

        val next = PipInteractiveOverlaySnapshot(false, true, false)
        assertTrue(state.enter(next))

        assertEquals(next, state.exit())
    }
}
