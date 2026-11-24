package com.limelight.binding.input.advance_setting

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
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
class KeyboardModifierChipPositionTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun shortViewportDoesNotCoverRailAndChipsReturnWhenSpaceIsAvailable() {
        lateinit var parent: FrameLayout
        lateinit var controller: KeyboardUIController
        activityRule.scenario.onActivity { activity ->
            val context = object : ContextWrapper(activity) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("modifier-position-test-$name", mode)
            }
            context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
            val density = activity.resources.displayMetrics.density
            parent = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams((360 * density).toInt(), (60 * density).toInt())
            }
            val root = FrameLayout(activity).apply { addView(parent) }
            controller = KeyboardUIController(parent, object : KeyboardUIController.OnKeyboardEventListener {
                override fun sendKeyEvent(down: Boolean, keyCode: Short) = Unit
                override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
            }, context)
            activity.setContentView(root)
            controller.show()
        }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            val content = parent.findViewById<View>(R.id.keyboard_content)
            content.translationY = -content.top.toFloat()
            val shift = checkNotNull(parent.findViewById<View>(R.id.layout_main).findViewWithTag<View>("k${KeyEvent.KEYCODE_SHIFT_LEFT}"))
            shift.performClick()
        }
        keyboardIdle()
        activityRule.scenario.onActivity { activity ->
            val chips = parent.findViewById<ViewGroup>(R.id.keyboard_modifier_chips)
            assertEquals(View.INVISIBLE, chips.visibility)
            parent.layoutParams = parent.layoutParams.apply {
                height = (300 * activity.resources.displayMetrics.density).toInt()
            }
        }
        keyboardIdle()
        activityRule.scenario.onActivity { controller.show() }
        keyboardIdle()
        activityRule.scenario.onActivity {
            val chips = parent.findViewById<ViewGroup>(R.id.keyboard_modifier_chips)
            assertEquals(View.VISIBLE, chips.visibility)
            assertTrue(chips.getChildAt(0).performClick())
            assertEquals(View.GONE, chips.visibility)
        }
    }
}
