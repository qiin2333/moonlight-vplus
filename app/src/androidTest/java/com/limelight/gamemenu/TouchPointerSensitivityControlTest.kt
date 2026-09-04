package com.limelight.gamemenu

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TouchPointerSensitivityControlTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dpadAdjustsFocusedSliderAndFinishesOncePerKey() {
        var percent = 100
        var finishedCount = 0
        var focused = false
        lateinit var initialFocusRequester: FocusRequester
        lateinit var inputModeManager: InputModeManager
        composeTestRule.setContent {
            initialFocusRequester = remember { FocusRequester() }
            inputModeManager = LocalInputModeManager.current
            Column {
                Box(
                    Modifier
                        .focusRequester(initialFocusRequester)
                        .testTag("focusBeforeSensitivity")
                        .size(48.dp)
                        .focusable()
                )
                TouchPointerSensitivityControl(
                    state = TouchPointerSensitivityState(
                        percent = percent,
                        applicable = true,
                        presets = testPresets(),
                        activePresetId = "preset-100"
                    ),
                    onValueChange = { value ->
                        percent = TouchPointerSensitivityPolicy.normalize(value)
                        false
                    },
                    onValueChangeFinished = { finishedCount++ },
                    onSavePreset = {},
                    onApplyPreset = {},
                    onManagePresets = {},
                    modifier = Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .testTag("touchPointerSensitivity"),
                    onSliderGesture = {}
                )
            }
        }

        composeTestRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            initialFocusRequester.requestFocus()
        }
        composeTestRule.onNodeWithTag("focusBeforeSensitivity").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchPointerPresetSave").assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPresetSave").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchPointerPreset:preset-50").assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPreset:preset-50").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.runOnIdle {
            assertEquals(true, focused)
        }
        composeTestRule.onNodeWithTag("touchPointerSensitivity").performKeyInput {
            pressKey(Key.DirectionLeft)
        }

        composeTestRule.runOnIdle {
            assertEquals(90, percent)
            assertEquals(1, finishedCount)
        }
    }

    @Test
    fun hiddenOutsideSplitDirectTouch() {
        composeTestRule.setContent {
            TouchPointerSensitivityControl(
                    state = TouchPointerSensitivityState(
                        percent = 100,
                        applicable = false,
                        presets = emptyList(),
                        activePresetId = null
                ),
                onValueChange = { false },
                onValueChangeFinished = {},
                onSavePreset = {},
                onApplyPreset = {},
                onManagePresets = {},
                modifier = Modifier.testTag("touchPointerSensitivity"),
                onSliderGesture = {}
            )
        }

        composeTestRule.onAllNodesWithTag("touchPointerSensitivity").assertCountEquals(0)
    }

    @Test
    fun presetButtonAppliesSavedValueOnce() {
        var applied = -1
        lateinit var inputModeManager: InputModeManager
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            TouchPointerSensitivityControl(
                    state = TouchPointerSensitivityState(
                        percent = 100,
                        applicable = true,
                        presets = testPresets(),
                        activePresetId = "preset-100"
                ),
                onValueChange = { false },
                onValueChangeFinished = {},
                onSavePreset = {},
                onApplyPreset = { id ->
                    applied = id.removePrefix("preset-").toInt()
                },
                onManagePresets = {},
                onSliderGesture = {}
            )
        }

        composeTestRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
        composeTestRule.onNodeWithContentDescription("Preset100").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Preset150").assertIsNotSelected()
        composeTestRule.onNodeWithContentDescription("Preset150").requestFocus()
        composeTestRule.onNodeWithContentDescription("Preset150").assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Preset150").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeTestRule.runOnIdle {
            assertEquals(150, applied)
        }
    }

    @Test
    fun equalPresetValuesOnlySelectTheActivePresetId() {
        val duplicateValues = mapOf(
            TouchPointerPresetField.POINTER_SPEED.storageKey to "100"
        )
        composeTestRule.setContent {
            TouchPointerSensitivityControl(
                state = TouchPointerSensitivityState(
                    percent = 100,
                    applicable = true,
                    presets = listOf(
                        TouchPointerSensitivityPreset("preset-a", "Preset A", duplicateValues),
                        TouchPointerSensitivityPreset("preset-b", "Preset B", duplicateValues),
                        TouchPointerSensitivityPreset("preset-c", "Preset C", duplicateValues)
                    ),
                    activePresetId = "preset-b"
                ),
                onValueChange = { false },
                onValueChangeFinished = {},
                onSavePreset = {},
                onApplyPreset = {},
                onManagePresets = {},
                onSliderGesture = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Preset A").assertIsNotSelected()
        composeTestRule.onNodeWithContentDescription("Preset B").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Preset C").assertIsNotSelected()
    }

    @Test
    fun directionalInputReachesPresetActionsAndGrid() {
        lateinit var inputModeManager: InputModeManager
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            TouchPointerSensitivityControl(
                state = TouchPointerSensitivityState(
                    percent = 100,
                    applicable = true,
                    presets = testPresets(),
                    activePresetId = "preset-100"
                ),
                onValueChange = { false },
                onValueChangeFinished = {},
                onSavePreset = {},
                onApplyPreset = {},
                onManagePresets = {},
                modifier = Modifier.testTag("touchPointerSensitivity"),
                onSliderGesture = {}
            )
        }

        composeTestRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
        composeTestRule.onNodeWithTag("touchPointerPresetSave").requestFocus()
        composeTestRule.onNodeWithTag("touchPointerPresetSave").assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPresetSave").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("touchPointerPresetManage").assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPresetManage").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchPointerPreset:preset-100").assertIsFocused()
    }

    private fun testPresets(): List<TouchPointerSensitivityPreset> = listOf(50, 100, 150).map {
        TouchPointerSensitivityPreset(
            id = "preset-$it",
            name = "Preset$it",
            values = mapOf(TouchPointerPresetField.POINTER_SPEED.storageKey to it.toString())
        )
    }
}
