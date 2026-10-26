package com.limelight.gamemenu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class GameMenuComposeTouchTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialogShellDismissesBackdropButNotPanel() {
        val dismissed = AtomicBoolean(false)

        composeTestRule.setContent {
            GameMenuDialogShell(
                widthFraction = 0.9f,
                onDismissRequest = { dismissed.set(true) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Color.Black)
                )
            }
        }

        composeTestRule.onNodeWithTag(GAME_MENU_PANEL_TAG).performTouchInput { click() }
        assertFalse(dismissed.get())

        composeTestRule.onNodeWithTag(GAME_MENU_BACKDROP_TAG, useUnmergedTree = true).performTouchInput {
            click(percentOffset(0.5f, 0.2f))
        }
        assertTrue(dismissed.get())
    }

    @Test
    fun dialogShellAdaptsToParentWidthAndDensityWhileStayingCentered() {
        var viewportWidth by mutableStateOf(1920.dp)
        var density by mutableFloatStateOf(0.5f)
        var wideLayout = false

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.size(viewportWidth, 360.dp).testTag("menuViewport")) {
                    GameMenuDialogShell(widthFraction = 0.98f, onDismissRequest = {}) { wide ->
                        wideLayout = wide
                        Box(Modifier.fillMaxWidth().height(160.dp).background(Color.Black))
                    }
                }
            }
        }

        // Resize the parent without changing the Activity's window configuration.
        // The 620dp case fits two columns by window width, but not after menu margins.
        val scenarios = listOf(
            Triple(1920.dp, 0.5f, true),
            Triple(1200.dp, 0.75f, true),
            Triple(720.dp, 1f, true),
            Triple(620.dp, 1f, false),
            Triple(360.dp, 2f, false),
            Triple(1920.dp, 0.5f, true)
        )
        for ((width, scale, expectWide) in scenarios) {
            composeTestRule.runOnIdle {
                viewportWidth = width
                density = scale
            }
            val viewport = composeTestRule.onNodeWithTag("menuViewport").fetchSemanticsNode().boundsInRoot
            val panel = composeTestRule.onNodeWithTag(GAME_MENU_PANEL_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals("Layout at $width", expectWide, wideLayout)
            assertEquals("Menu stays centered at $width", viewport.center.x, panel.center.x, 1f)
            assertTrue("Menu fits within $width", panel.left >= viewport.left && panel.right <= viewport.right)
            assertTrue("Menu is capped at 960dp", panel.width <= 960f * scale + 1f)
            if (width >= 1200.dp) {
                assertEquals("Large screens use the width cap", 960f * scale, panel.width, 1f)
            }
        }
    }

    @Test
    fun dialogShellKeepsNarrowSubmenuSingleColumnOnLargeParent() {
        var wideLayout = true
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f)) {
                Box(Modifier.size(1920.dp, 360.dp)) {
                    GameMenuDialogShell(
                        widthFraction = 0.98f,
                        onDismissRequest = {},
                        maxMenuWidth = 560.dp
                    ) { wide ->
                        wideLayout = wide
                        Box(Modifier.fillMaxWidth().height(160.dp))
                    }
                }
            }
        }

        val panel = composeTestRule.onNodeWithTag(GAME_MENU_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(280f, panel.width, 1f)
        assertFalse(wideLayout)
    }

    @Test
    fun verticalSwipeOnSliderScrollsMenuWithoutChangingSlider() {
        var sliderValue by mutableFloatStateOf(0.5f)
        var scrollValue = 0

        composeTestRule.setContent {
            val scrollState = rememberScrollState()
            var sliderGestureActive by remember { mutableStateOf(false) }
            scrollValue = scrollState.value

            Column(
                modifier = Modifier
                    .height(180.dp)
                    .verticalScroll(scrollState, enabled = !sliderGestureActive)
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    modifier = Modifier
                        .testTag("scrollableSlider")
                        .fillMaxWidth()
                        .lockParentScrollDuringGesture { sliderGestureActive = it }
                )
                Spacer(Modifier.height(600.dp))
            }
        }

        composeTestRule.onNodeWithTag("scrollableSlider").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertTrue(scrollValue > 0)
        assertEquals(0.5f, sliderValue, 0.01f)
    }

    @Test
    fun guideInputBlockerPreventsTouchesFromReachingMenu() {
        val underlyingActionInvoked = AtomicBoolean(false)

        composeTestRule.setContent {
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { underlyingActionInvoked.set(true) }
                )
                GameMenuGuideInputBlocker()
            }
        }

        composeTestRule.onNodeWithTag(
            GAME_MENU_GUIDE_INPUT_BLOCKER_TAG,
            useUnmergedTree = true
        )
            .performTouchInput { click() }

        assertFalse(underlyingActionInvoked.get())
    }
}
