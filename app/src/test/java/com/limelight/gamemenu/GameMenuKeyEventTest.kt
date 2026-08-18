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
    fun controllerAndRemoteNavigationKeysRequestMenuFocus() {
        assertTrue(isGameMenuNavigationKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(isGameMenuNavigationKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(isGameMenuNavigationKey(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(isGameMenuNavigationKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(!isGameMenuNavigationKey(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun guideDismissControllerConsumesOnlyTheActiveGuideOnce() {
        val controller = GameMenuGuideDismissController()
        var dismissCount = 0

        controller.register { dismissCount++ }

        assertTrue(controller.dismissIfShowing())
        assertEquals(1, dismissCount)
        assertTrue(!controller.dismissIfShowing())
        assertEquals(1, dismissCount)
    }

    @Test
    fun menuFocusWaitsUntilContentIsLaidOut() {
        assertTrue(
            !shouldRequestGameMenuFocus(
                hardwareFocusRequestToken = 1,
                guideActive = false,
                hasOptions = true,
                menuContentLaidOut = false,
                menuHasFocus = false
            )
        )
        assertTrue(
            shouldRequestGameMenuFocus(
                hardwareFocusRequestToken = 1,
                guideActive = false,
                hasOptions = true,
                menuContentLaidOut = true,
                menuHasFocus = false
            )
        )
    }

    @Test
    fun hardwareNavigationDoesNotResetAnExistingMenuFocus() {
        assertTrue(
            !shouldRequestGameMenuFocus(
                hardwareFocusRequestToken = 2,
                guideActive = false,
                hasOptions = true,
                menuContentLaidOut = true,
                menuHasFocus = true
            )
        )
    }

    @Test
    fun hardwareRefreshDoesNotResetAnExistingGuideFocus() {
        assertTrue(
            !shouldRequestFeatureGuideFocus(
                actionLaidOut = true,
                initialFocusRequested = true,
                guideHasFocus = true
            )
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
