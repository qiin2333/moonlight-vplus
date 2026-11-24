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

        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_mini).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_resize).visibility)
        }
        tapRightEdge(R.id.btn_keyboard_hide)
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.layer_6_keyboard).visibility)
        }
    }

    @Test
    fun narrowMiniCanExpandEvenAfterViewportShrinks() {
        activityRule.scenario.onActivity { setupKeyboard(it, "keyboard-narrow-mini-test", 320) }
        keyboardIdle()
        tapRightEdge(R.id.btn_keyboard_mini)
        activityRule.scenario.onActivity { activity ->
            val dock = activity.findViewById<View>(R.id.keyboard_content)
            assertTrue(dock.width / activity.resources.displayMetrics.density < 300f)
            assertEquals(activity.getString(R.string.keyboard_expand),
                activity.findViewById<View>(R.id.btn_keyboard_mini).contentDescription.toString())
            assertFullyVisible(activity.findViewById(R.id.btn_keyboard_mini))
            assertFullyVisible(activity.findViewById(R.id.btn_keyboard_hide))
            val parent = activity.findViewById<View>(R.id.layer_6_keyboard).parent as View
            parent.layoutParams = parent.layoutParams.apply {
                width = (240 * activity.resources.displayMetrics.density).toInt()
            }
        }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            assertFullyVisible(activity.findViewById(R.id.btn_keyboard_mini))
            assertFullyVisible(activity.findViewById(R.id.btn_keyboard_hide))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_opacity).visibility)
        }
        tapRightEdge(R.id.btn_keyboard_mini)
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btn_keyboard_mini).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_keyboard_opacity).visibility)
        }
        tapRightEdge(R.id.btn_keyboard_hide)
        activityRule.scenario.onActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.layer_6_keyboard).visibility)
        }
    }

    private fun tapRightEdge(id: Int) {
        var touchX = 0f
        var touchY = 0f
        activityRule.scenario.onActivity { activity ->
            val view = activity.findViewById<View>(id)
            assertFullyVisible(view)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            touchX = location[0] + view.width - 2 * activity.resources.displayMetrics.density
            touchY = location[1] + view.height / 2f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, touchX, touchY, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        keyboardIdle()
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        assertEquals(view.width, visible.width())
        assertEquals(view.height, visible.height())
    }
}
