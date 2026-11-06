package com.limelight.utils

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSliderInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discreteRepeatClampsAtBothEndsAndDisabledInputDoesNothing() {
        var value by mutableFloatStateOf(8f)
        var enabled by mutableStateOf(true)
        val changes = mutableListOf<Float>()
        var finishes = 0
        compose.setContent {
            AppSlider(value, { value = it; changes += it }, Modifier.width(240.dp).testTag("slider"),
                enabled = enabled, valueRange = 0f..10f, steps = 4, onValueChangeFinished = { finishes++ })
        }
        compose.onNodeWithTag("slider").requestFocus().assertIsFocused()
        for (repeat in 0..2) key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
        compose.runOnIdle { assertEquals(0, finishes) }
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        for (repeat in 0..5) key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
        compose.runOnIdle {
            assertEquals(listOf(10f, 8f, 6f, 4f, 2f, 0f), changes)
            assertEquals(2, finishes)
            enabled = false
        }
        compose.onNodeWithTag("slider").assertIsNotEnabled()
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(0f, value, 0f); assertEquals(2, finishes) }
    }

    @Test fun rtlReversesDirectionAndFocusLossFinishesOnlyTheSliderBeingEdited() {
        var value by mutableFloatStateOf(6f)
        var firstFinishes = 0
        var secondFinishes = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.width(240.dp)) {
                    AppSlider(value, { value = it }, Modifier.testTag("first"), valueRange = 0f..10f,
                        steps = 4, onValueChangeFinished = { firstFinishes++ })
                    AppSlider(6f, {}, Modifier.testTag("second"), valueRange = 0f..10f,
                        steps = 4, onValueChangeFinished = { secondFinishes++ })
                }
            }
        }
        compose.onNodeWithTag("first").requestFocus().assertIsFocused()
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(4f, value, 0f); assertEquals(1, firstFinishes) }
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("second").requestFocus().assertIsFocused()
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
        compose.runOnIdle {
            assertEquals(6f, value, 0f)
            assertEquals(2, firstFinishes)
            assertEquals(0, secondFinishes)
        }
    }

    private fun key(action: Int, code: Int, repeat: Int = 0) {
        val now = SystemClock.uptimeMillis()
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(now, now, action, code, repeat))
        compose.waitForIdle()
    }
}
