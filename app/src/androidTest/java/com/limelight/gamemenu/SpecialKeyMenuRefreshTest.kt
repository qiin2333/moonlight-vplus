package com.limelight.gamemenu

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.CustomKeyData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SpecialKeyMenuRefreshTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun addAndDeleteRefreshRowsWithoutLosingAddFocus() {
        var keys by mutableStateOf(emptyList<CustomKeyData>())
        var addCount = 0
        var selected: ShortArray? = null
        val groupFocus = FocusRequester()
        composeRule.setContent {
            val initialFocus = remember { FocusRequester() }
            val options = buildSpecialKeyMenuOptions(
                keys = keys,
                addLabel = "Add key",
                deleteLabel = "Delete key",
                backLabel = "Back",
                onSend = { selected = it },
                onAdd = { addCount++ },
                onDelete = {},
                onBack = {}
            )
            Box(Modifier.focusRequester(groupFocus).focusRestorer(initialFocus).focusGroup()) {
                MenuOptionColumn(
                    options = options,
                    iconForOption = { 0 },
                    onOptionClick = { it.runnable?.run() },
                    onInlineToggle = {},
                    onSegmentClick = {},
                    initialFocusRequester = initialFocus,
                    initialFocusOptionKey = SPECIAL_KEY_ADD_FOCUS_KEY,
                    stableFocusKeys = true
                )
            }
        }

        composeRule.onNodeWithText("Add key").requestFocus()
        composeRule.runOnIdle { keys = listOf(CustomKeyData("Shortcut", shortArrayOf(17, 65))) }
        composeRule.onNodeWithText("Shortcut").assertIsDisplayed()
        composeRule.onNodeWithText("Delete key").assertIsDisplayed()
        composeRule.onNodeWithText("Add key").assertIsFocused()
        composeRule.onNodeWithText("Add key").performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, addCount) }

        composeRule.onNodeWithText("Shortcut").requestFocus()
        composeRule.onNodeWithText("Shortcut").performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(listOf<Short>(17, 65), selected?.toList()) }

        composeRule.onNodeWithText("Delete key").requestFocus()
        composeRule.runOnIdle { keys = emptyList() }
        composeRule.onNodeWithText("Shortcut").assertDoesNotExist()
        composeRule.onNodeWithText("Delete key").assertDoesNotExist()
        composeRule.runOnIdle { groupFocus.requestFocus() }
        composeRule.onNodeWithText("Add key").assertIsFocused()
    }
}
