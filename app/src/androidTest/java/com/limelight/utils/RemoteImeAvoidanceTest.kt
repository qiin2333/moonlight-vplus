package com.limelight.utils

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import android.graphics.Insets
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.limelight.Game
import com.limelight.HelpActivity
import com.limelight.nvstream.RemoteTextContext
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator keyboard plus deterministic injected Insets; not a vendor compatibility matrix. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class RemoteImeAvoidanceTest {
    @get:Rule val rule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank")),
    )

    @Test fun manualPanTakesControlAndReopeningDoesNotReuseAnchor() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(stream)
            root.addView(cursor)
            root.layout(0, 0, 1920, 1080)
            // Larger content permits genuine scrolling through the production gesture handler.
            stream.layout(0, 0, 1920, 2160)
            cursor.layout(0, 0, 1920, 2160)
            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            val controller = RemoteImeController(activity, stream, pan)
            controller.handle(RemoteTextContext(
                flags = 0xB3, revision = 1, activationId = 2, inputToken = 3,
                source = 2, cause = 1, anchorX = 800, anchorY = 1000,
                elementLeft = 0, elementTop = 0, elementRight = 1920, elementBottom = 1080,
                caretLeft = 0, caretTop = 0, caretRight = 0, caretBottom = 0,
                captureWidth = 1920, captureHeight = 1080,
            ))
            fun dispatch(visible: Boolean) {
                stream.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, if (visible) 400 else 0))
                    .setVisible(WindowInsets.Type.ime(), visible).build())
            }
            try {
                dispatch(true)
                val before = stream.y
                val start = SystemClock.uptimeMillis()
                fun touch(action: Int, elapsed: Long, y: Float) {
                    val event = MotionEvent.obtain(start, start + elapsed, action, 800f, y, 0)
                    pan.handleTouchEvent(event)
                    event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, 0, 600f)
                touch(MotionEvent.ACTION_MOVE, 30, 500f)
                touch(MotionEvent.ACTION_MOVE, 60, 400f)
                touch(MotionEvent.ACTION_UP, 90, 400f)
                val dragged = stream.y
                assertTrue("Gesture really moved the production transform", dragged < before)
                dispatch(true)
                val recomputed = stream.y
                assertEquals("Avoidance must not cancel user pan", dragged, recomputed, 0.1f)
                dispatch(false)
                val hidden = stream.y
                assertTrue("Persistent user pan survives dismissal", hidden < 0f)
                dispatch(true)
                val reopened = stream.y
                assertEquals("Reopening must not reuse the consumed anchor", hidden, reopened, 0.1f)
                android.util.Log.i("ImeAvoidanceProbe", "before=$before dragged=$dragged recomputed=$recomputed hidden=$hidden reopened=$reopened")
            } finally {
                controller.dispose()
            }
        }
    }

    @Test fun manuallyShownEmulatorKeyboardAvoidsAnchorAndHidingRestores() {
        lateinit var stream: StreamView
        lateinit var cursor: View
        lateinit var controller: RemoteImeController
        rule.scenario.onActivity { activity ->
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            val root = FrameLayout(activity)
            stream = StreamView(activity)
            cursor = View(activity)
            root.addView(stream, FrameLayout.LayoutParams(-1, -1))
            root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
            activity.setContentView(root)
            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            controller = RemoteImeController(activity, stream, pan)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.scenario.onActivity { activity ->
            controller.handle(RemoteTextContext(
                flags = 0xB3, revision = 1, activationId = 2, inputToken = 3,
                source = 2, cause = 1, anchorX = 800, anchorY = 1000,
                elementLeft = 0, elementTop = 0, elementRight = 1920, elementBottom = 1080,
                caretLeft = 0, caretTop = 0, caretRight = 0, caretBottom = 0,
                captureWidth = 1920, captureHeight = 1080,
            ))
            assertFalse(ViewCompat.getRootWindowInsets(stream)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            // Explicit user action equivalent; no host-triggered show in the controller.
            stream.isFocusableInTouchMode = true
            stream.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(stream, InputMethodManager.SHOW_IMPLICIT)
        }
        fun awaitKeyboard(visible: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            var reached = false
            while (!reached && SystemClock.uptimeMillis() < deadline) {
                rule.scenario.onActivity {
                    val insets = ViewCompat.getRootWindowInsets(stream)
                    reached = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                        (if (visible) stream.y < 0f else stream.y == 0f)
                }
                if (!reached) SystemClock.sleep(50)
            }
            assertTrue("Keyboard visible=$visible and matching viewport offset", reached)
        }
        try {
            awaitKeyboard(true)
            rule.scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(stream)!!
                val root = stream.rootView
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                val bottom = location[1] + root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                stream.getLocationOnScreen(location)
                val anchorOnScreen = location[1] + stream.height * (1000f / 1080f)
                assertTrue("Clicked point must be above keyboard", anchorOnScreen <= bottom + 1f)
                assertEquals(stream.y, cursor.y, 0f)
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(stream.windowToken, 0)
            }
            awaitKeyboard(false)
        } finally {
            rule.scenario.onActivity { controller.dispose() }
        }
    }

    @Test fun dockedInsetsMoveBothViewsAndRestoreWithoutAccumulation() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(stream)
            root.addView(cursor)
            // Detached deterministic geometry, running in the emulator UI thread.
            root.layout(0, 0, 1920, 1080)
            stream.layout(0, 0, 1920, 1080)
            cursor.layout(0, 0, 1920, 1080)
            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            val controller = RemoteImeController(activity, stream, pan)
            val c = RemoteTextContext(
                flags = 0xB3, revision = 1, activationId = 2, inputToken = 3,
                source = 2, cause = 1, anchorX = 800, anchorY = 1000,
                elementLeft = 0, elementTop = 0, elementRight = 1920, elementBottom = 1080,
                caretLeft = 0, caretTop = 0, caretRight = 0, caretBottom = 0,
                captureWidth = 1920, captureHeight = 1080,
            )
            fun insets(visible: Boolean, bottom: Int) = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsets.Type.ime(), visible).build()
            fun dispatch(visible: Boolean, bottom: Int) {
                stream.dispatchApplyWindowInsets(insets(visible, bottom))
            }
            controller.handle(c)
            assertFalse("Host event must not request view focus", stream.hasFocus())
            assertEquals(0f, stream.y, 0f)
            val margin = 24 * activity.resources.displayMetrics.density
            repeat(20) {
                dispatch(true, 400)
                assertEquals(680f - margin - 1000f, stream.y, 0.01f)
                assertEquals(stream.y, cursor.y, 0f)
                assertEquals(1000f, pan.captureYToParent(1000, 1080), 0f)
            }
            dispatch(true, 0) // Floating keyboard: visible but no occlusion.
            assertEquals(0f, stream.y, 0f)
            dispatch(true, 400)
            dispatch(false, 0)
            assertEquals(0f, stream.y, 0f)
            dispatch(true, 400)
            controller.resetSession()
            assertEquals(0f, stream.y, 0f)
            controller.handle(c)
            dispatch(true, 400)
            controller.dispose()
            assertEquals(0f, stream.y, 0f)
            assertEquals(0f, cursor.y, 0f)
            controller.handle(RemoteTextContext(
                flags = 0xB3, revision = 2, activationId = 3, inputToken = 4,
                source = 2, cause = 1, anchorX = 800, anchorY = 1000,
                elementLeft = 0, elementTop = 0, elementRight = 1920, elementBottom = 1080,
                caretLeft = 0, caretTop = 0, caretRight = 0, caretBottom = 0,
                captureWidth = 1920, captureHeight = 1080,
            ))
            assertEquals(0f, stream.y, 0f)
        }
    }
}
