package com.limelight.utils

import android.app.Dialog
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AppActionSheetControllerFocusTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null

    @After
    fun dismissDialog() {
        composeTestRule.runOnUiThread { dialog?.dismiss() }
    }

    @Test
    fun forcedControllerModeFocusesFirstMultiSelectAction() {
        composeTestRule.runOnUiThread {
            dialog = AppActionSheet.showMultiSelect(
                context = composeTestRule.activity,
                title = "Delete custom keys",
                actions = listOf(
                    AppActionSheet.Action(0, "First key", checked = false),
                    AppActionSheet.Action(1, "Second key", checked = false)
                ),
                confirmLabel = "Delete",
                cancelLabel = "Cancel",
                minimumSelectionCount = 1,
                onConfirm = {},
                forceInitialFocus = true
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { dialog?.isShowing == true }
        composeTestRule.onNodeWithText("First key").assertIsFocused()
        composeTestRule.onNodeWithText("First key").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithText("Second key").assertIsFocused()

        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(1)
    }
}
