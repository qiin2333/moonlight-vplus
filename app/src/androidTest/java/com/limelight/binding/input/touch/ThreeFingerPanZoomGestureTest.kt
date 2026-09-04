package com.limelight.binding.input.touch

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.utils.PanZoomHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeFingerPanZoomGestureTest {
    private data class Contact(val id: Int, val x: Float, val y: Float = 200f)
    private val contacts = listOf(Contact(5, 200f), Contact(2, 300f), Contact(9, 400f))

    private class Harness(panZoom: (MotionEvent) -> Unit = {}) {
        val events = mutableListOf<String>()
        var keyboardCount = 0
        val gesture = ThreeFingerPanZoomGesture(
            movementThreshold = 20f,
            cancelHostTouches = { events += "cancel-host" },
            panZoom = { events += "pan-${it.actionMasked}"; panZoom(it) },
            toggleKeyboard = { keyboardCount++ }
        )
    }

    @Test
    fun onlyThirdPointerDownStartsOwnershipAndCancelsHostOnce() {
        val h = Harness()
        assertFalse(send(h, MotionEvent.ACTION_DOWN, 0, contacts.take(1)))
        assertFalse(send(h, MotionEvent.ACTION_POINTER_DOWN, 10, contacts.take(2), actionIndex = 1))
        assertFalse(send(h, MotionEvent.ACTION_MOVE, 20, contacts))
        assertTrue(start(h))
        assertEquals(listOf("cancel-host", "pan-${MotionEvent.ACTION_DOWN}",
            "pan-${MotionEvent.ACTION_POINTER_DOWN}", "pan-${MotionEvent.ACTION_POINTER_DOWN}"), h.events)
        send(h, MotionEvent.ACTION_MOVE, 110, contacts)
        assertEquals(1, h.events.count { it == "cancel-host" })
    }

    @Test
    fun detectorDownSequencePreservesPointerIdsWhenNewContactIsNotLast() {
        val frames = mutableListOf<Pair<Int, List<Int>>>()
        val h = Harness { event ->
            frames += event.action to (0 until event.pointerCount).map(event::getPointerId)
        }
        send(h, MotionEvent.ACTION_POINTER_DOWN, 100, contacts, actionIndex = 1)
        assertEquals(listOf(5), frames[0].second)
        assertEquals(MotionEvent.ACTION_DOWN, frames[0].first)
        assertEquals(listOf(5, 9), frames[1].second)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), frames[1].first)
        assertEquals(listOf(5, 2, 9), frames[2].second)
    }

    @Test
    fun excludedModeDoesNotCaptureOrCancelInput() {
        val h = Harness()
        assertFalse(start(h, enabled = false))
        assertTrue(h.events.isEmpty())
        assertReleased(h)
    }

    @Test
    fun settingChangeDoesNotLeakTailAndNextGestureCanBeDisabled() {
        val h = Harness()
        start(h)
        assertTrue(send(h, MotionEvent.ACTION_POINTER_UP, 150, contacts, enabled = false))
        assertTrue(send(h, MotionEvent.ACTION_MOVE, 200, contacts.drop(1), enabled = false))
        assertTrue(send(h, MotionEvent.ACTION_UP, 410, contacts.takeLast(1), enabled = false))
        assertReleased(h)
        assertFalse(start(h, enabled = false))
        assertEquals(0, h.keyboardCount)
    }

    @Test
    fun quickTapTogglesOnceOnlyWhenKeyboardGestureIsEnabled() {
        for (fingers in listOf(3, -1, 4)) {
            val h = Harness()
            start(h, fingers = fingers)
            send(h, MotionEvent.ACTION_POINTER_UP, 150, contacts)
            send(h, MotionEvent.ACTION_POINTER_UP, 170, contacts.drop(1))
            send(h, MotionEvent.ACTION_UP, 180, contacts.takeLast(1))
            assertEquals(if (fingers == 3) 1 else 0, h.keyboardCount)
            assertReleased(h)
        }
    }

    @Test
    fun tailMovementUsesPointerIdsAndLiftCoordinates() {
        val h = Harness()
        start(h)
        send(h, MotionEvent.ACTION_POINTER_UP, 140, contacts, actionIndex = 0)
        send(h, MotionEvent.ACTION_POINTER_UP, 160, contacts.drop(1).reversed(), actionIndex = 1)
        send(h, MotionEvent.ACTION_UP, 180, listOf(Contact(9, 450f)))
        assertEquals(0, h.keyboardCount)
    }

    @Test
    fun historicalMovementCannotBeHiddenByReturningToStart() {
        val h = Harness()
        start(h)
        val move = event(MotionEvent.ACTION_MOVE, 150, contacts.map { it.copy(x = it.x + 50) })
        try {
            move.addBatch(1_180, coordinates(contacts), 0)
            h.gesture.handle(move, enabled = true, keyboardFingers = 3)
        } finally {
            move.recycle()
        }
        send(h, MotionEvent.ACTION_UP, 200, contacts.takeLast(1))
        assertEquals(0, h.keyboardCount)
    }

    @Test
    fun additionalOrReplacementContactCannotToggleKeyboard() {
        for (extraContacts in listOf(contacts + Contact(10, 500f), contacts.take(2) + Contact(10, 400f))) {
            val h = Harness()
            start(h)
            send(h, MotionEvent.ACTION_POINTER_DOWN, 150, extraContacts, actionIndex = extraContacts.lastIndex)
            send(h, MotionEvent.ACTION_UP, 200, contacts.takeLast(1))
            assertEquals(0, h.keyboardCount)
        }
    }

    @Test
    fun systemCancelAndCaptureLossResetWithoutKeyboard() {
        val h = Harness()
        start(h)
        send(h, MotionEvent.ACTION_CANCEL, 150, contacts)
        assertReleased(h)
        start(h)
        h.gesture.cancel()
        h.gesture.cancel()
        assertReleased(h)
        assertEquals(2, h.events.count { it == "pan-${MotionEvent.ACTION_CANCEL}" })
        assertEquals(0, h.keyboardCount)
        assertFalse(send(h, MotionEvent.ACTION_DOWN, 300, contacts.take(1)))
    }

    @Test
    fun canceledLiftDoesNotToggleKeyboard() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val h = Harness()
        start(h)
        send(h, MotionEvent.ACTION_POINTER_UP, 150, contacts, flags = MotionEvent.FLAG_CANCELED)
        send(h, MotionEvent.ACTION_UP, 180, contacts.takeLast(1))
        assertEquals(0, h.keyboardCount)
    }

    @Test
    fun newDownResetsGestureWithMissingTerminalEvent() {
        val h = Harness()
        start(h)
        assertFalse(send(h, MotionEvent.ACTION_DOWN, 200, contacts.take(1)))
        assertReleased(h)
        assertEquals("pan-${MotionEvent.ACTION_CANCEL}", h.events.last())
    }

    @Test
    fun realAndroidDetectorsScaleAndPanStreamAndCursorTogether() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val density = context.resources.displayMetrics.density
            val width = (800 * density).toInt()
            val height = (600 * density).toInt()
            val parent = FrameLayout(context)
            val stream = View(context)
            val cursor = View(context)
            parent.addView(stream, FrameLayout.LayoutParams(width, height))
            parent.addView(cursor, FrameLayout.LayoutParams(width, height))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, width, height)
            val panZoom = PanZoomHandler(context, stream, cursor) {}
            panZoom.handleSurfaceChange()
            val h = Harness(panZoom::handleTouchEvent)
            val baseTime = SystemClock.uptimeMillis()
            fun points(left: Float, middle: Float, right: Float) = listOf(
                Contact(5, left * density, 200 * density),
                Contact(2, middle * density, 200 * density),
                Contact(9, right * density, 200 * density)
            )
            send(h, MotionEvent.ACTION_POINTER_DOWN, 100, points(200f, 300f, 400f), actionIndex = 2, baseTime = baseTime)
            send(h, MotionEvent.ACTION_MOVE, 200, points(100f, 300f, 500f), baseTime = baseTime)
            send(h, MotionEvent.ACTION_MOVE, 250, points(0f, 300f, 600f), baseTime = baseTime)
            assertTrue("Pinch changes scale", stream.scaleX > 1f)
            val oldX = stream.x
            send(h, MotionEvent.ACTION_MOVE, 280, points(-40f, 260f, 560f), baseTime = baseTime)
            assertTrue("Translation follows the three-finger movement", stream.x < oldX)
            assertEquals(stream.scaleX, cursor.scaleX, 0f)
            assertEquals(stream.x, cursor.x, 0f)
            assertEquals(stream.y, cursor.y, 0f)
            send(h, MotionEvent.ACTION_CANCEL, 300, contacts, baseTime = baseTime)
        }
    }

    private fun assertReleased(h: Harness) {
        assertFalse(send(h, MotionEvent.ACTION_MOVE, 500, contacts, enabled = false))
    }

    private fun start(h: Harness, enabled: Boolean = true, fingers: Int = 3, baseTime: Long = 1_000L) =
        send(h, MotionEvent.ACTION_POINTER_DOWN, 100, contacts, actionIndex = 2,
            enabled = enabled, fingers = fingers, baseTime = baseTime)

    private fun send(
        h: Harness,
        action: Int,
        time: Long,
        points: List<Contact>,
        actionIndex: Int = 0,
        enabled: Boolean = true,
        fingers: Int = 3,
        flags: Int = 0,
        baseTime: Long = 1_000L
    ): Boolean {
        val event = event(action, time, points, actionIndex, flags, baseTime)
        return try {
            h.gesture.handle(event, enabled, fingers)
        } finally {
            event.recycle()
        }
    }

    private fun coordinates(points: List<Contact>) = points.map {
        MotionEvent.PointerCoords().apply { x = it.x; y = it.y; pressure = 1f; size = 0.1f }
    }.toTypedArray()

    private fun event(
        action: Int,
        time: Long,
        points: List<Contact>,
        actionIndex: Int = 0,
        flags: Int = 0,
        baseTime: Long = 1_000L
    ) = MotionEvent.obtain(
        baseTime, baseTime + time, action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points.size,
        points.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
        coordinates(points), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags
    )
}
