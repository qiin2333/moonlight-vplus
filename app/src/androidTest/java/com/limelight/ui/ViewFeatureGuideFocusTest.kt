package com.limelight.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ViewFeatureGuideFocusTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    private val target = AtomicReference<Button>()

    @Test
    fun dismissingFirstStepHidesGuideOnNextEntry() {
        activityRule.scenario.onActivity { activity ->
            target.set(Button(activity).apply {
                text = TARGET_LABEL
                isFocusable = true
            })
            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    target.get(),
                    FrameLayout.LayoutParams(240, 120, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                )
            })
        }
        waitForIdle()

        activityRule.scenario.onActivity { activity ->
            activity.getSharedPreferences("feature_guides", Activity.MODE_PRIVATE)
                .edit()
                .remove("tv_focus_test_v1")
                .commit()
            target.get().requestFocus()
            assertTrue(
                ViewFeatureGuide.show(
                    activity = activity,
                    spec = FeatureGuideSpec("tv_focus_test", revision = 1),
                    steps = listOf(
                        ViewFeatureGuideStep(target.get(), "First step", "First body"),
                        ViewFeatureGuideStep(target.get(), "Second step", "Second body")
                    )
                )
            )
        }
        waitForIdle()

        assertFocusedText(R.string.feature_guide_next)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocusedText(R.string.feature_guide_skip)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForIdle()
        activityRule.scenario.onActivity { activity ->
            assertTrue(target.get().hasFocus())
            val spec = FeatureGuideSpec("tv_focus_test", revision = 1)
            assertFalse(FeatureGuideStore(activity).shouldShow(spec))
            assertFalse(
                ViewFeatureGuide.show(
                    activity = activity,
                    spec = spec,
                    steps = listOf(ViewFeatureGuideStep(target.get(), "First step", "First body"))
                )
            )
        }
    }

    @Test
    fun remoteBackDismissesGuideAndRemembersChoice() {
        showSingleStepGuide("tv_remote_back_test")

        press(KeyEvent.KEYCODE_BACK)

        assertGuideDismissedAndRemembered("tv_remote_back_test")
    }

    @Test
    fun gamepadButtonBDismissesGuideAndRemembersChoice() {
        showSingleStepGuide("gamepad_b_test")

        press(KeyEvent.KEYCODE_BUTTON_B)

        assertGuideDismissedAndRemembered("gamepad_b_test")
    }

    @Test
    fun gamepadButtonAConfirmsFocusedGuideAction() {
        showSingleStepGuide("gamepad_a_test")

        press(KeyEvent.KEYCODE_BUTTON_A)

        assertGuideDismissedAndRemembered("gamepad_a_test")
    }

    @Test
    fun guideReclaimsFocusAfterUnderlyingViewRequestsIt() {
        showSingleStepGuide("focus_reclaim_test")

        activityRule.scenario.onActivity { target.get().requestFocus() }
        waitForIdle()

        assertFocusedText(R.string.feature_guide_done)
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertGuideDismissedAndRemembered("focus_reclaim_test")
    }

    @Test
    fun touchActivatesNonFocusedSkipButtonOnce() {
        showTwoStepGuide("touch_skip_test")

        tapGuideButton(R.string.feature_guide_skip)

        assertGuideDismissedAndRemembered("touch_skip_test")
    }

    @Test
    fun touchActivatesNonFocusedNextButtonOnce() {
        showTwoStepGuide("touch_next_test")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        tapGuideButton(R.string.feature_guide_next)

        assertFocusedText(R.string.feature_guide_done)
        activityRule.scenario.onActivity { activity ->
            assertTrue(
                FeatureGuideStore(activity).shouldShow(
                    FeatureGuideSpec("touch_next_test", revision = 1)
                )
            )
        }
    }

    private fun showSingleStepGuide(id: String) {
        activityRule.scenario.onActivity { activity ->
            target.set(Button(activity).apply {
                text = TARGET_LABEL
                isFocusable = true
            })
            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    target.get(),
                    FrameLayout.LayoutParams(240, 120, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                )
            })
            activity.getSharedPreferences("feature_guides", Activity.MODE_PRIVATE)
                .edit()
                .remove("${id}_v1")
                .commit()
        }
        waitForIdle()

        activityRule.scenario.onActivity { activity ->
            target.get().requestFocus()
            assertTrue(
                ViewFeatureGuide.show(
                    activity = activity,
                    spec = FeatureGuideSpec(id, revision = 1),
                    steps = listOf(ViewFeatureGuideStep(target.get(), "Only step", "Only body"))
                )
            )
        }
        waitForIdle()
        assertFocusedText(R.string.feature_guide_done)
    }

    private fun showTwoStepGuide(id: String) {
        activityRule.scenario.onActivity { activity ->
            target.set(Button(activity).apply {
                text = TARGET_LABEL
                isFocusable = true
            })
            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    target.get(),
                    FrameLayout.LayoutParams(240, 120, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                )
            })
            activity.getSharedPreferences("feature_guides", Activity.MODE_PRIVATE)
                .edit()
                .remove("${id}_v1")
                .commit()
        }
        waitForIdle()

        activityRule.scenario.onActivity { activity ->
            target.get().requestFocus()
            assertTrue(
                ViewFeatureGuide.show(
                    activity = activity,
                    spec = FeatureGuideSpec(id, revision = 1),
                    steps = listOf(
                        ViewFeatureGuideStep(target.get(), "First step", "First body"),
                        ViewFeatureGuideStep(target.get(), "Second step", "Second body")
                    )
                )
            )
        }
        waitForIdle()
        assertFocusedText(R.string.feature_guide_next)
    }

    private fun tapGuideButton(textRes: Int) {
        val text = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(textRes)
        val location = IntArray(2)
        activityRule.scenario.onActivity { activity ->
            val button = findTextView(activity.findViewById(android.R.id.content), text)
                ?: error("Guide button not found: $text")
            button.getLocationOnScreen(location)
            location[0] += button.width / 2
            location[1] += button.height / 2
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        for ((action, eventTime) in listOf(
            MotionEvent.ACTION_DOWN to downTime,
            MotionEvent.ACTION_UP to SystemClock.uptimeMillis()
        )) {
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = location[0].toFloat()
                    y = location[1].toFloat()
                    pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                    size = 1f
                }),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        waitForIdle()
    }

    private fun findTextView(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun assertGuideDismissedAndRemembered(id: String) {
        activityRule.scenario.onActivity { activity ->
            assertTrue(target.get().hasFocus())
            assertFalse(FeatureGuideStore(activity).shouldShow(FeatureGuideSpec(id, revision = 1)))
        }
    }

    private fun assertFocusedText(textRes: Int) {
        activityRule.scenario.onActivity { activity ->
            assertEquals(activity.getString(textRes), (activity.currentFocus as TextView).text.toString())
        }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        waitForIdle()
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        const val TARGET_LABEL = "Guide target"
    }
}
