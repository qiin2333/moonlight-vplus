package com.limelight.gamemenu

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuControllerLayoutFocusTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wideTouchModeSplitPanesUseExplicitDirectionsAndReturnToHeader() {
        composeTestRule.setContent {
            val topFocusRequester = remember { FocusRequester() }
            val focusRequesters = remember { List(9) { FocusRequester() } }
            Column {
                Box(
                    Modifier
                        .testTag("touchModeBack")
                        .focusRequester(topFocusRequester)
                        .focusProperties { down = focusRequesters[0] }
                        .size(20.dp)
                        .focusable()
                )
                repeat(9) { index ->
                    Box(
                        Modifier
                            .testTag("touchMode$index")
                            .touchModeFocusNavigation(
                                targets = touchModeFocusTargets(
                                    primaryCount = 5,
                                    compatibleCount = 4,
                                    globalIndex = index
                                ),
                                focusRequesters = focusRequesters,
                                topFocusRequester = topFocusRequester
                            )
                            .focusRequester(focusRequesters[index])
                            .size(20.dp)
                            .focusable()
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("touchMode0").requestFocus()
        composeTestRule.onNodeWithTag("touchMode0").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("touchMode5").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode5").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode6").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode6").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithTag("touchMode1").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode1").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode2").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode2").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("touchMode7").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode7").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode8").assertIsFocused()
        composeTestRule.onNodeWithTag("touchMode8").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithTag("touchMode3").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode0").requestFocus()
        composeTestRule.onNodeWithTag("touchMode0").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithTag("touchModeBack").assertIsFocused()

        composeTestRule.onNodeWithTag("touchModeBack").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode0").assertIsFocused()
    }

    @Test
    fun fiveSegmentControlUsesOneHorizontalFocusRow() {
        composeTestRule.setContent {
            val focusRequesters = remember { List(5) { FocusRequester() } }
            Column {
                repeat(5) { index ->
                    Box(
                        Modifier
                            .testTag("segment$index")
                            .focusRequester(focusRequesters[index])
                            .segmentedFocusNavigation(
                                targets = segmentedFocusTargets(
                                    itemCount = 5,
                                    index = index,
                                    columnCount = 5
                                ),
                                focusRequesters = focusRequesters
                            )
                            .size(20.dp)
                            .focusable()
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("segment2").requestFocus()
        composeTestRule.onNodeWithTag("segment2").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("segment3").assertIsFocused()
        composeTestRule.onNodeWithTag("segment3").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("segment4").assertIsFocused()
        composeTestRule.onNodeWithTag("segment4").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithTag("segment3").assertIsFocused()
    }

    @Test
    fun fiveEnglishSegmentLabelsFitOneRowAtCompactWidth() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.3f)
            ) {
                InlineSegmentedControl(
                    segments = listOf("Touch", "Mouse", "Pad", "Ext.", "DS5").mapIndexed { index, label ->
                        GameMenu.SegmentOption(label, index == 0, Runnable {})
                    },
                    onSegmentClick = {},
                    modifier = Modifier.width(164.dp).height(36.dp)
                )
            }
        }

        repeat(5) { index ->
            val layoutResults = mutableListOf<TextLayoutResult>()
            val action = composeTestRule.onNodeWithTag(
                "inlineSegmentLabel$index",
                useUnmergedTree = true
            )
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
            assertTrue(action.action?.invoke(layoutResults) == true)
            assertFalse("Segment $index overflows at compact width", layoutResults.single().hasVisualOverflow)
        }
    }

    @Test
    fun compatibleToggleRowOwnsFocusWhenInlineControlDoesNot() {
        lateinit var rowFocusRequester: FocusRequester
        var toggledControl: GameMenu.InlineControl.Toggle? = null
        var toggleCount = 0
        composeTestRule.setContent {
            rowFocusRequester = remember { FocusRequester() }
            MenuOptionColumn(
                options = listOf(
                    GameMenu.MenuOption(
                        label = "Local cursor",
                        isWithGameFocus = false,
                        runnable = null,
                        iconKey = null,
                        isShowIcon = false,
                        isKeepDialog = false,
                        inlineControl = GameMenu.InlineControl.Toggle(
                            checked = false,
                            toggleAction = Runnable {}
                        )
                    )
                ),
                iconForOption = { 0 },
                onOptionClick = {},
                onInlineToggle = { toggleCount++; toggledControl = it },
                onSegmentClick = {},
                initialFocusRequester = rowFocusRequester,
                optionFocusModifier = Modifier.testTag("compatibleToggle"),
                inlineControlsFocusable = false
            )
        }

        var focusRequestSucceeded = false
        composeTestRule.runOnIdle {
            focusRequestSucceeded = rowFocusRequester.requestFocus()
        }
        assertTrue(focusRequestSucceeded)
        composeTestRule.onNodeWithTag("compatibleToggle").assertIsFocused()
        composeTestRule.onNodeWithTag("compatibleToggle").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.runOnIdle {
            assertEquals(1, toggleCount)
            assertEquals(false, toggledControl?.checked)
        }
    }

    @Test
    fun performanceOptionsNavigateAndToggleWithoutLosingFocus() {
        var enabled by mutableStateOf(false)
        var style by mutableStateOf(0)
        var toggleCount = 0
        composeTestRule.setContent {
            val first = remember { FocusRequester() }
            val styleOptions = listOf("Mini", "Standard", "Detailed", "Classic").mapIndexed { index, label ->
                GameMenu.SegmentOption(label, index == style, Runnable { style = index })
            }
            fun option(label: String, action: () -> Unit, control: GameMenu.InlineControl) =
                GameMenu.MenuOption(label, false, Runnable(action), null, false, true, inlineControl = control)

            MenuOptionColumn(
                options = listOf(
                    option("Style", {}, GameMenu.InlineControl.Segmented(styleOptions)),
                    option("Layout", {}, GameMenu.InlineControl.Segmented(
                        listOf(GameMenu.SegmentOption("Horizontal", true, Runnable {}),
                            GameMenu.SegmentOption("Vertical", false, Runnable {}))
                    )),
                    GameMenu.MenuOption("Metrics", false, Runnable {}, null, false, true),
                    option("Show health", { enabled = !enabled; toggleCount++ },
                        GameMenu.InlineControl.Toggle(enabled)),
                    option("Tap gesture", {}, GameMenu.InlineControl.Toggle(false))
                ),
                iconForOption = { 0 },
                onOptionClick = { it.runnable?.run() },
                onInlineToggle = { it.toggleAction?.run() },
                onSegmentClick = { it.runnable.run() },
                initialFocusRequester = first,
                inlineControlsFocusable = false,
                linearNavigation = true
            )
        }

        composeTestRule.onNodeWithText("Mini").requestFocus()
        composeTestRule.onNodeWithText("Mini").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.onNodeWithText("Standard").assertIsFocused()
        composeTestRule.onNodeWithText("Standard").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.runOnIdle { assertEquals(1, style) }
        composeTestRule.onNodeWithText("Standard").assertIsFocused()
        composeTestRule.onNodeWithText("Standard").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithContentDescription("Horizontal").assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Horizontal").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Metrics").assertIsFocused()
        composeTestRule.onNodeWithText("Metrics").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Show health").assertIsFocused()
        composeTestRule.onNodeWithText("Show health").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.onNodeWithText("Show health").assertIsOn().assertIsFocused()
        composeTestRule.runOnIdle { assertEquals(1, toggleCount) }
        composeTestRule.onNodeWithText("Show health").performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("Metrics").assertIsFocused()
        composeTestRule.onNodeWithText("Show health").performTouchInput { click() }
        composeTestRule.runOnIdle { assertEquals(2, toggleCount) }
    }

    @Test
    fun performanceEntrySeparatesSubmenuFromModeControl() {
        var entryCount = 0
        var modeCount = 0
        var selected by mutableStateOf(0)
        composeTestRule.setContent {
            val entry = GameMenu.MenuOption(
                label = "Performance",
                isWithGameFocus = false,
                runnable = Runnable { entryCount++ },
                iconKey = null,
                isShowIcon = false,
                isKeepDialog = true,
                showChevron = true,
                focusKey = PERF_ENTRY_FOCUS_KEY,
                inlineControl = GameMenu.InlineControl.Segmented(
                    listOf("Hidden", "Floating", "Pinned").mapIndexed { index, label ->
                        GameMenu.SegmentOption(label, selected == index, Runnable {
                            modeCount++
                            selected = index
                        })
                    }
                )
            )
            MenuOptionColumn(
                options = listOf(entry),
                iconForOption = { 0 },
                onOptionClick = { it.runnable?.run() },
                onInlineToggle = {},
                onSegmentClick = { it.runnable.run() }
            )
        }
        composeTestRule.onNodeWithText("Performance").requestFocus()
        composeTestRule.onNodeWithText("Performance").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.onNodeWithContentDescription("Hidden").assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Hidden").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.onNodeWithContentDescription("Floating").assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Floating").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.runOnIdle {
            assertEquals(1, modeCount)
            assertEquals(0, entryCount)
        }
        composeTestRule.onNodeWithContentDescription("Floating").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithText("Performance").assertIsFocused()
        composeTestRule.onNodeWithText("Performance").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.runOnIdle { assertEquals(1, entryCount) }
    }

    @Test
    fun performanceControlLabelsFitOnSmallLandscapeScreenWithLargeFont() {
        val locales = listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.GERMAN, Locale.forLanguageTag("ru"))
        var locale by mutableStateOf(locales.first())
        composeTestRule.setContent {
            val localized = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .createConfigurationContext(android.content.res.Configuration().apply { setLocale(locale) })
            val labels = listOf(
                localized.getString(com.limelight.R.string.perf_overlay_style_mini),
                localized.getString(com.limelight.R.string.perf_overlay_style_standard),
                localized.getString(com.limelight.R.string.perf_overlay_style_detailed),
                localized.getString(com.limelight.R.string.perf_overlay_style_classic)
            )
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MenuOptionColumn(
                        options = listOf(GameMenu.MenuOption(
                            localized.getString(com.limelight.R.string.game_menu_perf_overlay_style),
                            false, null, null, false, true,
                            inlineControl = GameMenu.InlineControl.Segmented(labels.mapIndexed { index, label ->
                                GameMenu.SegmentOption(label, index == 0, Runnable {})
                            })
                        )),
                        iconForOption = { 0 },
                        onOptionClick = {},
                        onInlineToggle = {},
                        onSegmentClick = {},
                        modifier = Modifier.width(220.dp),
                        linearNavigation = true
                )
            }
        }
        for (language in locales) {
            composeTestRule.runOnIdle { locale = language }
            val localized = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .createConfigurationContext(android.content.res.Configuration().apply { setLocale(language) })
            val labels = listOf(
                localized.getString(com.limelight.R.string.perf_overlay_style_mini),
                localized.getString(com.limelight.R.string.perf_overlay_style_standard),
                localized.getString(com.limelight.R.string.perf_overlay_style_detailed),
                localized.getString(com.limelight.R.string.perf_overlay_style_classic)
            )
            val viewport = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
            labels.forEach { label ->
                val node = composeTestRule.onNodeWithContentDescription(label).fetchSemanticsNode()
                assertTrue("$language: $label must fit the viewport", node.boundsInRoot.right <= viewport.right)
            }
            repeat(labels.size) { index ->
                val results = mutableListOf<TextLayoutResult>()
                val action = composeTestRule.onNodeWithTag("inlineSegmentLabel$index", useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                assertTrue(action.action?.invoke(results) == true)
                assertFalse(language.toString() + ": " + labels[index] + " overflows", results.single().hasVisualOverflow)
            }
        }
    }
}
