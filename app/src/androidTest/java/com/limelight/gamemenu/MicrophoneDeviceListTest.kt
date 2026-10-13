package com.limelight.gamemenu

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.limelight.utils.AppActionSheet

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MicrophoneDeviceListTest {
    @get:Rule val compose = createComposeRule()
    private val initial = MicrophoneMenuState(listOf(
        MicrophoneMenuChoice("default", "System default"),
        MicrophoneMenuChoice("usb", "USB microphone")), "usb", "Muted")

    @Test fun currentDeviceGetsInitialFocusAndRefreshDoesNotResetUserNavigation() {
        val state = mutableStateOf(initial)
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                Column { MicrophoneDeviceList(state.value, {}, remember { FocusRequester() }, remember { FocusRequester() }) }
            }
        }
        compose.onNodeWithTag("microphoneInput:usb").assertIsFocused().assertIsSelected()
        compose.onNodeWithTag("microphoneInput:usb").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("microphoneInput:default").assertIsFocused()
        compose.runOnIdle { state.value = initial.copy(status = "Recording") }
        compose.onNodeWithTag("microphoneInput:default").assertIsFocused()
    }

    @Test fun touchAndGamepadConfirmActivateExactlyOnce() {
        val selections = mutableListOf<String>()
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                MicrophoneDeviceList(initial, { selections.add(it) }, remember { FocusRequester() }, remember { FocusRequester() })
            }
        }
        compose.onNodeWithTag("microphoneInput:default").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("default"), selections) }
        compose.onNodeWithTag("microphoneInput:usb").requestFocus()
        compose.onNodeWithTag("microphoneInput:usb").performKeyInput { pressKey(Key.ButtonA) }
        compose.runOnIdle { assertEquals(listOf("default", "usb"), selections) }
    }

    @Test fun removalOfFocusedInputRestoresFocusToDefault() {
        val state = mutableStateOf(initial)
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                MicrophoneDeviceList(state.value, {}, remember { FocusRequester() }, remember { FocusRequester() })
            }
        }
        compose.onNodeWithTag("microphoneInput:usb").assertIsFocused()
        compose.runOnIdle { state.value = initial.copy(choices = initial.choices.take(1)) }
        compose.onNodeWithTag("microphoneInput:default").assertIsFocused()
    }

    @Test fun relabelingAndReorderingKeepFocusAndSelectionOnTheSameAttachment() {
        val state = mutableStateOf(initial)
        compose.setContent {
            AppActionSheet.AppActionSheetTheme {
                MicrophoneDeviceList(state.value, {}, remember { FocusRequester() }, remember { FocusRequester() })
            }
        }
        compose.onNodeWithTag("microphoneInput:usb").assertIsFocused().assertIsSelected()
        compose.runOnIdle {
            state.value = initial.copy(choices = initial.choices.reversed().map { choice ->
                choice.copy(name = choice.name + " (1)")
            })
        }
        compose.onNodeWithTag("microphoneInput:usb").assertIsFocused().assertIsSelected()
    }
}
