package com.limelight.gamemenu

import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchPointerPresetEditorTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<ComponentActivity>()

    private var dialog: Dialog? = null

    @After
    fun tearDown() {
        activityRule.runOnUiThread { dialog?.dismiss() }
    }

    @Test
    fun validLocalizedNameAndSelectedFieldsAreSaved() {
        var savedName = ""
        var savedValues = emptyMap<TouchPointerPresetField, String>()
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "预设1",
                fields = testFields(checked = true),
                onSave = { name, values ->
                    savedName = name
                    savedValues = values
                    TouchPointerPresetSaveResult.SAVED
                }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }
        activityRule.waitUntil(5_000) { firstCheckBox(dialog)?.isFocused == true }

        activityRule.runOnUiThread {
            (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == false }

        assertEquals("预设1", savedName)
        assertEquals(
            mapOf(TouchPointerPresetField.POINTER_SPEED to "100"),
            savedValues
        )
    }

    @Test
    fun editedNumericValueIsSaved() {
        var savedValues = emptyMap<TouchPointerPresetField, String>()
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "Preset 1",
                fields = testFields(checked = true),
                onSave = { _, values ->
                    savedValues = values
                    TouchPointerPresetSaveResult.SAVED
                }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            val seekBar = firstSeekBar(dialog) ?: error("Missing preset value slider")
            seekBar.progress = 175
            (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == false }

        assertEquals(
            mapOf(TouchPointerPresetField.POINTER_SPEED to "175"),
            savedValues
        )
    }

    @Test
    fun blankNameKeepsEditorOpen() {
        var saveCalled = false
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "   ",
                fields = testFields(checked = true),
                onSave = { _, _ ->
                    saveCalled = true
                    TouchPointerPresetSaveResult.SAVED
                }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activityRule.waitForIdle()

        assertTrue(dialog?.isShowing == true)
        assertFalse(saveCalled)
    }

    @Test
    fun supplementaryCharactersUseCodePointNameLimit() {
        val emoji = "\uD83D\uDE00"
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "",
                fields = testFields(checked = true),
                onSave = { _, _ -> TouchPointerPresetSaveResult.SAVED }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            val input = firstEditText(dialog) ?: error("Missing preset name input")
            input.setText(emoji.repeat(TouchPointerSensitivityPolicy.MAX_NAME_LENGTH))
            input.append(emoji)
            val actual = input.text.toString()
            assertEquals(
                TouchPointerSensitivityPolicy.MAX_NAME_LENGTH,
                actual.codePointCount(0, actual.length)
            )
            assertEquals(emoji.repeat(TouchPointerSensitivityPolicy.MAX_NAME_LENGTH), actual)
        }
    }

    @Test
    fun customControlsUseDialogThemedContext() {
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "Preset 1",
                fields = testFields(checked = true),
                onSave = { _, _ -> TouchPointerPresetSaveResult.SAVED }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            val alertDialog = dialog as AlertDialog
            val expectedTextColor = ContextCompat.getColor(
                alertDialog.context,
                R.color.app_dialog_text_primary
            )
            assertSame(alertDialog.context, firstEditText(dialog)?.context)
            assertSame(alertDialog.context, firstCheckBox(dialog)?.context)
            assertEquals(expectedTextColor, firstEditText(dialog)?.currentTextColor)
            assertEquals(expectedTextColor, firstCheckBox(dialog)?.currentTextColor)
            assertEquals(
                expectedTextColor,
                findTextView(dialog, alertDialog.context.getString(
                    R.string.game_menu_touch_pointer_preset_fields
                ))?.currentTextColor
            )
        }
    }

    @Test
    fun controllerNameEditingUsesTwoStageDismiss() {
        val inputState = TouchPointerPresetEditor.InputState()
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "Preset 1",
                fields = testFields(checked = true),
                inputState = inputState,
                onSave = { _, _ -> TouchPointerPresetSaveResult.SAVED }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            val input = firstEditText(dialog) ?: error("Missing preset name input")
            input.requestFocus()
            input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertTrue(inputState.isEditing)

            assertTrue(inputState.handleDismissKey(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
            ))
            assertTrue(inputState.handleDismissKey(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)
            ))
            assertFalse(inputState.isEditing)
            assertTrue(dialog?.isShowing == true)
        }
    }

    @Test
    fun controllerBrowseModeMovesDownWithoutEditingName() {
        val inputState = TouchPointerPresetEditor.InputState()
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "Preset 1",
                fields = testFields(checked = true),
                inputState = inputState,
                onSave = { _, _ -> TouchPointerPresetSaveResult.SAVED }
            )
        }
        activityRule.waitUntil(5_000) { dialog?.isShowing == true }

        activityRule.runOnUiThread {
            val input = firstEditText(dialog) ?: error("Missing preset name input")
            input.requestFocus()
            input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
            assertFalse(inputState.isEditing)
            assertTrue(firstCheckBox(dialog)?.isFocused == true)
        }
    }

    private fun testFields(checked: Boolean) = listOf(
        TouchPointerPresetEditor.FieldOption(
            field = TouchPointerPresetField.POINTER_SPEED,
            label = "Pointer speed",
            value = "100",
            checked = checked
        )
    )

    private fun firstCheckBox(dialog: Dialog?): CheckBox? {
        val root = dialog?.window?.decorView ?: return null
        return findView(root) { it is CheckBox } as? CheckBox
    }

    private fun firstEditText(dialog: Dialog?): EditText? {
        val root = dialog?.window?.decorView ?: return null
        return findView(root) { it is EditText } as? EditText
    }

    private fun firstSeekBar(dialog: Dialog?): SeekBar? {
        val root = dialog?.window?.decorView ?: return null
        return findView(root) { it is SeekBar } as? SeekBar
    }

    private fun findTextView(dialog: Dialog?, text: String): TextView? {
        val root = dialog?.window?.decorView ?: return null
        return findView(root) { it is TextView && it.text.toString() == text } as? TextView
    }

    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findView(view.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }
}
