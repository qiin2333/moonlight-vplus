package com.limelight.gamemenu

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        }
        waitForIme(nameInput, visible = true)
        activityRule.scenario.onActivity {
            val info = EditorInfo()
            val connection = requireNotNull(nameInput.onCreateInputConnection(info))
            assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
        }
        waitForIme(nameInput, visible = false)
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
        }
        tap(nameInput)
        activityRule.scenario.onActivity {
            assertTrue(editor.isEditing)
            assertEquals("My shortcut", nameInput.text.toString())
        }
        waitForIme(nameInput, visible = true)
    }

    private fun tap(view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        val location = IntArray(2)
        activityRule.scenario.onActivity {
            view.getLocationOnScreen(location)
            location[0] += view.width / 2
            location[1] += view.height / 2
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                location[0].toFloat(), location[1].toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(100)
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun cancelledNameTouchDoesNotEnterEditing() {
        activityRule.scenario.onActivity { activity ->
            val input = EditText(activity)
            activity.setContentView(input)
            val editor = CustomKeyNameEditor(input)
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 5f, 5f, 0)
            val cancel = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_CANCEL, 5f, 5f, 0)
            try {
                input.dispatchTouchEvent(down)
                input.dispatchTouchEvent(cancel)
                assertFalse(editor.isEditing)
            } finally {
                down.recycle()
                cancel.recycle()
            }
        }
    }

    @Test
    fun hardwareEnterDownAndUpFinishEditingWithoutReopeningIt() {
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
                externalViews = listOf<View>(nameInput),
                editableView = nameInput,
                isEditing = { editor.isEditing },
                onEnterEditing = editor::enter
            ).requestInitialFocus()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        for (keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            activityRule.scenario.onActivity {
                editor.enter()
                nameInput.setText("My shortcut")
            }
            waitForIme(nameInput, visible = true)
            val downTime = SystemClock.uptimeMillis()
            activityRule.scenario.onActivity {
                assertTrue(nameInput.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)))
                assertTrue("Editing must remain active through key-down", editor.isEditing)
            }
            activityRule.scenario.onActivity {
                assertTrue(nameInput.dispatchKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)))
                assertFalse("Key-up must not reopen name editing", editor.isEditing)
                assertTrue(nameInput.hasFocus())
                assertEquals("My shortcut", nameInput.text.toString())
            }
            waitForIme(nameInput, visible = false)
            tap(nameInput)
            activityRule.scenario.onActivity {
                assertTrue("A single tap must re-enter editing", editor.isEditing)
            }
            waitForIme(nameInput, visible = true)
            activityRule.scenario.onActivity { editor.leave() }
            waitForIme(nameInput, visible = false)
        }
    }

    private fun waitForIme(view: View, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        var actual = false
        do {
            activityRule.scenario.onActivity {
                actual = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            if (actual == visible) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("IME visibility did not change", visible, actual)
    }
}
