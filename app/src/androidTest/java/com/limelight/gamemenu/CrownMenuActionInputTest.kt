package com.limelight.gamemenu

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownMenuActionInputTest {
    @get:Rule val rule = createComposeRule()

    @Test fun editAndSwitchActionsActivateOncePerTouch() {
        val keys = listOf("crown_layout", "crown_profiles")
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
            rule.onNodeWithText(key).performTouchInput { click() }
            rule.runOnIdle { assertEquals(index + 1, clicks) }
        }
    }
}
