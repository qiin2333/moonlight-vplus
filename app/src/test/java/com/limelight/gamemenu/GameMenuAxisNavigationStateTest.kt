package com.limelight.gamemenu

import android.view.KeyEvent
import com.limelight.binding.input.MenuAxisNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMenuAxisNavigationStateTest {
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
}
