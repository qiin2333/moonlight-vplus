package com.limelight.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressiveSegmentSelectionTest {
    @get:Rule val rule = createComposeRule()
    private val modes = listOf(-1, 0, 1, 2, 4, 3)

    @Test
    fun semanticRowSwitchKeepsOneSelectionAndStableLayoutBounds() {
        val selected = mutableStateOf(-1)
        rule.setContent {
            MaterialTheme {
                ScreenCombinationSegmentedControl(
                    options = modes.map { AppScreenCombinationOption(it, "Mode $it") },
                    selectedMode = selected.value,
                    isDarkTheme = false,
                    onModeSelected = { selected.value = it }
                )
            }
        }
        val segments = rule.onAllNodes(isSelectable())
        segments.assertCountEquals(6)
        val before = segments[4].getUnclippedBoundsInRoot()
        rule.mainClock.autoAdvance = false
        // Semantic clicks check selection and layout, not pointer hit detection.
        segments[4].performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertEquals(4, selected.value) }
        assertEquals(before, segments[4].getUnclippedBoundsInRoot())
        rule.mainClock.advanceTimeBy(1000)
        segments[4].assertIsSelected()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
    }
}
