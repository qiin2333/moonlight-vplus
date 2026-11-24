package com.limelight.binding.input.advance_setting

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardAccessibilityHoldTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun accessibilityClickDoesNotReleaseTouchedKey() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { setupKeyboard(it, "keyboard-accessibility-hold-test", 360, events) }
        keyboardIdle()
        var duringClick = emptyList<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { activity ->
            val page = activity.findViewById<View>(R.id.layout_main)
            val key = checkNotNull(page.findViewWithTag<View>("k${KeyEvent.KEYCODE_Q}"))
            val time = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, key.width / 2f, key.height / 2f, 0)
                try { key.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            key.performClick()
            duringClick = events.toList()
            touch(MotionEvent.ACTION_UP)
        }
        val down = true to KeyEvent.KEYCODE_Q.toShort()
        val up = false to KeyEvent.KEYCODE_Q.toShort()
        assertEquals(listOf(down), duringClick)
        assertEquals(listOf(down, up), events)
    }
}
