package com.limelight.gamemenu

import androidx.compose.ui.Modifier
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.limelight.R

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TouchPointerSensitivityControlTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun touchpadParametersHaveSeparateSlidersAndVerticalNavigation() {
        var sensitivity = 100
        var scroll = 100
        var finished = 0
        lateinit var inputModeManager: InputModeManager
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            TouchPointerSensitivityControl(
                state = TouchPointerSensitivityState(100, true, emptyList(), null),
                onValueChange = { error("Direct-touch callback must not run") },
                onValueChangeFinished = {},
                onSavePreset = {}, onApplyPreset = {}, onManagePresets = {},
                parameters = listOf(
                    SensitivityParameter(R.string.game_menu_touchpad_sensitivity, 100, 1, 1000,
                        { sensitivity = it.toInt(); false }, { finished++ }, "touchpadSpeed"),
                    SensitivityParameter(R.string.game_menu_touchpad_scroll_speed, 100, 1, 500,
                        { scroll = it.toInt(); false }, { finished++ }, "touchpadScroll")
                ),
                onSliderGesture = {}
            )
        }
        composeTestRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        composeTestRule.onNodeWithTag("touchPointerPresetSave").requestFocus()
        composeTestRule.onNodeWithTag("touchPointerPresetSave").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithTag("touchpadSpeed").assertIsFocused()
        composeTestRule.onNodeWithTag("touchpadSpeed").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithTag("touchpadScroll").assertIsFocused()
        composeTestRule.onNodeWithTag("touchpadScroll").performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.runOnIdle {
            assertEquals(100, sensitivity)
            assertEquals(90, scroll)
            assertEquals(1, finished)
        }
        composeTestRule.onNodeWithTag("touchpadScroll").performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithTag("touchpadSpeed").assertIsFocused()
    }

    @Test
    fun dpadAdjustsFocusedSliderAndFinishesOncePerKey() {
        var percent = 100
        var finishedCount = 0
        lateinit var inputModeManager: InputModeManager
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
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
                onSliderGesture = {}
            )
        }

        composeTestRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
        composeTestRule.onNodeWithTag("touchPointerPresetSave").requestFocus().assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPresetSave").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchPointerPreset:preset-50").assertIsFocused()
        composeTestRule.onNodeWithTag("touchPointerPreset:preset-50").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchPointerSensitivity").assertIsFocused().performKeyInput {
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
        var appliedId: String? = null
        var applyCount = 0
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
                    appliedId = id
                    applyCount++
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
            assertEquals("preset-150", appliedId)
            assertEquals(1, applyCount)
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
