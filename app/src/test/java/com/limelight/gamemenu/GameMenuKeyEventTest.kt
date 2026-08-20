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
        assertTrue(isGameMenuNavigationKey(GAME_MENU_KEYCODE_DPAD_UP_LEFT))
        assertEquals(
            KeyEvent.KEYCODE_DPAD_UP,
            mapGameMenuConfirmKeyCode(GAME_MENU_KEYCODE_DPAD_UP_RIGHT)
        )
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

    @Test
    fun touchModeFocusGraphHandlesOddRowsAndCrossSectionNavigation() {
        assertEquals(
            TouchModeFocusTargets(left = null, right = 1, up = null, down = 2),
            touchModeFocusTargets(primaryCount = 3, compatibleCount = 2, globalIndex = 0)
        )
        assertEquals(
            TouchModeFocusTargets(left = 0, right = null, up = null, down = 2),
            touchModeFocusTargets(primaryCount = 3, compatibleCount = 2, globalIndex = 1)
        )
        assertEquals(
            TouchModeFocusTargets(left = null, right = null, up = 0, down = 3),
            touchModeFocusTargets(primaryCount = 3, compatibleCount = 2, globalIndex = 2)
        )
        assertEquals(
            TouchModeFocusTargets(left = null, right = 4, up = 2, down = null),
            touchModeFocusTargets(primaryCount = 3, compatibleCount = 2, globalIndex = 3)
        )
        assertEquals(
            TouchModeFocusTargets(left = 3, right = null, up = 2, down = null),
            touchModeFocusTargets(primaryCount = 3, compatibleCount = 2, globalIndex = 4)
        )
    }

    @Test
    fun touchModeFocusGraphPreservesColumnsWhenBothSectionsHaveFullRows() {
        assertEquals(
            5,
            touchModeFocusTargets(primaryCount = 4, compatibleCount = 3, globalIndex = 3).down
        )
        assertEquals(
            3,
            touchModeFocusTargets(primaryCount = 4, compatibleCount = 3, globalIndex = 5).up
        )
        assertEquals(
            6,
            touchModeFocusTargets(primaryCount = 4, compatibleCount = 3, globalIndex = 4).down
        )
    }

    @Test
    fun childDialogFocusRestoreWaitsForParentLayoutAndGuideDismissal() {
        assertTrue(
            !shouldRestoreGameMenuFocus(
                restoreFocusRequestToken = 1,
                guideActive = false,
                menuContentLaidOut = false,
                menuHasFocus = false
            )
        )
        assertTrue(
            !shouldRestoreGameMenuFocus(
                restoreFocusRequestToken = 1,
                guideActive = true,
                menuContentLaidOut = true,
                menuHasFocus = false
            )
        )
        assertTrue(
            shouldRestoreGameMenuFocus(
                restoreFocusRequestToken = 1,
                guideActive = false,
                menuContentLaidOut = true,
                menuHasFocus = false
            )
        )
        assertTrue(
            !shouldRestoreGameMenuFocus(
                restoreFocusRequestToken = 1,
                guideActive = false,
                menuContentLaidOut = true,
                menuHasFocus = true
            )
        )
    }

}
