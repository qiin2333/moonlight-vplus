package com.limelight.binding.input.advance_setting

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertFalse
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
