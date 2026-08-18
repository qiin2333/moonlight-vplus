package com.limelight.gamemenu

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuComposeFocusTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cardIsSkippedAndChildControlsRemainReachable() {
        val cardFocused = AtomicBoolean(false)

        composeTestRule.setContent {
            val sliderValue = remember { mutableFloatStateOf(0.5f) }

            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .testTag("focusBeforeCard")
                        .size(48.dp)
                        .focusable()
                )
                GameMenuCard(
                    title = "Focus test",
                    onLongClick = {},
                    modifier = Modifier.onFocusChanged { cardFocused.set(it.isFocused) }
                ) {
                    Slider(
                        value = sliderValue.floatValue,
                        onValueChange = { sliderValue.floatValue = it },
                        modifier = Modifier
                            .focusProperties { canFocus = true }
                            .testTag("bitrateSlider")
                            .fillMaxWidth()
                    )
                    Switch(
                        checked = false,
                        onCheckedChange = {},
                        modifier = Modifier
                            .focusProperties { canFocus = true }
                            .testTag("gyroSwitch")
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { canFocus = true }
                            .clickable {}
                            .testTag("settingRow")
                    ) {
                        Text("Clickable setting")
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("focusBeforeCard").requestFocus()
        composeTestRule.onNodeWithTag("focusBeforeCard").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("bitrateSlider").assertIsFocused()
        assertFalse("The card container must not consume controller focus", cardFocused.get())

        composeTestRule.onNodeWithTag("bitrateSlider").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("gyroSwitch").assertIsFocused()

        composeTestRule.onNodeWithTag("gyroSwitch").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag("settingRow").assertIsFocused()
    }

    @Test
    fun standardGamepadAConfirmsInitiallyFocusedMenuOption() {
        val activated = AtomicBoolean(false)
        lateinit var initialFocusRequester: FocusRequester
        lateinit var inputModeManager: androidx.compose.ui.input.InputModeManager
        val firstOption = GameMenu.MenuOption(
            "First option",
            false,
            Runnable { activated.set(true) },
            null,
            false
        )
        val secondOption = GameMenu.MenuOption(
            "Second option",
            false,
            Runnable {},
            null,
            false
        )

        composeTestRule.setContent {
            initialFocusRequester = remember { FocusRequester() }
            inputModeManager = LocalInputModeManager.current
            MenuOptionColumn(
                options = listOf(firstOption, secondOption),
                iconForOption = { 0 },
                onOptionClick = { it.runnable?.run() },
                onInlineToggle = {},
                onSegmentClick = {},
                initialFocusRequester = initialFocusRequester
            )
        }

        composeTestRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            initialFocusRequester.requestFocus()
        }
        composeTestRule.onNodeWithText("First option").assertIsFocused()
        composeTestRule.onNodeWithText("First option").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithText("Second option").assertIsFocused()
        composeTestRule.onNodeWithText("Second option").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithText("First option").assertIsFocused()
        val mappedConfirmKey = when (mapGameMenuConfirmKeyCode(KeyEvent.KEYCODE_BUTTON_A)) {
            KeyEvent.KEYCODE_DPAD_CENTER -> Key.DirectionCenter
            else -> error("Standard gamepad A must map to the focused UI confirmation key")
        }
        composeTestRule.onNodeWithText("First option").performKeyInput {
            pressKey(mappedConfirmKey)
        }

        assertTrue(activated.get())
    }

    @Test
    fun hardwareFocusRefreshDoesNotResetDirectionalNavigation() {
        lateinit var refreshHardwareFocus: () -> Unit
        val firstOption = GameMenu.MenuOption("First", false, Runnable {}, null, false)
        val secondOption = GameMenu.MenuOption("Second", false, Runnable {}, null, false)

        composeTestRule.setContent {
            val initialFocusRequester = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current
            var focusRequestToken by remember { mutableIntStateOf(1) }
            var contentLaidOut by remember { mutableStateOf(false) }
            var menuHasFocus by remember { mutableStateOf(false) }
            refreshHardwareFocus = { focusRequestToken++ }

            LaunchedEffect(focusRequestToken, contentLaidOut) {
                if (shouldRequestGameMenuFocus(
                        hardwareFocusRequestToken = focusRequestToken,
                        guideActive = false,
                        hasOptions = true,
                        menuContentLaidOut = contentLaidOut,
                        menuHasFocus = menuHasFocus
                    )
                ) {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    initialFocusRequester.requestFocus()
                }
            }

            Column(
                modifier = Modifier
                    .onFocusChanged { menuHasFocus = it.hasFocus }
                    .focusGroup()
                    .onGloballyPositioned { contentLaidOut = true }
            ) {
                MenuOptionColumn(
                    options = listOf(firstOption, secondOption),
                    iconForOption = { 0 },
                    onOptionClick = {},
                    onInlineToggle = {},
                    onSegmentClick = {},
                    initialFocusRequester = initialFocusRequester
                )
            }
        }

        composeTestRule.onNodeWithText("First").assertIsFocused()
        composeTestRule.onNodeWithText("First").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithText("Second").assertIsFocused()
        composeTestRule.runOnIdle { refreshHardwareFocus() }
        composeTestRule.onNodeWithText("Second").assertIsFocused()
        composeTestRule.onNodeWithText("Second").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithText("First").assertIsFocused()
    }

    @Test
    fun featureGuideKeepsControllerFocusOnItsActions() {
        val advanced = AtomicBoolean(false)

        composeTestRule.setContent {
            CuteFeatureGuideCard(
                eyebrow = "Guide",
                title = "Controller focus",
                body = "The action buttons should own directional focus.",
                actionLabel = "Next",
                onAction = { advanced.set(true) },
                onSkip = {},
                hardwareFocusRequestToken = 1
            )
        }

        val skipLabel = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.feature_guide_skip)

        composeTestRule.onNodeWithText("Next").assertIsFocused()
        composeTestRule.onNodeWithText("Next").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithText(skipLabel).assertIsFocused()
        composeTestRule.onNodeWithText(skipLabel).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithText("Next").assertIsFocused()
        composeTestRule.onNodeWithText("Next").performKeyInput {
            pressKey(Key.Enter)
        }
        assertTrue(advanced.get())
    }

    @Test
    fun featureGuideDecorationDoesNotExpandCardToMaximumHeight() {
        var maximumCardHeightPx = 0f
        composeTestRule.setContent {
            maximumCardHeightPx = with(LocalDensity.current) { 260.dp.toPx() }
            CuteFeatureGuideCard(
                eyebrow = "Guide",
                title = "Compact content",
                body = "Short guide copy should determine the card height.",
                actionLabel = "Next",
                onAction = {},
                onSkip = {}
            )
        }

        val cardHeight = composeTestRule
            .onNodeWithTag(FEATURE_GUIDE_CARD_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val rootHeight = composeTestRule
            .onRoot()
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        assertTrue(
            "Guide decoration must not force the card to fill its height constraint " +
                "(card=$cardHeight, maximum=$maximumCardHeightPx, root=$rootHeight)",
            cardHeight < maximumCardHeightPx - 1f
        )
    }

    @Test
    fun featureGuideHardwareRefreshDoesNotLockFocusOnAction() {
        lateinit var refreshHardwareFocus: () -> Unit

        composeTestRule.setContent {
            var focusRequestToken by remember { mutableIntStateOf(1) }
            refreshHardwareFocus = { focusRequestToken++ }
            CuteFeatureGuideCard(
                eyebrow = "Guide",
                title = "Focus refresh",
                body = "Refreshing hardware focus must preserve the selected action.",
                actionLabel = "Next",
                onAction = {},
                onSkip = {},
                hardwareFocusRequestToken = focusRequestToken
            )
        }

        val skipLabel = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.feature_guide_skip)

        composeTestRule.onNodeWithText("Next").assertIsFocused()
        composeTestRule.onNodeWithText("Next").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.onNodeWithText(skipLabel).assertIsFocused()
        composeTestRule.runOnIdle { refreshHardwareFocus() }
        composeTestRule.onNodeWithText(skipLabel).assertIsFocused()
    }

    @Test
    fun featureGuideCanBeDismissedWithGamepadButtonB() {
        val dismissed = AtomicBoolean(false)

        composeTestRule.setContent {
            CuteFeatureGuideCard(
                eyebrow = "Guide",
                title = "Controller dismissal",
                body = "The standard gamepad back button should close the guide.",
                actionLabel = "Next",
                onAction = {},
                onSkip = { dismissed.set(true) }
            )
        }

        composeTestRule.onNodeWithText("Next").requestFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
        composeTestRule.waitForIdle()

        assertTrue(dismissed.get())
    }
}
