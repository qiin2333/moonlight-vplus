package com.limelight.gamemenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import com.limelight.preferences.DisplayChoiceLists
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
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

    @Test
    fun rapidBitratePreviewsDoNotMoveTheBottomAnchoredCardAtCompactWidthAndLargeText() {
        var state by mutableStateOf(displayState("1920x1080").copy(adaptiveBitrate = true))
        composeTestRule.setContent {
            CompactMenuFixture {
                Box(Modifier.testTag("bitrateCardBounds")) {
                    BitrateCard(state, displayCallbacks(), onSliderGesture = {}, onConfigure = {})
                }
            }
        }
        val card = bounds("bitrateCardBounds")
        val readout = bounds("bitrateReadout")
        val slider = bounds("bitrateSlider")
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val modeIndex = resources.getStringArray(R.array.abr_mode_values).indexOf("balanced")
        val modeLabel = resources.getStringArray(R.array.abr_mode_names)[modeIndex]
        val modeLayouts = mutableListOf<TextLayoutResult>()
        val modeLayoutAction = composeTestRule.onNodeWithText(modeLabel, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        assertTrue(modeLayoutAction.action?.invoke(modeLayouts) == true)
        assertVisibleLine("ABR mode", modeLayouts.single())
        val modeBounds = composeTestRule.onNodeWithText(modeLabel).getUnclippedBoundsInRoot()
        val cardBounds = composeTestRule.onNodeWithTag("bitrateCardBounds", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("The mode action, including its arrow, stays inside the card",
            modeBounds.left >= cardBounds.left && modeBounds.right <= cardBounds.right &&
                modeBounds.top >= cardBounds.top && modeBounds.bottom <= cardBounds.bottom)

        for (progress in listOf(0f, 9f, 24f, 34f, 49f, 59f)) {
            composeTestRule.runOnIdle { state = state.copy(progress = progress) }
            assertEquals("Preview must not resize or move the card", card, bounds("bitrateCardBounds"))
            assertEquals("Preview must not move the slider", slider, bounds("bitrateSlider"))
            assertVerticalPositionUnchanged(readout, bounds("bitrateReadout"))
            composeTestRule.onNodeWithTag("bitrateReadout", useUnmergedTree = true)
                .assertTextEquals(BitrateCardController.formatBitrateMbps(state.selectedBitrateKbps))
            val layouts = mutableListOf<TextLayoutResult>()
            val action = composeTestRule.onNodeWithTag("bitrateReadout", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            assertTrue(action.action?.invoke(layouts) == true)
            assertVisibleLine("Bitrate", layouts.single())
        }
        val selectedReadout = bounds("bitrateReadout")
        composeTestRule.runOnIdle { state = state.copy(bitrateUpdating = true) }
        composeTestRule.onNodeWithTag("bitrateUpdating").assertIsDisplayed()
        assertEquals(card, bounds("bitrateCardBounds"))
        assertEquals(slider, bounds("bitrateSlider"))
        assertEquals("Submitting must not move the value", selectedReadout, bounds("bitrateReadout"))
        composeTestRule.runOnIdle {
            state = state.copy(bitrateUpdating = false)
        }
        composeTestRule.onNodeWithTag("bitrateUpdating").assertDoesNotExist()
        assertEquals(card, bounds("bitrateCardBounds"))
        assertEquals(slider, bounds("bitrateSlider"))
        assertEquals("Confirming must not move the value", selectedReadout, bounds("bitrateReadout"))
    }

    @Test
    fun displayScaleUsesHostStopsAndCommitsOnlyOnReleaseWhileIgnoringInputDuringApply() {
        val submitted = mutableListOf<Int>()
        var state by mutableStateOf(displayState("1920x1080").copy(
            displayScale = DisplayScaleState(
                available = true,
                currentPercent = 125,
                recommendedPercent = 125,
                supportedPercents = listOf(100, 125, 175, 200)
            )
        ))
        val callbacks = displayCallbacks(onSetDisplayScale = { percent ->
            submitted += percent
            state = state.copy(displayScale = state.displayScale.copy(
                loading = true,
                pendingPercent = percent
            ))
        })
        composeTestRule.setContent {
            CompactMenuFixture {
                DisplayStatusCard(state, callbacks, onConfigure = {}, onSliderGesture = {})
            }
        }
        val slider = composeTestRule.onNodeWithTag("displayScaleSlider")
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(1f, 0f..3f, steps = 2)
        ))
        slider.requestFocus()
        slider.performKeyInput { keyDown(Key.DirectionRight) }
        composeTestRule.runOnIdle { assertTrue("Key-down only previews", submitted.isEmpty()) }
        slider.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(2f, 0f..3f, steps = 2)
        ))
        slider.performKeyInput { keyUp(Key.DirectionRight) }
        composeTestRule.runOnIdle { assertEquals(listOf(175), submitted) }

        slider.performKeyInput { pressKey(Key.DirectionRight) }
        slider.performTouchInput { swipeRight() }
        composeTestRule.runOnIdle {
            assertEquals("Applying ignores both controller and touch edits", listOf(175), submitted)
        }

        composeTestRule.runOnIdle {
            state = state.copy(displayScale = state.displayScale.copy(
                loading = false,
                pendingPercent = null,
                currentPercent = 175
            ))
        }
        slider.performTouchInput {
            down(center)
            moveTo(centerRight)
        }
        composeTestRule.runOnIdle { assertEquals("Dragging only previews", listOf(175), submitted) }
        slider.performTouchInput { up() }
        composeTestRule.runOnIdle { assertEquals(listOf(175, 200), submitted) }
    }

    @Test
    fun currentScreenPolicyRemainsVisibleWhenItIsNotAQuickAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels = DisplayChoiceLists.labelLookup(
            context.resources, R.array.screen_combination_mode_names, R.array.screen_combination_mode_values
        )
        val label = requireNotNull(labels["-1"])
        val state = displayState("1920x1080").copy(
            screenModes = emptyList(),
            screenModeLabel = label
        )
        val opened = AtomicInteger(0)
        composeTestRule.setContent {
            CompactMenuFixture {
                DisplayStatusCard(
                    state = state,
                    callbacks = displayCallbacks(onOpenScreenModeSheet = { opened.incrementAndGet() }),
                    onConfigure = {},
                    onSliderGesture = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("${context.getString(R.string.display_spec_screen)}, $label")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, opened.get())
        }
    }

    @Composable
    private fun CompactMenuFixture(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            GameMenuTheme {
                Box(Modifier.fillMaxSize()) {
                    GameMenuDialogShell(widthFraction = 1f, horizontalInset = 16.dp, onDismissRequest = {}) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(300.dp)) { content() }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = composeTestRule
        .onNodeWithTag(tag, useUnmergedTree = true)
        .assertIsDisplayed()
        .fetchSemanticsNode().boundsInRoot

    private fun assertVerticalPositionUnchanged(expected: Rect, actual: Rect) {
        assertEquals("Readout top remains stable", expected.top, actual.top, 0.5f)
        assertEquals("Readout bottom remains stable", expected.bottom, actual.bottom, 0.5f)
    }

    private fun assertVisibleLine(label: String, layout: TextLayoutResult) {
        // Intrinsic measurement may retain a wider paragraph constraint. Check
        // the actual line bounds instead of that intermediate width.
        assertEquals("$label uses one line", 1, layout.lineCount)
        assertFalse("$label is not ellipsized", layout.isLineEllipsized(0))
        assertTrue("$label fits horizontally: ${layout.getLineRight(0)} > ${layout.size.width}",
            layout.getLineRight(0) <= layout.size.width + 0.5f)
        assertTrue("$label fits vertically: ${layout.getLineBottom(0)} > ${layout.size.height}",
            layout.getLineBottom(0) <= layout.size.height + 0.5f)
    }

    private fun displayState(selected: String): BitrateCardState {
        return BitrateCardState(
            progress = 24f,
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
        onOpenResolutionSheet: () -> Unit = {},
        onOpenFrameRateSheet: () -> Unit = {},
        onOpenScreenModeSheet: () -> Unit = {},
        onSetDisplayScale: (Int) -> Unit = {},
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
        onSetDisplayScale = onSetDisplayScale,
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
