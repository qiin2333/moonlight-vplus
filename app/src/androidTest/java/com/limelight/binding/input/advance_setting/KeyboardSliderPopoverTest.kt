package com.limelight.binding.input.advance_setting

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardSliderPopoverTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun opacityAndHeightPopoversKeepTheirOwnValuesAcrossSwitches() {
        activityRule.scenario.onActivity { setupKeyboard(it, "keyboard-slider-test") }
        keyboardIdle()

        open(R.id.btn_keyboard_opacity)
        popup(R.id.keyboard_slider_title).check(matches(withText(R.string.keyboard_opacity_title)))
        presets("100%", "85%", "70%", "50%")
        popup(R.id.keyboard_slider_preset_2).perform(click())
        popup(R.id.keyboard_slider_value).check(matches(withText("85%")))

        open(R.id.btn_keyboard_resize)
        popup(R.id.keyboard_slider_title).check(matches(withText(R.string.keyboard_key_height)))
        presets("31", "38", "44", "50")
        popup(R.id.keyboard_slider_preset_1).perform(click())
        popup(R.id.keyboard_slider_value).check(matches(withText("31dp")))

        open(R.id.btn_keyboard_opacity)
        popup(R.id.keyboard_slider_value).check(matches(withText("85%")))
        open(R.id.btn_keyboard_opacity)
        open(R.id.btn_keyboard_resize)
        popup(R.id.keyboard_slider_value).check(matches(withText("31dp")))
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
}
