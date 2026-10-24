package com.limelight.gamemenu

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameMenuDisplaySummaryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaySummaryRowsOpenIndependentChoiceActions() {
        val resolutionCount = AtomicInteger(0)
        val frameRateCount = AtomicInteger(0)
        val screenModeCount = AtomicInteger(0)
        val resolutionLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.display_spec_resolution)
        val frameRateLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.display_spec_fps)
        val screenModeLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.display_spec_screen)

        composeTestRule.setContent {
            DisplayStatusCard(
                state = displayState("1920x1080"),
                callbacks = displayCallbacks(
                    onOpenResolutionSheet = {
                        resolutionCount.incrementAndGet()
                    },
                    onOpenFrameRateSheet = {
                        frameRateCount.incrementAndGet()
                    },
                    onOpenScreenModeSheet = {
                        screenModeCount.incrementAndGet()
                    },
                ),
                onConfigure = {},
                onSliderGesture = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("$resolutionLabel, 1080p")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals("Resolution row opens resolution actions", 1, resolutionCount.get())
            assertEquals("Resolution row does not open frame-rate actions", 0, frameRateCount.get())
            assertEquals("Resolution row does not open screen-mode actions", 0, screenModeCount.get())
        }
        composeTestRule.onNodeWithContentDescription("$frameRateLabel, 60 FPS")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals("Frame-rate row keeps resolution count unchanged", 1, resolutionCount.get())
            assertEquals("Frame-rate row opens frame-rate actions", 1, frameRateCount.get())
            assertEquals("Frame-rate row does not open screen-mode actions", 0, screenModeCount.get())
        }
        composeTestRule.onNodeWithContentDescription("$screenModeLabel, Single display")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals("Screen-mode row keeps resolution count unchanged", 1, resolutionCount.get())
            assertEquals("Screen-mode row keeps frame-rate count unchanged", 1, frameRateCount.get())
            assertEquals("Screen-mode row opens screen-mode actions", 1, screenModeCount.get())
        }
        composeTestRule.runOnIdle {
            assertEquals("Resolution is an independent action", 1, resolutionCount.get())
            assertEquals("Frame rate is an independent action", 1, frameRateCount.get())
            assertEquals("Screen mode is an independent action", 1, screenModeCount.get())
        }
    }

    private fun displayState(selected: String): BitrateCardState {
        return BitrateCardState(
            progress = 24f,
            currentBitrateKbps = 20_000,
            maxProgress = BitrateCardController.MAX_PROGRESS,
            maxBitrateKbps = 200_000,
            adaptiveBitrate = false,
            abrMode = "balanced",
            resolutionLabel = "1080p",
            fpsLabel = "60 FPS",
            resolutions = listOf(
                DisplayChoice("1280x720", "720p", selected == "1280x720", true, "1280×720"),
                DisplayChoice("1920x1080", "1080p", selected == "1920x1080", true, "1920×1080"),
                DisplayChoice("2560x1440", "1440p", selected == "2560x1440", true, "2560×1440")
            ),
            frameRates = listOf(DisplayChoice("60", "60", true, true, "FPS")),
            screenModes = listOf(DisplayChoice("2", "Single display", true, true)),
            hapticMode = BitrateCardController.HapticMode.NONE
        )
    }

    private fun displayCallbacks(
        onOpenResolutionSheet: () -> Unit,
        onOpenFrameRateSheet: () -> Unit = {},
        onOpenScreenModeSheet: () -> Unit = {},
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
        onOpenResolutionSheet = onOpenResolutionSheet,
        onOpenFrameRateSheet = onOpenFrameRateSheet,
        onOpenScreenModeSheet = onOpenScreenModeSheet,
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
