package com.limelight.gamemenu

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun displaySummaryRowsOpenIndependentChoiceActions() {
        val selectionCount = AtomicInteger(0)

        composeTestRule.setContent {
            val guideDismissController = remember { GameMenuGuideDismissController() }
            GameMenuScreen(
                state = displayState("1920x1080"),
                callbacks = displayCallbacks(
                    onOpenResolutionSheet = {
                        selectionCount.incrementAndGet()
                    }
                ),
                hardwareFocusRequestToken = 1,
                guideDismissController = guideDismissController,
                useFabricTexture = false,
                restoreFocusRequestToken = 0
            )
        }

        composeTestRule.onNodeWithContentDescription("Resolution, 1080p")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals("Resolution summary is an independent action", 1, selectionCount.get())
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
            isSubmenu = false,
            pageLayout = GameMenuPageLayout.STANDARD,
            pageGeneration = 0,
            controllerNavigationActive = true
        )
    }

    private fun displayCallbacks(
        onOpenResolutionSheet: () -> Unit
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
