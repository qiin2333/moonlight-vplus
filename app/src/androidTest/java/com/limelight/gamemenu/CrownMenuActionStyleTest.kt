package com.limelight.gamemenu

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownMenuActionStyleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onlyEditAndSwitchTitlesAreBoldAndStillActivateOnce() {
        val keys = listOf("crown_layout", "crown_profiles", "crown_touch")
        var clicks = 0
        rule.setContent {
            MenuOptionColumn(
                options = keys.map { key ->
                    GameMenu.MenuOption(key, false, Runnable {}, key, true, false,
                        isCrownControl = true)
                },
                iconForOption = GameMenu::getIconForMenuOption,
                onOptionClick = { clicks++ },
                onInlineToggle = {},
                onSegmentClick = {}
            )
        }
        keys.forEachIndexed { index, key ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(key, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(if (index < 2) FontWeight.Bold else FontWeight.Medium,
                layouts.single().layoutInput.style.fontWeight)
        }
        assertEquals(R.drawable.phc_action_edit, GameMenu.getIconForMenuOption("crown_layout"))
        assertEquals(R.drawable.ic_change, GameMenu.getIconForMenuOption("crown_profiles"))
        rule.onNodeWithText("crown_layout").performTouchInput { click() }
        rule.onNodeWithText("crown_profiles").performTouchInput { click() }
        rule.runOnIdle { assertEquals(2, clicks) }
    }
}
