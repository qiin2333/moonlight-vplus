package com.limelight.gamemenu

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun fiveSegmentControlUsesExplicitTwoRowDirections() {
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
                                    columnCount = 3
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
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("segment4").assertIsFocused()
        composeTestRule.onNodeWithTag("segment4").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithTag("segment1").assertIsFocused()
        composeTestRule.onNodeWithTag("segment1").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("segment4").assertIsFocused()
        composeTestRule.onNodeWithTag("segment4").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithTag("segment3").assertIsFocused()
    }

    @Test
    fun compatibleToggleRowOwnsFocusWhenInlineControlDoesNot() {
        lateinit var rowFocusRequester: FocusRequester
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
                onInlineToggle = {},
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
    }
}
