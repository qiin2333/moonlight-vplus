package com.limelight.gamemenu

import android.app.Dialog
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuOpacityEditorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null

    @After
    fun dismissDialog() {
        composeTestRule.runOnUiThread { dialog?.dismiss() }
    }

    @Test
    fun sliderReceivesInitialFocusAndSupportsControllerAdjustment() {
        var previewOpacity = 90
        var persistedOpacity: Int? = null
        composeTestRule.runOnUiThread {
            dialog = GameMenuOpacityEditor.show(
                context = composeTestRule.activity,
                initialOpacity = 90,
                onOpacityChange = { previewOpacity = it },
                onOpacityChangeFinished = { persistedOpacity = it }
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { dialog?.isShowing == true }
        composeTestRule.onNodeWithTag("gameMenuOpacitySlider").assertIsFocused()
        composeTestRule.onNodeWithTag("gameMenuOpacitySlider").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { previewOpacity == 85 }
        composeTestRule.onNodeWithText("85%").assertIsDisplayed()

        composeTestRule.onNodeWithTag("gameMenuOpacitySlider").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("gameMenuOpacityDone").assertIsFocused()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)

        composeTestRule.waitUntil(timeoutMillis = 5_000) { dialog?.isShowing == false }
        assertEquals(85, persistedOpacity)
    }
}
