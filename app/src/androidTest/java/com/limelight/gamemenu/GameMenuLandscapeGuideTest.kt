package com.limelight.gamemenu

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import com.joco.showcase.sequence.SequenceShowcase
import com.joco.showcase.sequence.rememberSequenceShowcaseState
import com.joco.showcaseview.BackgroundAlpha
import com.joco.showcaseview.ShowcaseAlignment
import com.joco.showcaseview.ShowcasePosition
import com.joco.showcaseview.highlight.ShowcaseHighlight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GameMenuLandscapeGuideTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mockQuickActionGuideStaysVisibleAndOwnsDirectionalFocus() {
        composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeTestRule.waitForIdle()
        val skipLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.feature_guide_skip)

        composeTestRule.setContent {
            val showcaseState = rememberSequenceShowcaseState()
            var targetLaidOut by remember { mutableStateOf(false) }

            LaunchedEffect(targetLaidOut) {
                if (targetLaidOut) showcaseState.start()
            }

            SequenceShowcase(state = showcaseState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(0.88f)
                            .padding(top = 24.dp)
                            .height(72.dp)
                            .sequenceShowcaseTarget(
                                index = 0,
                                position = ShowcasePosition.Bottom,
                                alignment = ShowcaseAlignment.Start,
                                highlight = ShowcaseHighlight.Rectangular(12.dp),
                                backgroundAlpha = BackgroundAlpha.Dark
                            ) {
                                CuteFeatureGuideCard(
                                    eyebrow = "1 / 1",
                                    title = "快捷栏也要我教吗？",
                                    body = "长按快捷按钮就能添加、移除或拖动排序。" +
                                        "把常用操作摆好，下次可别再手忙脚乱啦。",
                                    actionLabel = "下一步",
                                    onAction = {},
                                    onSkip = {},
                                    hardwareFocusRequestToken = 1
                                )
                            }
                            .onGloballyPositioned {
                                targetLaidOut = it.size.width > 0 && it.size.height > 0
                            },
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Esc", "Win", "Alt+Tab", "键盘", "性能", "退出").forEach {
                            Text(it, color = Color.White)
                        }
                    }
                }
            }
        }

        composeTestRule.waitUntilAtLeastOneExists(
            hasTestTag(FEATURE_GUIDE_CARD_TAG),
            timeoutMillis = 5_000
        )
        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val cardBounds = composeTestRule
            .onNodeWithTag(FEATURE_GUIDE_CARD_TAG)
            .fetchSemanticsNode().boundsInRoot
        val bodyBounds = composeTestRule
            .onNodeWithTag(FEATURE_GUIDE_BODY_TAG)
            .fetchSemanticsNode().boundsInRoot
        val actionBounds = composeTestRule
            .onNodeWithTag(FEATURE_GUIDE_ACTIONS_TAG)
            .fetchSemanticsNode().boundsInRoot

        assertTrue("The mock must run in landscape", rootBounds.width > rootBounds.height)
        assertTrue("Guide card must stay inside the window", cardBounds.bottom <= rootBounds.bottom)
        assertTrue("Guide body must not overlap the actions", bodyBounds.bottom <= actionBounds.top)

        composeTestRule.onNodeWithText("下一步").assertIsFocused()
        composeTestRule.onNodeWithText("下一步").performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionLeft)
        }
        composeTestRule.onNodeWithText(skipLabel).assertIsFocused()
        composeTestRule.onNodeWithText(skipLabel).performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionRight)
        }
        composeTestRule.onNodeWithText("下一步").assertIsFocused()
    }
}
