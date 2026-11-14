package com.limelight.binding.input.advance_setting.superpage

import android.widget.SeekBar
import android.widget.TextView
import android.view.LayoutInflater
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NumberSeekbarInputTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun nonzeroBoundsKeepDisplayedAndSavedValuesConsistent() {
        activity.scenario.onActivity { host ->
            fun id(name: String) = host.resources.getIdentifier(name, "id", host.packageName)
            host.setTheme(host.resources.getIdentifier("StreamTheme", "style", host.packageName))
            val page = LayoutInflater.from(host).inflate(
                host.resources.getIdentifier("page_edit", "layout", host.packageName), null)
            host.setContentView(page)
            val control = page.findViewById<NumberSeekbar>(id("page_edit_edit_grid_width"))
            control.setProgressMin(40)
            control.setProgressMax(140)
            control.setValueWithNoCallBack(100)
            val slider = control.findViewById<SeekBar>(id("number_seekbar_seekbar"))
            val text = control.findViewById<TextView>(id("number_seekbar_number"))
            val changes = mutableListOf<Int>()
            val saved = mutableListOf<Int>()
            var starts = 0
            control.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    changes += progress
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { starts++ }
                override fun onStopTrackingTouch(seekBar: SeekBar) { saved += control.value }
            })

            slider.progress = 0
            assertEquals(40, control.value)
            assertEquals("40", text.text.toString())
            control.findViewById<android.view.View>(id("number_seekbar_minus")).performClick()
            assertEquals(0, starts)
            control.findViewById<android.view.View>(id("number_seekbar_add")).performClick()
            assertEquals(listOf(41), saved)

            slider.progress = slider.max
            assertEquals(140, control.value)
            assertEquals("140", text.text.toString())
            control.findViewById<android.view.View>(id("number_seekbar_add")).performClick()
            assertEquals(1, starts)
            control.setProgressMax(80)
            assertEquals(80, control.value)
            assertEquals("80", text.text.toString())
            assertEquals(listOf(40, 41, 140, 80), changes)
            control.setValueWithNoCallBack(1_000)
            assertEquals(80, control.value)
            assertEquals("80", text.text.toString())
            assertEquals(listOf(40, 41, 140, 80), changes)

            control.findViewById<android.view.View>(id("number_seekbar_minus")).performClick()
            assertEquals(listOf(41, 79), saved)
            assertEquals(2, starts)
            assertEquals("79", text.text.toString())
            control.setProgressMin(80)
            assertEquals(80, control.value)
            assertEquals(0, slider.max)
        }
    }

    @Test fun touchDragUpdatesLogicalValue() {
        activity.scenario.onActivity { host ->
            host.setTheme(host.resources.getIdentifier("StreamTheme", "style", host.packageName))
            val page = LayoutInflater.from(host).inflate(
                host.resources.getIdentifier("page_edit", "layout", host.packageName), null)
            host.setContentView(page)
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activity.scenario.onActivity { host ->
            fun id(name: String) = host.resources.getIdentifier(name, "id", host.packageName)
            val control = host.findViewById<NumberSeekbar>(id("page_edit_edit_grid_width"))
            control.setProgressMin(40)
            control.setProgressMax(140)
            control.setValueWithNoCallBack(60)
            val slider = control.findViewById<SeekBar>(id("number_seekbar_seekbar"))
            val before = control.value
            check(slider.width > 0 && slider.height > 0) {
                "slider must be laid out before dispatching touch"
            }

            val y = slider.height / 2f
            val startX = slider.width * 0.2f
            val endX = slider.width * 0.8f
            val downTime = android.os.SystemClock.uptimeMillis()
            val events = listOf(
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y, 0),
                MotionEvent.obtain(downTime, downTime + 16, MotionEvent.ACTION_MOVE, endX, y, 0),
                MotionEvent.obtain(downTime, downTime + 32, MotionEvent.ACTION_UP, endX, y, 0)
            )
            try {
                events.forEach { event -> check(slider.dispatchTouchEvent(event)) }
            } finally {
                events.forEach(MotionEvent::recycle)
            }

            check(control.value > before) {
                "touch drag should move NumberSeekbar from $before, got ${control.value}"
            }
        }
    }
}
