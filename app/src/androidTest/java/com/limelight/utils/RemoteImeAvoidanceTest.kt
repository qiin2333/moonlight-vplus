package com.limelight.utils

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
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

    private fun context() = RemoteTextContext(
        flags = 0xB3, revision = 1, activationId = 2, inputToken = 3,
        source = 2, cause = 1, anchorX = 800, anchorY = 1000,
        elementLeft = 0, elementTop = 0, elementRight = 1920, elementBottom = 1080,
        caretLeft = 0, caretTop = 0, caretRight = 0, caretBottom = 0,
        captureWidth = 1920, captureHeight = 1080,
    )

    @Test fun sessionResetBeforeFirstSurfaceChangeKeepsStreamCentered() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(stream, FrameLayout.LayoutParams(800, 400, Gravity.CENTER))
            root.addView(cursor, FrameLayout.LayoutParams(800, 400, Gravity.CENTER))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1000, 600)
            assertEquals(100f, stream.x, 0f)
            assertEquals(100f, stream.y, 0f)

            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            pan.setImeOffsetY(0f)
            pan.handleSurfaceChange()

            assertEquals(100f, stream.x, 0f)
            assertEquals(100f, stream.y, 0f)
            assertEquals(stream.x, cursor.x, 0f)
            assertEquals(stream.y, cursor.y, 0f)
        }
    }

    @Test fun firstSurfaceChangePreservesConfiguredStreamPosition() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(
                stream,
                FrameLayout.LayoutParams(800, 400, Gravity.TOP or Gravity.END),
            )
            root.addView(
                cursor,
                FrameLayout.LayoutParams(800, 400, Gravity.TOP or Gravity.END),
            )
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1000, 600)
            assertEquals(200f, stream.x, 0f)
            assertEquals(0f, stream.y, 0f)

            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            pan.setImeOffsetY(0f)
            pan.handleSurfaceChange()

            assertEquals(200f, stream.x, 0f)
            assertEquals(0f, stream.y, 0f)
            assertEquals(stream.x, cursor.x, 0f)
            assertEquals(stream.y, cursor.y, 0f)
        }
    }

    @Test fun surfaceChangesAdoptDisplayLayoutUntilUserTransforms() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            val streamParams = FrameLayout.LayoutParams(800, 400, Gravity.TOP or Gravity.END)
            val cursorParams = FrameLayout.LayoutParams(800, 400, Gravity.TOP or Gravity.END)
            root.addView(stream, streamParams)
            root.addView(cursor, cursorParams)
            fun layoutRoot() {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, 1000, 600)
            }
            layoutRoot()

            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            pan.handleSurfaceChange()
            assertEquals(200f, stream.x, 0f)
            assertEquals(0f, stream.y, 0f)

            streamParams.gravity = Gravity.BOTTOM or Gravity.START
            cursorParams.gravity = Gravity.BOTTOM or Gravity.START
            stream.layoutParams = streamParams
            cursor.layoutParams = cursorParams
            layoutRoot()
            pan.handleSurfaceChange()

            assertEquals(0f, stream.x, 0f)
            assertEquals(200f, stream.y, 0f)
            assertEquals(stream.x, cursor.x, 0f)
            assertEquals(stream.y, cursor.y, 0f)
        }
    }

    @Test fun boundaryDragAndMinimumPinchKeepAvoidanceActive() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(stream)
            root.addView(cursor)
            root.layout(0, 0, 1920, 1080)
            stream.layout(0, 0, 1920, 1080)
            cursor.layout(0, 0, 1920, 1080)
            val prefs = PreferenceConfiguration().apply { enablePip = false }
            val game = Game().apply { prefConfig = prefs }
            val pan = PanZoomHandler(activity, game, stream, cursor, prefs)
            val controller = RemoteImeController(activity, stream, pan)
            controller.handle(context())
            fun inset(bottom: Int) {
                stream.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, time: Long, vararg xs: Float) {
                val properties = Array(xs.size) { i -> MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(xs.size) { i -> MotionEvent.PointerCoords().apply {
                    x = xs[i]; y = 500f; pressure = 1f; size = 1f
                } }
                val e = MotionEvent.obtain(start, start + time, action, xs.size,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
                pan.handleTouchEvent(e)
                e.recycle()
            }
            try {
                inset(300)
                val initial = stream.y
                event(MotionEvent.ACTION_DOWN, 0, 600f)
                event(MotionEvent.ACTION_MOVE, 30, 800f) // Cannot pan a fitted viewport.
                event(MotionEvent.ACTION_UP, 60, 800f)
                inset(400)
                assertEquals(initial - 100f, stream.y, 0.1f)
                event(MotionEvent.ACTION_DOWN, 100, 500f)
                event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 130, 500f, 1100f)
                event(MotionEvent.ACTION_MOVE, 160, 600f, 1000f)
                event(MotionEvent.ACTION_MOVE, 190, 650f, 950f)
                event(MotionEvent.ACTION_MOVE, 220, 700f, 900f)
                event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 250, 700f, 900f)
                event(MotionEvent.ACTION_UP, 280, 700f)
                assertEquals(1f, stream.scaleX, 0f)
                inset(500)
                assertEquals(initial - 200f, stream.y, 0.1f)
            } finally {
                controller.dispose()
            }
        }
    }

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
            controller.handle(context())
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
                pan.handleSurfaceChange()
                assertEquals("Surface updates preserve user pan", dragged, stream.y, 0.1f)
                dispatch(true)
                val recomputed = stream.y
                assertEquals("Avoidance must not cancel user pan", dragged, recomputed, 0.1f)
                dispatch(false)
                val hidden = stream.y
                assertTrue("Persistent user pan survives dismissal", hidden < 0f)
                dispatch(true)
                val reopened = stream.y
                assertEquals("Reopening must not reuse the consumed anchor", hidden, reopened, 0.1f)

            } finally {
                controller.dispose()
            }
        }
    }

    @Test fun pinchZoomSurvivesSurfaceUpdates() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val cursor = View(activity)
            root.addView(stream)
            root.addView(cursor)
            root.layout(0, 0, 1920, 1080)
            stream.layout(0, 0, 1920, 1080)
            cursor.layout(0, 0, 1920, 1080)
            val pan = PanZoomHandler(activity, Game(), stream, cursor, PreferenceConfiguration())
            pan.handleSurfaceChange()

            val start = SystemClock.uptimeMillis()
            fun event(action: Int, time: Long, vararg xs: Float) {
                val properties = Array(xs.size) { i -> MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(xs.size) { i -> MotionEvent.PointerCoords().apply {
                    x = xs[i]; y = 500f; pressure = 1f; size = 1f
                } }
                val motionEvent = MotionEvent.obtain(
                    start, start + time, action, xs.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, 0, 0,
                )
                pan.handleTouchEvent(motionEvent)
                motionEvent.recycle()
            }

            event(MotionEvent.ACTION_DOWN, 0, 700f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 30, 700f, 1100f)
            event(MotionEvent.ACTION_MOVE, 60, 500f, 1300f)
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 90, 500f, 1300f)
            event(MotionEvent.ACTION_UP, 120, 500f)

            val scale = stream.scaleX
            assertTrue("Pinch must enlarge the stream", scale > 1f)
            pan.handleSurfaceChange()
            assertEquals("Surface updates preserve user zoom", scale, stream.scaleX, 0.001f)
            assertEquals(stream.scaleX, cursor.scaleX, 0f)
            assertEquals(stream.y, cursor.y, 0f)
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
            controller.handle(context())
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

}
