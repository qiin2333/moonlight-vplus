package com.limelight.binding.input.advance_setting

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardFocusResetTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun resetReleasesHeldKeysOnceAndClearsModifiersAndGestures() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        lateinit var controller: KeyboardUIController
        activityRule.scenario.onActivity { activity ->
            val context = object : ContextWrapper(activity) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("keyboard-focus-test-$name", mode)
            }
            context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
            val parent = FrameLayout(activity)
            controller = KeyboardUIController(parent, object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) { events.add(down to keyCode) }
                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }, context)
            controller.show()
            activity.setContentView(parent)
        }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            val main = activity.findViewById<ViewGroup>(R.id.layout_main)
            val shift = checkNotNull(main.findViewWithTag<View>("k${KeyEvent.KEYCODE_SHIFT_LEFT}"))
            val ctrl = checkNotNull(main.findViewWithTag<View>("k${KeyEvent.KEYCODE_CTRL_LEFT}"))
            val q = checkNotNull(main.findViewWithTag<View>("k${KeyEvent.KEYCODE_Q}"))
            val symbol = checkNotNull(activity.findViewById<View>(R.id.layout_num).findViewWithTag<View>("k1008"))

            touch(shift, MotionEvent.ACTION_DOWN)
            touch(shift, MotionEvent.ACTION_UP)
            controller.onKeyPress(ctrl, KeyEvent.KEYCODE_CTRL_LEFT)
            controller.onKeyRelease(ctrl, KeyEvent.KEYCODE_CTRL_LEFT)
            controller.onKeyPress(ctrl, KeyEvent.KEYCODE_CTRL_LEFT) // Lock Ctrl.
            controller.onKeyRelease(ctrl, KeyEvent.KEYCODE_CTRL_LEFT)
            controller.onKeyPress(q, KeyEvent.KEYCODE_Q)
            controller.onKeyPress(symbol, 1008)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.keyboard_modifier_chips).visibility)

            val beforeReset = events.size
            controller.resetInputState()
            val releases = events.drop(beforeReset)
            assertEquals(4, releases.size)
            assertTrue(releases.all { !it.first })
            assertEquals(setOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT).map { it.toShort() }.toSet(),
                releases.map { it.second }.toSet())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.keyboard_modifier_chips).visibility)

            controller.resetInputState()
            controller.onKeyRelease(q, KeyEvent.KEYCODE_Q)
            controller.onKeyRelease(symbol, 1008)
            controller.onModifierHoldRelease(shift, KeyEvent.KEYCODE_SHIFT_LEFT)
            assertEquals(beforeReset + 4, events.size)

            // A quick tap after focus returns must not be mistaken for a double tap.
            touch(shift, MotionEvent.ACTION_DOWN)
            touch(shift, MotionEvent.ACTION_UP)
            assertEquals(true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(), events.last())
            controller.resetInputState()
            assertEquals(false to KeyEvent.KEYCODE_SHIFT_LEFT.toShort(), events.last())
        }
    }

    private fun touch(view: View, action: Int) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, 1f, 1f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
    }
}
