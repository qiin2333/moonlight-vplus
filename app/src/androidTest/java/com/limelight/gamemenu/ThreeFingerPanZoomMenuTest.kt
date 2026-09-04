package com.limelight.gamemenu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThreeFingerPanZoomMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rowAndSwitchEachToggleOnceAndRenderUpdatedState() {
        var enabled by mutableStateOf(true)
        var calls = 0
        val action = Runnable { enabled = !enabled; calls++ }
        composeRule.setContent {
            MenuOptionColumn(
                options = listOf(threeFingerPanZoomOption("Three-finger pan/zoom", enabled, action)),
                iconForOption = { 0 },
                onOptionClick = { it.runnable!!.run() },
                onInlineToggle = { it.toggleAction!!.run() },
                onSegmentClick = {}
            )
        }
        composeRule.onNode(isToggleable(), useUnmergedTree = true).assertIsOn()
        composeRule.onNodeWithText("Three-finger pan/zoom").performClick()
        composeRule.onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
        composeRule.runOnIdle { assertEquals(1, calls) }
        composeRule.onNode(isToggleable(), useUnmergedTree = true).performClick().assertIsOn()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }
}
