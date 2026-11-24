package com.limelight.binding.input.advance_setting

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal fun keyboardTestRule() = ActivityScenarioRule<HelpActivity>(
    Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java).setData(Uri.parse("about:blank"))
)

internal fun setupKeyboard(activity: HelpActivity, prefs: String, widthDp: Int? = null, events: MutableList<Pair<Boolean, Short>> = mutableListOf()) {
    val context = object : ContextWrapper(activity) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefs-$name", mode)
    }
    context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
    val root = FrameLayout(activity)
    val parent = FrameLayout(activity)
    if (widthDp != null) parent.layoutParams = FrameLayout.LayoutParams(
        (widthDp * activity.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
    )
    root.addView(parent)
    KeyboardUIController(parent, object : KeyboardUIController.OnKeyboardEventListener {
        override fun sendKeyEvent(down: Boolean, keyCode: Short) { events.add(down to keyCode) }
        override fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int) = Unit
    }, context).show()
    activity.setContentView(root)
}

internal fun keyboardIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

@RunWith(AndroidJUnit4::class)
class KeyboardCompactNumpadTest {
    @get:Rule val activityRule = keyboardTestRule()

    @Test
    fun compactNumpadSendsNumpadKeysAndReturnsInFullAndMiniModes() {
        val events = mutableListOf<Pair<Boolean, Short>>()
        activityRule.scenario.onActivity { setupKeyboard(it, "compact-numpad-test", 360, events) }
        keyboardIdle()
        repeat(2) { mode ->
            activityRule.scenario.onActivity { activity ->
                if (mode == 1) activity.findViewById<View>(R.id.btn_keyboard_mini).performClick()
            }
            keyboardIdle()
            activityRule.scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btn_key_page_num).performClick()
                checkNotNull(findPageKey(activity.findViewById(R.id.layout_num), KeyboardPage.NUMPAD)).performClick()
            }
            keyboardIdle()
            activityRule.scenario.onActivity { activity ->
                val num = activity.findViewById<ViewGroup>(R.id.layout_num)
                assertTrue(num.isShown && activity.findViewById<View>(R.id.btn_key_page_num).isSelected)
                val key = checkNotNull(num.findViewWithTag<View>("k${KeyEvent.KEYCODE_NUMPAD_7}"))
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    MotionEvent.obtain(time, time, action, key.width / 2f, key.height / 2f, 0).also {
                        key.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
                checkNotNull(findPageKey(num, KeyboardPage.NUM)).performClick()
                assertTrue(num.findViewWithTag<View>("k${KeyEvent.KEYCODE_1}") != null)
                checkNotNull(findPageKey(num, KeyboardPage.NUMPAD)).performClick()
                checkNotNull(findPageKey(num, KeyboardPage.MAIN)).performClick()
                assertTrue(activity.findViewById<View>(R.id.layout_main).isShown)
            }
        }
        val pair = listOf(true to KeyEvent.KEYCODE_NUMPAD_7.toShort(), false to KeyEvent.KEYCODE_NUMPAD_7.toShort())
        assertEquals(pair + pair, events)
    }

    private fun findPageKey(view: View, page: KeyboardPage): KeyboardKeyView? {
        if (view is KeyboardKeyView && view.keySpec?.page == page) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findPageKey(view.getChildAt(i), page)?.let { return it }
        }
        return null
    }
}
