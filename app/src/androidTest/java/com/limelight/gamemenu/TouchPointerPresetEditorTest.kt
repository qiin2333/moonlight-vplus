package com.limelight.gamemenu

import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var savedFields = emptySet<TouchPointerPresetField>()
        activityRule.runOnUiThread {
            dialog = TouchPointerPresetEditor.show(
                context = activityRule.activity,
                title = "Create",
                initialName = "预设1",
                fields = testFields(checked = true),
                onSave = { name, fields ->
                    savedName = name
                    savedFields = fields
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
        assertEquals(setOf(TouchPointerPresetField.POINTER_SPEED), savedFields)
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

    private fun testFields(checked: Boolean) = listOf(
        TouchPointerPresetEditor.FieldOption(
            field = TouchPointerPresetField.POINTER_SPEED,
            label = "Pointer speed",
            value = "100%",
            checked = checked
        )
    )

    private fun firstCheckBox(dialog: Dialog?): CheckBox? {
        val root = dialog?.window?.decorView ?: return null
        return findView(root) { it is CheckBox } as? CheckBox
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
