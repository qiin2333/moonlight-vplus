package com.limelight.gamemenu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuDisplayGridFocusTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun directionalBrowsingKeepsSelectionUntilConfirmationAndCustomActionIsReachable() {
        val selectionCount = AtomicInteger(0)
        val customActionCount = AtomicInteger(0)
        val customLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.title_custom_resolutions)

        composeTestRule.setContent {
            var selected by remember { mutableStateOf("1920x1080") }
            val guideDismissController = remember { GameMenuGuideDismissController() }
            GameMenuScreen(
                state = displayState(selected),
                callbacks = displayCallbacks(
                    onSelectResolution = {
                        selectionCount.incrementAndGet()
                        selected = it
                    },
                    onEditCustomResolutions = { customActionCount.incrementAndGet() }
                ),
                hardwareFocusRequestToken = 1,
                guideDismissController = guideDismissController,
                useFabricTexture = false,
                restoreFocusRequestToken = 0
            )
        }

        val current = composeTestRule.onNodeWithText("1080p")
        val next = composeTestRule.onNodeWithText("1440p")
        val custom = composeTestRule.onNodeWithContentDescription(customLabel)
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        // The saved value is the second tile, so this also catches first-item focus regressions.
        current.assertIsFocused().assertIsSelected().assert(radioRole)
        composeTestRule.onNodeWithText("720p").assertIsNotFocused().assertIsNotSelected()
        current.performKeyInput { pressKey(Key.DirectionRight) }

        next.assertIsFocused().assertIsNotSelected().assert(radioRole)
        current.assertIsNotFocused().assertIsSelected()
        assertEquals("Browsing must not stage a resolution change", 0, selectionCount.get())
        next.performKeyInput { pressKey(Key.DirectionCenter) }

        next.assertIsFocused().assertIsSelected()
        current.assertIsNotSelected()
        assertEquals("Confirmation stages exactly one change", 1, selectionCount.get())

        // This action stays a button, outside the resolution radio selection.
        custom.requestFocus()
        custom.assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        next.assertIsSelected()
        custom.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, customActionCount.get())
        assertEquals("Opening custom management must not select a resolution", 1, selectionCount.get())
    }

    private fun displayState(selected: String): GameMenuComposeUiState {
        val applied = DisplaySettingsDraft("1920x1080", "60", "2")
        return GameMenuComposeUiState(
            title = "Display settings",
            options = emptyList(),
            superOptions = emptyList(),
            appName = "",
            crownToggleText = "",
            gameMenuOpacity = 100,
            deviceQuickOptions = emptyList(),
            quickActions = emptyList(),
            visibleCards = GameMenuVisibleCards(
                bitrate = false,
                display = true,
                audioHaptics = false,
                gyro = false,
                shortcuts = false
            ),
            bitrate = BitrateCardState(
                appliedDisplay = applied,
                displayDraft = DisplaySettingsDraft(selected, "60", "2"),
                progress = 24f,
                currentBitrateKbps = 20_000,
                maxProgress = BitrateCardController.MAX_PROGRESS,
                maxBitrateKbps = 200_000,
                adaptiveBitrate = false,
                abrMode = "balanced",
                resolutionLabel = "1080p",
                fpsLabel = "60 FPS",
                resolutions = listOf(
                    DisplayChoice("1280x720", "720p", selected == "1280x720", true, "1280×720", null),
                    DisplayChoice("1920x1080", "1080p", selected == "1920x1080", true, "1920×1080", null),
                    DisplayChoice("2560x1440", "1440p", selected == "2560x1440", true, "2560×1440", null)
                ),
                frameRates = listOf(DisplayChoice("60", "60", true, true, "FPS", null)),
                screenModes = listOf(DisplayChoice("2", "Single display", true, true, null, null)),
                hapticMode = BitrateCardController.HapticMode.NONE
            ),
            audioHaptics = AudioHapticsCardState(false, 0, "", 0, false),
            waveformHaptics = WaveformHapticsCardState(emptyList()),
            gyro = GyroCardState(false, false, "", 1f, false, false),
            touchPointerSensitivity = TouchPointerSensitivityState(100, false, emptyList(), null),
            customKeys = emptyList(),
            quickEditMode = false,
            usbForwardingEnabled = false,
            isSubmenu = true,
            pageLayout = GameMenuPageLayout.DISPLAY_SETTINGS,
            pageGeneration = 0,
            controllerNavigationActive = true
        )
    }

    private fun displayCallbacks(
        onSelectResolution: (String) -> Unit,
        onEditCustomResolutions: () -> Unit
    ) = GameMenuCallbacks(
        onDismiss = {},
        onUsbDevices = {},
        onHapticFeedback = {},
        iconForOption = { 0 },
        onBack = {},
        onCrownToggle = {},
        onEditOpacity = {},
        onOptionClick = {},
        onTouchInteraction = {},
        onInlineToggle = {},
        onSegmentClick = {},
        onEmptySuperCommandClick = {},
        onQuickAction = {},
        onToggleQuickEdit = {},
        onAddQuickAction = {},
        onRemoveQuickAction = {},
        onMoveQuickAction = { _, _ -> },
        onEditCards = {},
        onBitrateProgress = { false },
        onBitrateApply = {},
        onBitrateAdaptive = {},
        onAbrMode = {},
        onBitrateHapticMode = {},
        onOpenDisplaySettings = {},
        onSelectResolution = onSelectResolution,
        onSelectFrameRate = {},
        onSelectScreenMode = {},
        onApplyDisplaySettings = {},
        onCancelDisplaySettings = {},
        onEditCustomResolutions = onEditCustomResolutions,
        onAudioHapticsEnabled = {},
        onAudioHapticsStrength = { false },
        onAudioHapticsStrengthFinished = {},
        onAudioHapticsMode = {},
        onAudioHapticsScene = {},
        onAudioHapticsReset = {},
        onGyroEnabled = {},
        onGyroMouseMode = {},
        onGyroActivationKey = {},
        onGyroSensitivity = {},
        onGyroSensitivityFinished = {},
        onGyroInvertX = {},
        onGyroInvertY = {},
        onTouchPointerSensitivity = { false },
        onTouchPointerSensitivityFinished = {},
        onSaveTouchPointerSensitivityPreset = {},
        onApplyTouchPointerSensitivityPreset = {},
        onManageTouchPointerSensitivityPresets = {},
        onCustomKey = {},
        onWaveformTest = { _, _ -> }
    )
}
