package com.limelight.gamemenu

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import com.limelight.binding.input.advance_setting.KeyboardKeyPickerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomKeyNameEditorTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun imeDoneLeavesEditingAndRestoresControllerNavigation() {
        lateinit var editor: CustomKeyNameEditor
        lateinit var nameInput: EditText
        activityRule.scenario.onActivity { activity ->
            val content = activity.layoutInflater.inflate(R.layout.dialog_add_custom_key, null) as ViewGroup
            activity.setContentView(content)
            nameInput = content.findViewById(R.id.edit_text_key_name)
            editor = CustomKeyNameEditor(nameInput)
            KeyboardKeyPickerController(
                root = content.findViewById(R.id.keyboard_drawing),
                onKeySelected = {},
                externalViews = listOf<View>(
                    nameInput,
                    content.findViewById(R.id.button_save_key),
                    content.findViewById(R.id.button_clear_keys),
                    content.findViewById(R.id.button_close_dialog)
                ),
                editableView = nameInput,
                isEditing = { editor.isEditing },
                onEnterEditing = editor::enter
            ).requestInitialFocus()
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity {
            editor.enter()
            nameInput.setText("My shortcut")
            val info = EditorInfo()
            val connection = requireNotNull(nameInput.onCreateInputConnection(info))
            assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
        }
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity {
            assertFalse(editor.isEditing)
            assertFalse(nameInput.showSoftInputOnFocus)
            assertTrue(nameInput.hasFocus())
            assertEquals("My shortcut", nameInput.text.toString())
        }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity {
            assertFalse(nameInput.hasFocus())
            assertTrue(it.currentFocus is View)
            nameInput.performClick()
            assertTrue(editor.isEditing)
            assertEquals("My shortcut", nameInput.text.toString())
        }
    }
}
