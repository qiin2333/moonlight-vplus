package com.limelight.gamemenu

import android.view.KeyEvent
import android.view.MotionEvent
import com.limelight.binding.input.MenuAxisNavigationState
import com.limelight.binding.input.MenuAxisSnapshotState
import com.limelight.binding.input.MenuNavigationAxisMapping
import com.limelight.binding.input.readMenuNavigationAxisPairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMenuAxisNavigationStateTest {
    @Test
    fun activeAxisSourceCannotBePreemptedBeforeRelease() {
        assertTrue(canActivateGameMenuAxisSource(activeSourceId = null, reportingSourceId = 1))
        assertTrue(canActivateGameMenuAxisSource(activeSourceId = 1, reportingSourceId = 1))
        assertFalse(canActivateGameMenuAxisSource(activeSourceId = 1, reportingSourceId = 2))
    }

    @Test
    fun rawAxisSnapshotFilterReemitsCurrentValuesAfterReset() {
        val state = MenuAxisSnapshotState()
        val centered = listOf(0f to 0f, 0f to 0f)

        assertTrue(state.update(centered))
        assertFalse(state.update(centered))
        assertTrue(state.update(listOf(0.7f to 0f, 0f to 0f)))

        state.reset()

        assertTrue(state.update(listOf(0.7f to 0f, 0f to 0f)))
    }

    @Test
    fun supportsHatLeftStickAndRightStickInPriorityOrder() {
        val state = MenuAxisNavigationState()

        val transition = state.update(listOf(0f to 0f, 0f to 0f, 0.8f to 0f))

        assertTrue(transition.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, transition.pressedKeyCode)
    }

    @Test
    fun diagonalUsesDominantAxisAndDoesNotMoveTwice() {
        val state = MenuAxisNavigationState()

        assertEquals(
            KeyEvent.KEYCODE_DPAD_DOWN,
            state.update(listOf(0.7f to 0.9f)).pressedKeyCode
        )
    }

    @Test
    fun hysteresisKeepsDirectionUntilAxisReturnsNearCenter() {
        val state = MenuAxisNavigationState()
        state.update(listOf(-0.8f to 0f))

        assertFalse(state.update(listOf(-0.5f to 0f)).changed)
        val released = state.update(listOf(-0.2f to 0f))

        assertTrue(released.changed)
        assertNull(released.pressedKeyCode)
    }

    @Test
    fun directionDoesNotSwitchBelowActivationThreshold() {
        val state = MenuAxisNavigationState()
        state.update(listOf(0.8f to 0f))

        val released = state.update(listOf(0.3f to 0.4f))

        assertTrue(released.changed)
        assertNull(released.pressedKeyCode)
    }

    @Test
    fun dominantAxisDriftDoesNotReplaceHeldDirectionBelowActivationThreshold() {
        val state = MenuAxisNavigationState()
        state.update(listOf(0.8f to 0f))

        val held = state.update(listOf(0.5f to 0.55f))

        assertFalse(held.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, held.pressedKeyCode)
    }

    @Test
    fun directionSwitchesOnlyAfterNewDirectionActivates() {
        val state = MenuAxisNavigationState()
        state.update(listOf(0.8f to 0f))

        val switched = state.update(listOf(0.4f to 0.8f))

        assertTrue(switched.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, switched.pressedKeyCode)
    }

    @Test
    fun anotherAxisSourceCannotStealFocusUntilCurrentSourceReturnsToCenter() {
        val state = MenuAxisNavigationState()
        state.update(listOf(0.8f to 0f, 0f to 0f))

        val held = state.update(listOf(0.5f to 0f, 0f to 0.5f))
        assertFalse(held.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, held.pressedKeyCode)

        val competing = state.update(listOf(0.5f to 0f, 0f to 0.8f))
        assertFalse(competing.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, competing.pressedKeyCode)

        val switched = state.update(listOf(0.2f to 0f, 0f to 0.8f))
        assertTrue(switched.changed)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, switched.pressedKeyCode)
    }

    @Test
    fun neutralRequiresEveryAxisPairInsideReleaseThreshold() {
        val state = MenuAxisNavigationState()

        assertFalse(state.isNeutral(listOf(0f to 0f, 0.4f to 0f)))
        assertTrue(state.isNeutral(listOf(0.2f to 0f, 0f to -0.2f)))
    }

    @Test
    fun rxRyRightStickMappingIgnoresCenteredZAndRzTriggers() {
        val mapping = MenuNavigationAxisMapping(
            leftStickAxes = MotionEvent.AXIS_X to MotionEvent.AXIS_Y,
            rightStickAxes = MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        )

        listOf(-1f, 0f, 1f).forEach { triggerValue ->
            val values = mapOf(
                MotionEvent.AXIS_X to 0f,
                MotionEvent.AXIS_Y to 0f,
                MotionEvent.AXIS_RX to 0.75f,
                MotionEvent.AXIS_RY to -0.25f,
                MotionEvent.AXIS_Z to triggerValue,
                MotionEvent.AXIS_RZ to triggerValue
            )

            assertEquals(
                listOf(0f to 0f, 0.75f to -0.25f),
                readMenuNavigationAxisPairs(mapping) { values[it] ?: 0f }
            )
        }
    }

    @Test
    fun zRzRightStickMappingIgnoresDedicatedTriggerAxes() {
        val mapping = MenuNavigationAxisMapping(
            leftStickAxes = MotionEvent.AXIS_X to MotionEvent.AXIS_Y,
            rightStickAxes = MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        )

        listOf(-1f, 0f, 1f).forEach { triggerValue ->
            val values = mapOf(
                MotionEvent.AXIS_X to 0f,
                MotionEvent.AXIS_Y to 0f,
                MotionEvent.AXIS_Z to -0.8f,
                MotionEvent.AXIS_RZ to 0.4f,
                MotionEvent.AXIS_LTRIGGER to triggerValue,
                MotionEvent.AXIS_RTRIGGER to triggerValue
            )

            assertEquals(
                listOf(0f to 0f, -0.8f to 0.4f),
                readMenuNavigationAxisPairs(mapping) { values[it] ?: 0f }
            )
        }
    }
}
