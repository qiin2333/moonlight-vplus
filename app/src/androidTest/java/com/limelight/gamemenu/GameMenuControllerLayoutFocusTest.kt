package com.limelight.gamemenu

import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuControllerLayoutFocusTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wideTouchModeGridUsesExplicitDirectionsAndReturnsToHeader() {
        composeTestRule.setContent {
            val topFocusRequester = remember { FocusRequester() }
            val focusRequesters = remember { List(5) { FocusRequester() } }

            Column {
                Box(
                    Modifier
                        .testTag("touchModeBack")
                        .focusRequester(topFocusRequester)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = focusRequesters[0]
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        .focusable()
                )

                repeat(5) { index ->
                    Box(
                        Modifier
                            .testTag("touchMode$index")
                            .touchModeFocusNavigation(
                                targets = touchModeFocusTargets(
                                    primaryCount = 3,
                                    compatibleCount = 2,
                                    globalIndex = index
                                ),
                                focusRequesters = focusRequesters,
                                topFocusRequester = topFocusRequester
                            )
                            .focusRequester(focusRequesters[index])
                            .focusable()
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("touchMode0").requestFocus()
        composeTestRule.onNodeWithTag("touchMode0").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("touchMode1").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode1").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode2").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode2").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("touchMode3").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode3").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag("touchMode4").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode4").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithTag("touchMode2").assertIsFocused()

        composeTestRule.onNodeWithTag("touchMode2").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithTag("touchMode0").assertIsFocused()

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
    fun compatibleMenuOptionUsesExplicitCellFocusProperties() {
        lateinit var firstFocusRequester: FocusRequester
        lateinit var secondFocusRequester: FocusRequester

        composeTestRule.setContent {
            firstFocusRequester = remember { FocusRequester() }
            secondFocusRequester = remember { FocusRequester() }
            val first = remember {
                GameMenu.MenuOption("Compatible first", false, Runnable {}, null, false)
            }
            val second = remember {
                GameMenu.MenuOption("Compatible second", false, Runnable {}, null, false)
            }

            Column {
                MenuOptionColumn(
                    options = listOf(first),
                    iconForOption = { 0 },
                    onOptionClick = {},
                    onInlineToggle = {},
                    onSegmentClick = {},
                    initialFocusRequester = firstFocusRequester,
                    optionFocusModifier = Modifier.focusProperties {
                        right = secondFocusRequester
                    }
                )
                MenuOptionColumn(
                    options = listOf(second),
                    iconForOption = { 0 },
                    onOptionClick = {},
                    onInlineToggle = {},
                    onSegmentClick = {},
                    initialFocusRequester = secondFocusRequester
                )
            }
        }

        composeTestRule.onNodeWithText("Compatible first").requestFocus()
        composeTestRule.onNodeWithText("Compatible first").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithText("Compatible second").assertIsFocused()
    }

    @Test
    fun parentFocusGroupRestoresPreviouslyFocusedChild() {
        lateinit var parentFocusRequester: FocusRequester

        composeTestRule.setContent {
            parentFocusRequester = remember { FocusRequester() }
            val firstFocusRequester = remember { FocusRequester() }

            Column {
                Column(
                    Modifier
                        .focusRequester(parentFocusRequester)
                        .focusRestorer(firstFocusRequester)
                        .focusGroup()
                ) {
                    Box(
                        Modifier
                            .testTag("parentFirst")
                            .focusRequester(firstFocusRequester)
                            .focusable()
                    )
                    Box(
                        Modifier
                            .testTag("parentSecond")
                            .focusable()
                    )
                }
                Box(
                    Modifier
                        .testTag("childSurface")
                        .focusable()
                )
            }
        }

        composeTestRule.onNodeWithTag("parentSecond").requestFocus()
        composeTestRule.onNodeWithTag("parentSecond").assertIsFocused()
        composeTestRule.onNodeWithTag("childSurface").requestFocus()
        composeTestRule.onNodeWithTag("childSurface").assertIsFocused()

        composeTestRule.runOnIdle {
            parentFocusRequester.requestFocus()
        }
        composeTestRule.onNodeWithTag("parentSecond").assertIsFocused()
    }
}
