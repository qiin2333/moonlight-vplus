package com.limelight.utils

import android.app.Dialog
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AppActionSheetSingleChoiceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null
    private val invokedIds = mutableListOf<Int>()

    @After
    fun dismissDialog() {
        composeTestRule.runOnUiThread { dialog?.dismiss() }
    }

    private fun showChoices(actions: List<AppActionSheet.Action>, selectedId: Int) {
        composeTestRule.runOnUiThread {
            val controllerContext = ContextThemeWrapper(composeTestRule.activity, 0).apply {
                applyOverrideConfiguration(Configuration().apply {
                    uiMode = Configuration.UI_MODE_TYPE_TELEVISION
                })
            }
            dialog = AppActionSheet.show(
                context = controllerContext,
                title = "Display choices",
                actions = actions,
                initialActionId = selectedId,
                singleChoice = true,
                onAction = { invokedIds += it.id }
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun longListShowsAndFocusesSavedChoiceAndControllerActivationRunsOnce() {
        showChoices((0..40).map { AppActionSheet.Action(it, "Choice $it", checked = it == 37) }, 37)
        awaitFocusedChoice("Choice 37")

        composeTestRule.onNodeWithText("Choice 37")
            .assertIsDisplayed()
            .assertIsFocused()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeTestRule.onNodeWithText("Choice 37").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Choice 38").assertIsFocused()
        composeTestRule.onNodeWithText("Choice 37").assertIsSelected()
        assertEquals(emptyList<Int>(), invokedIds)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        composeTestRule.waitForIdle()

        assertEquals(listOf(38), invokedIds)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun disabledSavedChoiceIsShownButCannotBeActivatedAndNavigationSkipsIt() {
        showChoices(listOf(
            AppActionSheet.Action(0, "Previous", checked = false),
            AppActionSheet.Action(1, "Unavailable", enabled = false, checked = true),
            AppActionSheet.Action(2, "Next", checked = false)
        ), 1)

        awaitFocusedChoice("Next")
        composeTestRule.onNodeWithText("Unavailable").assertIsDisplayed().assertIsSelected().performClick()
        assertEquals(emptyList<Int>(), invokedIds)
        composeTestRule.onNodeWithText("Next").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("Previous").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(0), invokedIds)
    }

    @Test
    fun keyboardConfirmationRunsOnce() {
        showChoices(listOf(AppActionSheet.Action(1, "Current", checked = true)), 1)
        awaitFocusedChoice("Current")

        composeTestRule.onNodeWithText("Current").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        composeTestRule.waitForIdle()

        assertEquals(listOf(1), invokedIds)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun iconChoiceUsesTheSharedIconSlotAndKeepsSingleChoiceControllerBehavior() {
        showChoices(listOf(
            AppActionSheet.Action(0, "Plain choice", checked = false),
            AppActionSheet.Action(1, "Icon choice", checked = true, iconRes = android.R.drawable.ic_menu_view)
        ), 1)
        awaitFocusedChoice("Icon choice")

        composeTestRule.onNodeWithText("Icon choice").assertIsFocused().assertIsSelected()
        val plainLeft = composeTestRule.onNodeWithText("Plain choice", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        val iconLeft = composeTestRule.onNodeWithText("Icon choice", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        // Decorative icons use 24dp and leave 12dp before the option label.
        assertEquals(36f * composeTestRule.activity.resources.displayMetrics.density, iconLeft - plainLeft, 1f)

        composeTestRule.onNodeWithText("Icon choice").performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("Plain choice").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        composeTestRule.waitForIdle()

        assertEquals(listOf(0), invokedIds)
    }

    /** Window focus can arrive after Compose first becomes idle, especially on older Android. */
    private fun awaitFocusedChoice(label: String) {
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(label)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .any { it.config[SemanticsProperties.Focused] }
        }
    }
}
