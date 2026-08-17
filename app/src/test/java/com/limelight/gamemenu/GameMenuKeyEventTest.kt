package com.limelight.gamemenu

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMenuKeyEventTest {
    @Test
    fun standardGamepadAIsMappedToFocusedUiConfirmation() {
        assertEquals(
            KeyEvent.KEYCODE_DPAD_CENTER,
            mapGameMenuConfirmKeyCode(KeyEvent.KEYCODE_BUTTON_A)
        )
    }

    @Test
    fun unrelatedKeysAreNotChanged() {
        assertEquals(
            KeyEvent.KEYCODE_DPAD_LEFT,
            mapGameMenuConfirmKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        )
    }

    @Test
    fun submenuBackOptionIsClickableAndKeepsDialogOpen() {
        var navigatedBack = false
        val option = createGameMenuBackOption("Back") {
            navigatedBack = true
        }

        assertTrue(option.isKeepDialog)
        assertTrue(option.runnable != null)
        option.runnable?.run()
        assertTrue(navigatedBack)
    }
}
