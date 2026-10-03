package com.limelight.binding.input.touchpad

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.audio.MicrophoneButtonPositionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatibilityTouchpadDispatchTest {
    private fun event(action: Int, x: Float = 20f, count: Int = 1,
                      buttons: Int = 0, source: Int = InputDevice.SOURCE_MOUSE, device: Int = 42,
                      dx: Float = 0f): MotionEvent = MotionEvent.obtain(
        1000, 1100, action, count,
        Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } },
        Array(count) { MotionEvent.PointerCoords().apply {
            this.x = if (it == 0) x else 280f; y = 80f; pressure = 1f
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx)
        } },
        0, buttons, 1f, 1f, device, 0, source, 0,
    )

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatch = CompatibilityTouchpadDispatch()
        val root = FrameLayout(context).apply { isMotionEventSplittingEnabled = true }
        val stream = View(context)
        val button = ImageButton(context)
        val frames = mutableListOf<MotionEvent>()
        var handleStreamInput: (MotionEvent) -> Boolean = { true }
        var mapPosition: ((MotionEvent) -> CompatibilityTouchpadGesture.Action.Position)? = null
        var clicks = 0
        val microphone: MicrophoneButtonPositionController

        init {
            root.addView(stream, FrameLayout.LayoutParams(400, 300))
            root.addView(button, FrameLayout.LayoutParams(100, 100).apply { leftMargin = 250; topMargin = 50 })
            button.setOnClickListener { clicks++ }
            microphone = MicrophoneButtonPositionController.attach(context, button)!!
            stream.setOnTouchListener { _, event -> receive(event) }
            stream.setOnGenericMotionListener { _, event -> receive(event) }
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 300)
        }

        fun receive(event: MotionEvent): Boolean = dispatch.handleStream(event) {
            frames += MotionEvent.obtain(it)
            handleStreamInput(it)
        }

        fun send(event: MotionEvent, touch: Boolean = true, selected: Boolean = true) {
            try {
                if (touch && event.actionMasked == MotionEvent.ACTION_DOWN) dispatch.onTouchDown()
                val route: (MotionEvent) -> Boolean = {
                    if (touch) root.dispatchTouchEvent(it) else root.dispatchGenericMotionEvent(it) || receive(it)
                }
                if (selected) dispatch.dispatch(event, touch, mapPosition?.invoke(event), route) else route(event)
            } finally { event.recycle() }
        }

        fun close() { dispatch.reset(); microphone.dispose(); frames.forEach(MotionEvent::recycle) }
    }

    private fun withViews(test: (Fixture) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val fixture = Fixture()
            try { test(fixture) } finally { fixture.close() }
        }
    }

    @Test fun rewrittenTapClicksTheMicrophoneControlWithoutSendingHostInput() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, source = InputDevice.SOURCE_TOUCHSCREEN))
        f.send(event(MotionEvent.ACTION_UP, x = 280f))
        assertEquals(1, f.clicks)
        assertTrue(f.frames.isEmpty())
    }

    @Test fun localPhysicalDragAndGenericButtonPacketsNeverClickTheHost() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_BUTTON_PRESS, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY), touch = false)
        f.send(event(MotionEvent.ACTION_MOVE, x = 200f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_BUTTON_RELEASE, x = 200f), touch = false)
        // Finish with CANCEL so this test does not persist a microphone position.
        f.send(event(MotionEvent.ACTION_CANCEL, x = 200f))
        assertTrue(f.button.x < 250f)
        assertEquals(0, f.clicks)
        assertTrue(f.frames.isEmpty())
    }

    @Test fun controlsWithoutClickableFlagStillReceiveTheirTouchSequence() = withViews { f ->
        f.microphone.dispose()
        f.button.isClickable = false
        var releases = 0
        f.button.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) releases++
            true
        }
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, source = InputDevice.SOURCE_TOUCHSCREEN))
        f.send(event(MotionEvent.ACTION_UP, x = 280f))
        assertEquals(1, releases)
        assertTrue(f.frames.isEmpty())
    }

    @Test fun streamGestureKeepsAllContactsEvenWhenOtherFingersCrossLocalControls() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN))
        f.send(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 2))
        f.send(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 3))
        f.send(event(MotionEvent.ACTION_CANCEL, count = 3))
        assertEquals(listOf(1, 2, 3, 3), f.frames.map { it.pointerCount })
        assertEquals(0, f.clicks)
        assertTrue(f.root.isMotionEventSplittingEnabled)
    }

    @Test fun localGestureDoesNotSendAnAddedFingerToTheStream() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, source = InputDevice.SOURCE_TOUCHSCREEN))
        val second = event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x = 280f, count = 2)
        second.offsetLocation(-100f, 0f)
        f.send(second)
        f.send(event(MotionEvent.ACTION_CANCEL, x = 180f, count = 2))
        assertTrue(f.frames.isEmpty())
    }

    @Test fun scaledStreamReceivesOriginalCoordinatesAndRelativeAxesExactlyOnce() = withViews { f ->
        f.stream.pivotX = 0f; f.stream.pivotY = 0f
        f.stream.translationX = 40f; f.stream.translationY = 20f
        f.stream.scaleX = 2f; f.stream.scaleY = 2f
        val motion = event(MotionEvent.ACTION_HOVER_MOVE, x = 200f)
        motion.addBatch(1120, arrayOf(MotionEvent.PointerCoords().apply {
            x = 220f; y = 100f
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, 20f)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, 8f)
        }), 0)
        f.send(motion, touch = false)
        assertEquals(1, f.frames.size)
        assertEquals(CompatibilityTouchpadGesture.Action.Position(220f, 100f, 20f, 8f),
            CompatibilityTouchpadHandler.pointerPosition(f.frames.single()))
    }

    @Test fun parentGeneratedCancellationReleasesTheStreamGesture() = withViews { f ->
        val motion = event(MotionEvent.ACTION_MOVE)
        val cancel = event(MotionEvent.ACTION_CANCEL)
        try {
            f.dispatch.dispatch(motion, touch = true, position = null) { f.receive(cancel) }
            assertEquals(MotionEvent.ACTION_CANCEL, f.frames.single().actionMasked)
        } finally { motion.recycle(); cancel.recycle() }
    }

    @Test fun newDownAfterAnUnfinishedStreamTouchSurvivesViewGroupCancellation() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN))
        f.send(event(MotionEvent.ACTION_DOWN, x = 40f))
        f.send(event(MotionEvent.ACTION_UP, x = 40f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), f.frames.map { it.actionMasked })
        assertEquals(40f, f.frames[2].rawX)
    }

    @Test fun newLocalPressStillOwnsItsButtonsAfterCancellingTheOldStreamTouch() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN))
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_BUTTON_PRESS, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY), touch = false)
        f.send(event(MotionEvent.ACTION_BUTTON_RELEASE, x = 280f), touch = false)
        f.send(event(MotionEvent.ACTION_UP, x = 280f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.frames.map { it.actionMasked })
        assertEquals(1, f.clicks)
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun cancellingForALocalPhysicalPressDoesNotFallThroughToMouseButtons() = withViews { f ->
        val actions = mutableListOf<CompatibilityTouchpadGesture.Action>()
        val handler = CompatibilityTouchpadHandler(f.context, { action, _ -> actions += action }, { true })
        f.handleStreamInput = {
            assertTrue("Gesture cancellation must not forward the new local button state", handler.handle(it))
            true
        }
        try {
            f.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN))
            f.send(event(MotionEvent.ACTION_DOWN, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY))
            f.send(event(MotionEvent.ACTION_UP, x = 280f))
            assertEquals(listOf(CompatibilityTouchpadGesture.Action.Button(true),
                CompatibilityTouchpadGesture.Action.Button(false)),
                actions.filterIsInstance<CompatibilityTouchpadGesture.Action.Button>())
        } finally { handler.destroy() }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun anotherSelectedDeviceCancelsTheOldStreamPressBeforeClickingLocally() = crossDeviceLocalClick(selected = true)

    @Test @SdkSuppress(minSdkVersion = 31)
    fun anUnselectedTouchscreenCancelsTheOldStreamPressBeforeClickingLocally() = crossDeviceLocalClick(selected = false)

    private fun crossDeviceLocalClick(selected: Boolean) = withViews { f ->
        val actions = mutableListOf<CompatibilityTouchpadGesture.Action>()
        val handler = CompatibilityTouchpadHandler(f.context, { action, _ -> actions += action }, { true })
        f.handleStreamInput = { handler.handle(it); true }
        try {
            f.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN))
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val otherDevice = event(action, x = 280f, device = 43, source = InputDevice.SOURCE_TOUCHSCREEN)
                f.send(otherDevice, selected = selected)
            }
            assertEquals(1, f.clicks)
            assertEquals(listOf(CompatibilityTouchpadGesture.Action.Button(true),
                CompatibilityTouchpadGesture.Action.Button(false)),
                actions.filterIsInstance<CompatibilityTouchpadGesture.Action.Button>())
        } finally { handler.destroy() }
    }

    @Test fun unselectedLocalDownReleasesTheOldLocalDevicesSideButtons() = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, device = 43,
            source = InputDevice.SOURCE_TOUCHSCREEN), selected = false)
        f.send(event(MotionEvent.ACTION_UP, x = 280f, device = 43,
            source = InputDevice.SOURCE_TOUCHSCREEN), selected = false)
        assertTrue(f.frames.isEmpty())
        f.send(event(MotionEvent.ACTION_BUTTON_PRESS, buttons = MotionEvent.BUTTON_BACK), touch = false)
        f.send(event(MotionEvent.ACTION_BUTTON_RELEASE), touch = false)
        assertEquals(listOf(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE),
            f.frames.map { it.actionMasked })
    }

    @Test fun sideButtonsReachTheStreamAfterALocalClickEnds() = sideButtonsAfterLocalTouch(MotionEvent.ACTION_UP)

    @Test fun sideButtonsReachTheStreamAfterALocalTouchIsCancelled() = sideButtonsAfterLocalTouch(MotionEvent.ACTION_CANCEL)

    private fun sideButtonsAfterLocalTouch(endAction: Int) = withViews { f ->
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_BUTTON_PRESS, x = 280f, buttons = MotionEvent.BUTTON_PRIMARY), touch = false)
        f.send(event(MotionEvent.ACTION_BUTTON_RELEASE, x = 280f), touch = false)
        f.send(event(endAction, x = 280f))
        assertTrue(f.frames.isEmpty())
        f.send(event(MotionEvent.ACTION_HOVER_MOVE), touch = false)
        f.send(event(MotionEvent.ACTION_BUTTON_PRESS, buttons = MotionEvent.BUTTON_BACK), touch = false)
        f.send(event(MotionEvent.ACTION_BUTTON_RELEASE), touch = false)
        assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE), f.frames.map { it.actionMasked })
    }

    @Test fun hoveringOntoALocalControlDoesNotDuplicateTheFinalMove() = withViews { f ->
        f.send(event(MotionEvent.ACTION_HOVER_MOVE), touch = false)
        assertEquals(1, f.frames.size)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 280f), touch = false)
        // ViewGroup deliberately delivers one final MOVE before leaving the old
        // target. The synthesized EXIT must not replay that movement again.
        assertEquals(2, f.frames.size)
        assertTrue(f.button.isHovered)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 290f), touch = false)
        assertEquals(2, f.frames.size)
    }

    private fun Fixture.useSpeed(speed: Float) {
        val sensitivity = TouchpadSensitivity(pointerSpeed = speed, scrollSpeed = 1f)
        mapPosition = { sensitivity.position(CompatibilityTouchpadHandler.pointerPosition(it), 400, 300) }
    }

    @Test fun fasterLogicalCursorClicksTheControlInsteadOfTheSystemCursorTarget() = withViews { f ->
        f.useSpeed(2f)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 100f), touch = false)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 190f, dx = 90f), touch = false)
        assertTrue(f.button.isHovered)
        val previousFrames = f.frames.size
        f.send(event(MotionEvent.ACTION_DOWN, x = 190f))
        f.send(event(MotionEvent.ACTION_UP, x = 190f))
        assertEquals(1, f.clicks)
        assertEquals(previousFrames, f.frames.size)
    }

    @Test fun slowerLogicalCursorClicksTheControlEvenWhenSystemCursorHasPassedIt() = withViews { f ->
        f.useSpeed(0.5f)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 200f), touch = false)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 360f, dx = 160f), touch = false)
        f.send(event(MotionEvent.ACTION_DOWN, x = 360f))
        f.send(event(MotionEvent.ACTION_UP, x = 360f))
        assertEquals(1, f.clicks)
        assertTrue(f.frames.none { it.actionMasked == MotionEvent.ACTION_DOWN })
    }

    @Test fun systemCursorOverAControlDoesNotStealALogicalStreamClick() = withViews { f ->
        f.useSpeed(0.5f)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 100f), touch = false)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 280f, dx = 180f), touch = false)
        f.send(event(MotionEvent.ACTION_DOWN, x = 280f))
        f.send(event(MotionEvent.ACTION_UP, x = 280f))
        assertEquals(0, f.clicks)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
            f.frames.takeLast(2).map { it.actionMasked })
        assertEquals(280f, f.frames.last().rawX)
    }

    @Test fun localDraggingUsesScaledRawCoordinatesForTheWholeSequence() = withViews { f ->
        f.useSpeed(2f)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 100f), touch = false)
        f.send(event(MotionEvent.ACTION_HOVER_MOVE, x = 190f, dx = 90f), touch = false)
        f.send(event(MotionEvent.ACTION_DOWN, x = 190f, buttons = MotionEvent.BUTTON_PRIMARY))
        f.send(event(MotionEvent.ACTION_MOVE, x = 170f, dx = -20f, buttons = MotionEvent.BUTTON_PRIMARY))
        assertEquals(210f, f.button.x)
        f.send(event(MotionEvent.ACTION_CANCEL, x = 170f))
        assertEquals(0, f.clicks)
        assertTrue(f.frames.none { it.actionMasked == MotionEvent.ACTION_DOWN || it.actionMasked == MotionEvent.ACTION_MOVE })
    }

    @Test fun logicalCoordinatesPreserveTheWindowOffsetAndOriginalStreamFrame() = withViews { f ->
        f.microphone.dispose()
        val raw = mutableListOf<Float>()
        f.button.setOnTouchListener { _, event -> raw += event.rawX; true }
        f.mapPosition = { CompatibilityTouchpadGesture.Action.Position(320f, 100f) }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            f.send(event(action, x = 180f).apply { offsetLocation(-40f, -20f) })
        }
        assertEquals(listOf(320f, 320f), raw)
        assertTrue(f.frames.isEmpty())
    }
}
