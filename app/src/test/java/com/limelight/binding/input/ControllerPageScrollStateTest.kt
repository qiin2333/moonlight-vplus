package com.limelight.binding.input

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerPageScrollStateTest {
    @Test
    fun axisUsesActivationHysteresisAndRepeatInterval() {
        val state = ControllerPageScrollState(repeatIntervalMs = 80L)

        assertFalse(state.update(listOf(0.4f), 0L).consumed)

        val activated = state.update(listOf(0.8f), 10L)
        assertEquals(1, activated.direction)
        assertTrue(activated.shouldScroll)

        val heldBeforeRepeat = state.update(listOf(0.5f), 50L)
        assertTrue(heldBeforeRepeat.consumed)
        assertFalse(heldBeforeRepeat.shouldScroll)

        val repeated = state.update(listOf(0.5f), 90L)
        assertTrue(repeated.shouldScroll)

        val released = state.update(listOf(0.2f), 100L)
        assertTrue(released.consumed)
        assertEquals(0, released.direction)

        val reactivated = state.update(listOf(-0.8f), 110L)
        assertEquals(-1, reactivated.direction)
        assertTrue(reactivated.shouldScroll)
    }

    @Test
    fun nextSourceTakesOverAfterActiveSourceReleases() {
        val state = ControllerPageScrollState()

        assertEquals(1, state.update(listOf(0.8f, -0.9f), 0L).direction)
        assertEquals(-1, state.update(listOf(0f, -0.9f), 100L).direction)
    }

    @Test
    fun rightStickAxisMatchesControllerMappingRules() {
        assertEquals(
            MotionEvent.AXIS_RY,
            resolveControllerPageRightStickYAxis(
                hasRxRy = true,
                hasZRz = true,
                isLegacyDualShockMapping = false
            )
        )
        assertEquals(
            MotionEvent.AXIS_RZ,
            resolveControllerPageRightStickYAxis(
                hasRxRy = false,
                hasZRz = true,
                isLegacyDualShockMapping = false
            )
        )
        assertEquals(
            MotionEvent.AXIS_RZ,
            resolveControllerPageRightStickYAxis(
                hasRxRy = true,
                hasZRz = true,
                isLegacyDualShockMapping = true
            )
        )
        assertEquals(
            null,
            resolveControllerPageRightStickYAxis(
                hasRxRy = true,
                hasZRz = false,
                isLegacyDualShockMapping = true
            )
        )
    }
}
