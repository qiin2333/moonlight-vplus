package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardUIControllerTouchTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun numpadRemainsVisibleAndEmitsPairedTouchEvents() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            val listener = object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) {
                    events.add(down to keyCode)
                }

                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }
            KeyboardUIController(parent, listener, activity).show()
            activity.setContentView(parent)
            parent.findViewById<View>(R.id.btn_key_page_num).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            val numpad = activity.findViewById<View>(R.id.layout_num)
            for (code in (KeyEvent.KEYCODE_NUM_LOCK..KeyEvent.KEYCODE_NUMPAD_DOT) + KeyEvent.KEYCODE_ENTER) {
                val key = numpad.findViewWithTag<View>("k$code")
                val rect = Rect()
                assertTrue(key.getLocalVisibleRect(rect))
                assertEquals(key.width, rect.width())
                assertEquals(key.height, rect.height())
            }
            val key = numpad.findViewWithTag<View>("k144")
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0)
                key.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        assertEquals(listOf(true to KeyEvent.KEYCODE_NUMPAD_0.toShort(), false to KeyEvent.KEYCODE_NUMPAD_0.toShort()), events)
    }

    @Test
    fun keyboardBodyConsumesBlankTouchesButFullscreenCarrierDoesNot() {
        activityRule.scenario.onActivity { activity ->
            val parent = FrameLayout(activity)
            KeyboardUIController(parent, NoOpKeyboardListener, activity).show()

            val keyboardLayer = parent.findViewById<View>(R.id.layer_6_keyboard)
            val keyboardContent = parent.findViewById<View>(R.id.keyboard_content)

            assertFalse(parent.isClickable)
            assertFalse(keyboardLayer.isClickable)
            assertFalse(keyboardContent.isClickable)

            val eventTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                eventTime,
                eventTime,
                MotionEvent.ACTION_DOWN,
                8f,
                8f,
                0
            )
            val up = MotionEvent.obtain(
                eventTime,
                eventTime + 16,
                MotionEvent.ACTION_UP,
                8f,
                8f,
                0
            )
            try {
                assertTrue(keyboardContent.dispatchTouchEvent(down))
                assertTrue(keyboardContent.dispatchTouchEvent(up))
            } finally {
                down.recycle()
                up.recycle()
            }
        }
    }

    private object NoOpKeyboardListener : KeyboardUIController.OnKeyboardEventListener {
        override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit

        override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
    }
}
