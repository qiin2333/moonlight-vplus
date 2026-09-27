package com.limelight.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamViewInputTest {
    @get:Rule val rule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank")),
    )

    @Test fun everyConstructorAndProductionLayoutKeepKeyboardInputAvailable() {
        rule.scenario.onActivity { activity ->
            val views = listOf(
                StreamView(activity),
                StreamView(activity, null),
                StreamView(activity, null, 0),
                StreamView(activity, null, 0, 0),
                activity.layoutInflater.inflate(R.layout.activity_game, FrameLayout(activity), true)
                    .findViewById<StreamView>(R.id.surfaceView),
            )
            for (view in views) {
                assertFalse(view.onCheckIsTextEditor())
                assertNull(view.onCreateInputConnection(EditorInfo()))
                view.setTextInputEnabled(true)
                assertTrue(view.onCheckIsTextEditor())
                assertNotNull(view.onCreateInputConnection(EditorInfo()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertFalse(view.isAutoHandwritingEnabled)
                }
                view.setTextInputEnabled(false)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun streamOptsOutEvenWhenStyleEnablesAutomaticHandwriting() {
        rule.scenario.onActivity {
            val context = InstrumentationRegistry.getInstrumentation().context
            val style = com.limelight.test.R.style.HandwritingEnabledStreamFixture
            assertTrue(SurfaceView(context, null, 0, style).isAutoHandwritingEnabled)
            assertFalse(StreamView(context, null, 0, style).isAutoHandwritingEnabled)
        }
    }

    @Test fun textAndKeysWorkWithoutAnyRemoteFocusReport() {
        rule.scenario.onActivity { activity ->
            val view = StreamView(activity)
            val received = mutableListOf<String>()
            view.setInputCallbacks(recordingCallbacks(received))
            view.setTextInputEnabled(true)
            val info = EditorInfo()
            val connection = view.onCreateInputConnection(info)!!
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType)

            connection.setComposingText("zhong", 1)
            assertTrue(received.isEmpty())
            connection.commitText("中文🙂", 1)
            connection.finishComposingText()
            connection.setComposingText("输入", 1)
            connection.finishComposingText()
            connection.finishComposingText()
            connection.deleteSurroundingText(1, 1)
            connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
            assertEquals(
                listOf("text:中文🙂", "text:输入", "delete", "forward-delete", "enter",
                    "down:${KeyEvent.KEYCODE_A}", "up:${KeyEvent.KEYCODE_A}"),
                received,
            )

            view.setTextInputEnabled(false)
            connection.commitText("detached", 1)
            connection.finishComposingText()
            connection.deleteSurroundingText(1, 1)
            connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            assertEquals(7, received.size)
        }
    }

    @Test fun remoteInputOptionsDoNotEnableScreenHandwriting() {
        rule.scenario.onActivity { activity ->
            val view = StreamView(activity)
            for (options in listOf(true to false, false to true, false to false)) {
                view.setRemoteTextInputOptions(options.first, options.second)
                val info = EditorInfo()
                assertFalse(view.isTextInputEnabled())
                assertNull(view.onCreateInputConnection(info))
                view.setTextInputEnabled(true)
                assertNotNull(view.onCreateInputConnection(info))
                assertEquals(
                    if (options.first) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0,
                    info.inputType and InputType.TYPE_MASK_VARIATION,
                )
                assertEquals(options.second, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                assertEquals(
                    if (options.second) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE,
                    info.imeOptions and EditorInfo.IME_MASK_ACTION,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertFalse(view.isAutoHandwritingEnabled)
                }
                view.setTextInputEnabled(false)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun manualKeyboardAndStylusDragWorkWithoutRemoteContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: StreamView
        val actions = mutableListOf<Int>()
        val location = IntArray(2)
        rule.scenario.onActivity { activity ->
            view = StreamView(activity).apply {
                isFocusableInTouchMode = true
                setTextInputEnabled(true)
                setOnTouchListener { _, event ->
                    if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                        actions.add(event.actionMasked)
                    }
                    true
                }
            }
            activity.setContentView(view)
        }
        instrumentation.waitForIdleSync()
        rule.scenario.onActivity { activity ->
            assertTrue(view.requestFocus())
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        try {
            awaitIme(view, true)
            rule.scenario.onActivity {
                view.getLocationOnScreen(location)
                // Use the exposed top part of the stream while the keyboard is visible.
                location[0] += view.width / 4
                location[1] += view.height / 4
            }
            val downTime = SystemClock.uptimeMillis()
            for ((action, offset) in listOf(
                MotionEvent.ACTION_DOWN to 0f,
                MotionEvent.ACTION_MOVE to 80f,
                MotionEvent.ACTION_MOVE to 160f,
                MotionEvent.ACTION_UP to 160f,
            )) {
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, 1,
                    arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_STYLUS
                    }),
                    arrayOf(MotionEvent.PointerCoords().apply {
                        x = location[0] + offset
                        y = location[1].toFloat()
                        pressure = if (action == MotionEvent.ACTION_UP) 0f else 0.5f
                    }),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
                )
                try {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            instrumentation.waitForIdleSync()
            rule.scenario.onActivity {
                assertEquals(MotionEvent.ACTION_DOWN, actions.first())
                assertTrue(actions.contains(MotionEvent.ACTION_MOVE))
                assertEquals(MotionEvent.ACTION_UP, actions.last())
                assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
            }
        } finally {
            rule.scenario.onActivity { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
        awaitIme(view, false)
    }

    private fun awaitIme(view: View, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var reached = false
            rule.scenario.onActivity {
                reached = ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            if (reached) return
            SystemClock.sleep(50)
        }
        fail("IME did not reach visible=$visible")
    }

    private fun recordingCallbacks(received: MutableList<String>) = object : StreamView.InputCallbacks {
        override fun handleKeyDown(event: KeyEvent): Boolean {
            received.add("down:${event.keyCode}")
            return true
        }
        override fun handleKeyUp(event: KeyEvent): Boolean {
            received.add("up:${event.keyCode}")
            return true
        }
        override fun handleText(text: String) { received.add("text:$text") }
        override fun handleDelete() { received.add("delete") }
        override fun handleForwardDelete() { received.add("forward-delete") }
        override fun handleEnter() { received.add("enter") }
    }
}
