package com.limelight.binding.input.advance_setting

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardNarrowRailTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun hideButtonIsFullyVisibleAndAcceptsTouchAtRightEdge() {
        activityRule.scenario.onActivity { setupKeyboard(it, "keyboard-narrow-rail-test", 240) }
        keyboardIdle()

        var touchX = 0f
        var touchY = 0f
        activityRule.scenario.onActivity { activity ->
            val hide = activity.findViewById<View>(R.id.btn_keyboard_hide)
            val visible = Rect()
            assertTrue(hide.getGlobalVisibleRect(visible))
            assertEquals(hide.width, visible.width())
            assertEquals(hide.height, visible.height())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_mini).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_resize).visibility)
            val location = IntArray(2)
            hide.getLocationOnScreen(location)
            touchX = location[0] + hide.width - 2 * activity.resources.displayMetrics.density
            touchY = location[1] + hide.height / 2f
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, touchX, touchY, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.layer_6_keyboard).visibility)
        }
    }
}
