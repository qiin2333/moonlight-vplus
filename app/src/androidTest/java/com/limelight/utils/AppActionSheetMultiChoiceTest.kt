package com.limelight.utils

import android.app.Dialog
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class AppActionSheetMultiChoiceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null
    private val confirmed = mutableListOf<Set<Int>>()
    private val drafts = mutableListOf<Set<Int>>()
    private var dismissals = 0

    @After
    fun closeDialog() {
        compose.runOnUiThread { dialog?.dismiss() }
    }

    private fun show(actions: List<AppActionSheet.Action>, minimumSelectionCount: Int = 0) {
        compose.runOnUiThread {
            dialog = AppActionSheet.showMultiSelect(
                context = compose.activity,
                title = "Choose display details",
                actions = actions,
                confirmLabel = "Apply",
                cancelLabel = "Cancel",
                minimumSelectionCount = minimumSelectionCount,
                onConfirm = { confirmed += it },
                forceInitialFocus = true,
                onDismiss = { dismissals++ },
                onSelectionChanged = { drafts += it }
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun longListStartsAtSavedChoiceAndChangesRemainDraftUntilConfirmation() {
        show((0..40).map { AppActionSheet.Action(it, "Detail $it", checked = it == 30 || it == 37) })

        compose.onNodeWithText("Detail 30").assertIsDisplayed().assertIsFocused().assertIsOn()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Detail 31").assertIsFocused().assertIsOff()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Detail 31").assertIsFocused().assertIsOn()
        assertEquals(listOf(setOf(30, 31, 37)), drafts)
        assertEquals(emptyList<Set<Int>>(), confirmed)

        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf(30, 31, 37)), confirmed)
        assertEquals(1, dismissals)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun disabledChoiceIsSkippedAndCancelDiscardsChanges() {
        val actions = listOf(
            AppActionSheet.Action(0, "Previous", checked = false),
            AppActionSheet.Action(1, "Unavailable", enabled = false, checked = true),
            AppActionSheet.Action(2, "Next", checked = false)
        )
        show(actions)

        compose.onNodeWithText("Unavailable").assertIsDisplayed().assertIsOn().performClick()
        compose.onNodeWithText("Next").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Previous").assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Previous").assertIsFocused().assertIsOn()
        sendKey(KeyEvent.KEYCODE_BUTTON_B)

        assertEquals(emptyList<Set<Int>>(), confirmed)
        assertEquals(listOf(setOf(0, 1)), drafts)
        assertEquals(listOf(false, true, false), actions.map { it.checked })
        assertEquals(1, dismissals)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun entirelyDisabledChoicesFocusCancelInsteadOfRequestingAnUnattachedRow() {
        show(listOf(AppActionSheet.Action(1, "Unavailable", enabled = false, checked = true)))

        compose.onNodeWithText("Unavailable").assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("Cancel").assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)

        assertEquals(emptyList<Set<Int>>(), confirmed)
        assertEquals(1, dismissals)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun minimumSelectionCountKeepsLastRequiredChoiceSelected() {
        show(listOf(AppActionSheet.Action(1, "Required", checked = true)), minimumSelectionCount = 1)

        compose.onNodeWithText("Required").assertIsFocused()
        sendKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Required").assertIsOn().assertIsFocused()
        assertEquals(emptyList<Set<Int>>(), drafts)
        compose.onNodeWithText("Apply").performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf(1)), confirmed)
    }

    private fun sendKey(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
}
