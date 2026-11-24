package com.limelight.binding.input.advance_setting

import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardSliderPopoverTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun opacityAndHeightPopoversKeepTheirOwnValuesAcrossSwitches() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { setupKeyboard(it, "keyboard-slider-test", events = events) }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            assertTrue(activity.hasWindowFocus())
            val shift = checkNotNull(activity.findViewById<View>(R.id.layout_main)
                .findViewWithTag<View>("k${KeyEvent.KEYCODE_SHIFT_LEFT}"))
            shift.performClick()
            shift.performClick()
        }

        open(R.id.btn_keyboard_opacity)
        assertActivityFocus()
        popup(R.id.keyboard_slider_title).check(matches(withText(R.string.keyboard_opacity_title)))
        presets("100%", "85%", "70%", "50%")
        popup(R.id.keyboard_slider_seek).perform(swipeLeft())
        popup(R.id.keyboard_slider_value).check { view, _ ->
            assertTrue((view as TextView).text.toString().removeSuffix("%").toInt() < 100)
        }
        tapOutsidePopup()
        open(R.id.btn_keyboard_opacity) // Opens again only if the outside tap dismissed it.
        popup(R.id.keyboard_slider_preset_2).perform(click())
        popup(R.id.keyboard_slider_value).check(matches(withText("85%")))

        open(R.id.btn_keyboard_resize)
        assertActivityFocus()
        popup(R.id.keyboard_slider_title).check(matches(withText(R.string.keyboard_key_height)))
        presets("31", "38", "44", "50")
        popup(R.id.keyboard_slider_preset_1).perform(click())
        popup(R.id.keyboard_slider_value).check(matches(withText("31dp")))

        open(R.id.btn_keyboard_opacity)
        popup(R.id.keyboard_slider_value).check(matches(withText("85%")))
        open(R.id.btn_keyboard_opacity)
        open(R.id.btn_keyboard_resize)
        popup(R.id.keyboard_slider_value).check(matches(withText("31dp")))
        assertActivityFocus()
        assertEquals(listOf(true to KeyEvent.KEYCODE_SHIFT_LEFT.toShort()), events)
    }

    private fun popup(id: Int) = onView(withId(id)).inRoot(isPlatformPopup())

    private fun presets(vararg expected: String) {
        listOf(R.id.keyboard_slider_preset_1, R.id.keyboard_slider_preset_2,
            R.id.keyboard_slider_preset_3, R.id.keyboard_slider_preset_4).forEachIndexed { index, id ->
            popup(id).check(matches(withText(expected[index])))
        }
    }

    private fun open(id: Int) {
        activityRule.scenario.onActivity { it.findViewById<View>(id).performClick() }
        keyboardIdle()
    }

    private fun assertActivityFocus() {
        activityRule.scenario.onActivity { assertTrue(it.hasWindowFocus()) }
    }

    private fun tapOutsidePopup() {
        val coordinates = IntArray(2)
        activityRule.scenario.onActivity {
            val decor = it.window.decorView
            decor.getLocationOnScreen(coordinates)
            coordinates[0] += (2 * it.resources.displayMetrics.density).toInt()
            coordinates[1] += decor.height / 2
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, time, action, coordinates[0].toFloat(), coordinates[1].toFloat(), 0).also {
                instrumentation.sendPointerSync(it)
                it.recycle()
            }
        }
        keyboardIdle()
        assertActivityFocus()
    }
}
