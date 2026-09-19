package com.limelight.binding.input.touchpad

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatibilityTouchpadHandlerTest {
    @SdkSuppress(minSdkVersion = 34)
    private fun classified(action: Int, classification: Int, count: Int = 1,
                           x: Float = 100f, spacing: Float = 100f): MotionEvent = MotionEvent.obtain(
        1000, 1100, action, count,
        Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } },
        Array(count) { MotionEvent.PointerCoords().apply { this.x = x + it * spacing; y = 100f; pressure = 1f } },
        0, 0, 1f, 1f, 42, 0, InputDevice.SOURCE_MOUSE, 0, 0, classification)!!

    @Test @SdkSuppress(minSdkVersion = 34)
    fun classifiedScrollWithoutNoFocusFlagDoesNotBecomeALeftDrag() = withHandler { handler, actions ->
        val classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
        handler.send(classified(MotionEvent.ACTION_DOWN, classification))
        handler.send(classified(MotionEvent.ACTION_MOVE, classification, x = 200f))
        handler.send(classified(MotionEvent.ACTION_UP, classification, x = 200f))
        assertTrue(actions.none { it is Action.Button || it is Action.Click || it is Action.Position })
        assertEquals(listOf(Action.Scroll(100, 0)), actions)
    }

    @Test @SdkSuppress(minSdkVersion = 34)
    fun classifiedPinchDoesNotClickBeforeTheSecondPointerArrives() = withHandler { handler, actions ->
        val classification = MotionEvent.CLASSIFICATION_PINCH
        handler.send(classified(MotionEvent.ACTION_DOWN, classification))
        handler.send(classified(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), classification, count = 2))
        handler.send(classified(MotionEvent.ACTION_MOVE, classification, count = 2, spacing = 200f))
        handler.send(classified(MotionEvent.ACTION_POINTER_UP or (1 shl 8), classification, count = 2, spacing = 200f))
        handler.send(classified(MotionEvent.ACTION_UP, classification))
        assertTrue(actions.none { it is Action.Button || it is Action.Click || it is Action.Position })
        assertTrue(actions.any { it is Action.Zoom })
    }

    @Test @SdkSuppress(minSdkVersion = 34)
    fun classifiedGestureEndingBelowSlopDoesNotBecomeATap() = withHandler { handler, actions ->
        for (classification in listOf(MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE, MotionEvent.CLASSIFICATION_PINCH)) {
            handler.send(classified(MotionEvent.ACTION_DOWN, classification))
            handler.send(classified(MotionEvent.ACTION_UP, classification, x = 101f))
        }
        assertTrue(actions.isEmpty())
    }

    private fun event(action: Int, time: Long = 1000, x: Float = 0f, count: Int = 1,
                      buttons: Int = 0, flags: Int = 0x40, device: Int = 42,
                      source: Int = InputDevice.SOURCE_MOUSE, y: Float = 0f): MotionEvent = MotionEvent.obtain(
        1000, time, action, count,
        Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } },
        Array(count) { MotionEvent.PointerCoords().apply { this.x = x + it * 100; this.y = y; pressure = 1f } },
        0, buttons, 1f, 1f, device, 0, source, flags,
    )

    private fun CompatibilityTouchpadHandler.send(event: MotionEvent): Boolean =
        try { handle(event) } finally { event.recycle() }

    private fun withHandler(selected: Boolean = true, test: (CompatibilityTouchpadHandler, MutableList<Action>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val actions = mutableListOf<Action>()
            val handler = CompatibilityTouchpadHandler(instrumentation.targetContext,
                { action, _ -> actions += action }, { selected })
            try { test(handler, actions) } finally { handler.destroy() }
        }
    }

    @Test fun systemRightClickFallsThroughIncludingFinalRelease() = withHandler { handler, actions ->
        assertFalse(handler.send(event(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_SECONDARY)))
        assertFalse(handler.send(event(MotionEvent.ACTION_BUTTON_PRESS, buttons = MotionEvent.BUTTON_SECONDARY)))
        assertFalse(handler.send(event(MotionEvent.ACTION_BUTTON_RELEASE, time = 1050)))
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, time = 1050)))
        assertTrue(actions.isEmpty())
    }

    @Test fun rawPhysicalButtonDragMovesWithoutAbsolutePositionOrSyntheticClicks() = withHandler { handler, actions ->
        val raw = InputDevice.SOURCE_TOUCHPAD
        assertTrue(handler.send(event(MotionEvent.ACTION_DOWN, x = 500f, source = raw)))
        assertFalse(handler.send(event(MotionEvent.ACTION_BUTTON_PRESS, x = 500f,
            buttons = MotionEvent.BUTTON_PRIMARY, source = raw)))
        assertFalse(handler.send(event(MotionEvent.ACTION_MOVE, x = 530f,
            buttons = MotionEvent.BUTTON_PRIMARY, source = raw)))
        assertFalse(handler.send(event(MotionEvent.ACTION_BUTTON_RELEASE, x = 530f, source = raw)))
        assertFalse(handler.send(event(MotionEvent.ACTION_MOVE, x = 540f, source = raw)))
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, x = 545f, source = raw)))
        assertEquals(listOf(Action.Move(30, 0), Action.Move(10, 0), Action.Move(5, 0)), actions)
    }

    @Test fun rawButtonDragRetainsFractionalAndBatchedMovement() = withHandler { handler, actions ->
        val raw = InputDevice.SOURCE_TOUCHPAD
        handler.send(event(MotionEvent.ACTION_DOWN, x = 100f, buttons = MotionEvent.BUTTON_PRIMARY, source = raw))
        val move = event(MotionEvent.ACTION_MOVE, time = 1020, x = 100.5f,
            buttons = MotionEvent.BUTTON_PRIMARY, source = raw)
        move.addBatch(1040, arrayOf(MotionEvent.PointerCoords().apply { x = 102.5f; pressure = 1f }), 0)
        handler.send(move)
        handler.send(event(MotionEvent.ACTION_UP, time = 1060, x = 104f, source = raw))
        assertEquals(4, actions.filterIsInstance<Action.Move>().sumOf { it.x.toInt() })
        assertTrue(actions.all { it is Action.Move })
    }

    @Test fun rawButtonCancellationDoesNotMoveOrReuseThePreviousContact() = withHandler { handler, actions ->
        val raw = InputDevice.SOURCE_TOUCHPAD
        handler.send(event(MotionEvent.ACTION_DOWN, x = 100f, buttons = MotionEvent.BUTTON_PRIMARY, source = raw))
        handler.send(event(MotionEvent.ACTION_CANCEL, x = 500f, source = raw))
        handler.onInputDeviceRemoved(42)
        handler.send(event(MotionEvent.ACTION_DOWN, x = 900f, buttons = MotionEvent.BUTTON_PRIMARY, source = raw))
        handler.send(event(MotionEvent.ACTION_MOVE, x = 910f, buttons = MotionEvent.BUTTON_PRIMARY, source = raw))
        handler.send(event(MotionEvent.ACTION_UP, x = 910f, source = raw))
        assertEquals(listOf(Action.Move(10, 0)), actions)
    }

    private fun rawButtonEvent(action: Int, vararg contacts: Pair<Int, Float>): MotionEvent = MotionEvent.obtain(
        1000, 1100, action, contacts.size,
        Array(contacts.size) { MotionEvent.PointerProperties().apply {
            id = contacts[it].first; toolType = MotionEvent.TOOL_TYPE_FINGER
        } },
        Array(contacts.size) { MotionEvent.PointerCoords().apply { x = contacts[it].second; pressure = 1f } },
        0, if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_PRIMARY,
        1f, 1f, 42, 0, InputDevice.SOURCE_TOUCHPAD, 0,
    )

    @Test fun rawButtonDragTransfersToTheRemainingContactWithoutJumping() = withHandler { handler, actions ->
        handler.send(rawButtonEvent(MotionEvent.ACTION_DOWN, 0 to 100f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_MOVE, 0 to 110f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            0 to 110f, 1 to 200f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_POINTER_UP, 0 to 110f, 1 to 200f))
        assertEquals(listOf(Action.Move(10, 0)), actions)
        handler.send(rawButtonEvent(MotionEvent.ACTION_MOVE, 1 to 230f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_MOVE, 1 to 250f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_UP, 1 to 250f))
        assertEquals(listOf(Action.Move(10, 0), Action.Move(30, 0), Action.Move(20, 0)), actions)
    }

    @Test fun rawButtonDragTracksIdsWhenIndicesChangeAndAnotherFingerLifts() = withHandler { handler, actions ->
        handler.send(rawButtonEvent(MotionEvent.ACTION_DOWN, 2 to 100f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_POINTER_DOWN, 7 to 900f, 2 to 110f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_MOVE, 7 to 950f, 2 to 120f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_POINTER_UP, 7 to 950f, 2 to 125f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_MOVE, 2 to 145f))
        handler.send(rawButtonEvent(MotionEvent.ACTION_UP, 2 to 145f))
        assertEquals(listOf(Action.Move(20, 0), Action.Move(5, 0), Action.Move(20, 0)), actions)
    }

    @Test fun cancelledContactsNeverBecomeTaps() = withHandler { handler, actions ->
        assertTrue(handler.send(event(MotionEvent.ACTION_DOWN)))
        assertTrue(handler.send(event(MotionEvent.ACTION_UP, time = 1100, flags = MotionEvent.FLAG_CANCELED)))
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, time = 1101)))
        assertFalse(handler.send(event(MotionEvent.ACTION_DOWN, flags = MotionEvent.FLAG_CANCELED)))
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, time = 1150)))
        assertTrue(actions.isEmpty())
        handler.send(event(MotionEvent.ACTION_DOWN))
        handler.send(event(MotionEvent.ACTION_UP, time = 1301))
        assertEquals(listOf(Action.Click(1)), actions)
    }

    @Test fun batchedMotionRetainsTheWholeScrollDistance() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN))
        val move = event(MotionEvent.ACTION_MOVE, time = 1020, x = 100f)
        move.addBatch(1040, arrayOf(MotionEvent.PointerCoords().apply { x = 140f; y = 0f; pressure = 1f }), 0)
        assertTrue(handler.send(move))
        handler.send(event(MotionEvent.ACTION_UP, time = 1060, x = 140f))
        assertEquals(140, actions.filterIsInstance<Action.Scroll>().sumOf { it.x.toInt() })
        assertFalse(actions.any { it is Action.Click })
    }

    @Test fun rawTwoFingerTapDrainsTheRemainingPointer() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHPAD))
        handler.send(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            time = 1010, count = 2, source = InputDevice.SOURCE_TOUCHPAD))
        handler.send(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            time = 1100, count = 2, source = InputDevice.SOURCE_TOUCHPAD))
        handler.send(event(MotionEvent.ACTION_UP, time = 1110, source = InputDevice.SOURCE_TOUCHPAD))
        assertEquals(listOf(Action.Click(2)), actions)
    }

    @Test fun batchedScrollDoesNotMixAxesWhenContactsDriftSideways() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN))
        val move = event(MotionEvent.ACTION_MOVE, time = 1020, x = 1f, y = 40f)
        move.addBatch(1040, arrayOf(MotionEvent.PointerCoords().apply { x = 50f; y = 80f; pressure = 1f }), 0)
        assertTrue(handler.send(move))
        handler.send(event(MotionEvent.ACTION_UP, time = 1060, x = 50f, y = 80f))
        val scroll = actions.filterIsInstance<Action.Scroll>()
        assertEquals(80, scroll.sumOf { it.y.toInt() })
        assertTrue(scroll.all { it.x.toInt() == 0 })
        assertFalse(actions.any { it is Action.Click })
    }

    @Test fun rawSingleContactMovesInsteadOfScrolling() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHPAD))
        handler.send(event(MotionEvent.ACTION_MOVE, time = 1040, x = 100f, source = InputDevice.SOURCE_TOUCHPAD))
        handler.send(event(MotionEvent.ACTION_UP, time = 1100, x = 100f, source = InputDevice.SOURCE_TOUCHPAD))
        assertEquals(listOf(Action.Move(100, 0)), actions)
    }

    @Test fun switchingFromInsetStreamViewToActivityCoordinatesPreservesTaps() = withHandler { handler, actions ->
        val down = event(MotionEvent.ACTION_DOWN, x = 200f)
        down.offsetLocation(-200f, -100f)
        handler.send(down)
        handler.send(event(MotionEvent.ACTION_UP, time = 1301, x = 200f))
        assertEquals(listOf(Action.Click(1)), actions)
    }

    @Test fun switchingCoordinateSpacesDoesNotAddTheStreamInsetToScroll() = withHandler { handler, actions ->
        val down = event(MotionEvent.ACTION_DOWN, x = 200f)
        down.offsetLocation(-200f, -100f)
        handler.send(down)
        handler.send(event(MotionEvent.ACTION_MOVE, time = 1100, x = 300f))
        handler.send(event(MotionEvent.ACTION_UP, time = 1200, x = 300f))
        assertEquals(listOf(Action.Scroll(100, 0)), actions)
    }

    @Test fun unpluggingCancelsOwnershipButNewDeviceIdsWork() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN))
        handler.onInputDeviceRemoved(42)
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, time = 1100)))
        assertTrue(actions.isEmpty())
        handler.send(event(MotionEvent.ACTION_DOWN, device = 43))
        handler.send(event(MotionEvent.ACTION_UP, time = 1301, device = 43))
        assertEquals(listOf(Action.Click(1)), actions)
    }

    @Test fun anotherMouseDoesNotCancelTheOwnedTouch() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN))
        assertFalse(handler.send(event(MotionEvent.ACTION_BUTTON_PRESS, device = 43, buttons = MotionEvent.BUTTON_PRIMARY)))
        handler.send(event(MotionEvent.ACTION_UP, time = 1301))
        assertEquals(listOf(Action.Click(1)), actions)
    }

    @Test fun unselectedTouchscreenEventsAreNotConsumed() = withHandler(selected = false) { handler, actions ->
        assertFalse(handler.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertFalse(handler.send(event(MotionEvent.ACTION_UP, time = 1100, source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertTrue(actions.isEmpty())
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun synthesizedTapPressesImmediatelyAndReleasesAtTheSystemUp() = withHandler { handler, actions ->
        assertTrue(handler.send(event(MotionEvent.ACTION_DOWN, flags = 0)))
        assertEquals(listOf(Action.Position(0f, 0f), Action.Button(true)), actions)
        assertTrue(handler.send(event(MotionEvent.ACTION_UP, time = 1301,
            source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertEquals(Action.Button(false), actions.last())
        assertFalse(actions.any { it is Action.Click })
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun synthesizedDoubleTapKeepsBothButtonPairs() = withHandler { handler, actions ->
        for (time in listOf(1000L, 1120L)) {
            handler.send(event(MotionEvent.ACTION_DOWN, time = time, flags = 0,
                source = InputDevice.SOURCE_TOUCHSCREEN))
            handler.send(event(MotionEvent.ACTION_UP, time = time + 100,
                source = InputDevice.SOURCE_TOUCHSCREEN))
        }
        assertEquals(listOf(true, false, true, false), actions.filterIsInstance<Action.Button>().map { it.down })
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun tapDragMovesThePressedPointerWithoutScrolling() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN, flags = 0))
        handler.send(event(MotionEvent.ACTION_MOVE, time = 1100, x = 100f, flags = 0))
        handler.send(event(MotionEvent.ACTION_UP, time = 1301, x = 100f))
        assertEquals(listOf(Action.Position(0f, 0f), Action.Button(true),
            Action.Position(100f, 0f), Action.Position(100f, 0f), Action.Button(false)), actions)
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun cancelAndUnplugReleaseSynthesizedPressExactlyOnce() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN, flags = 0))
        handler.send(event(MotionEvent.ACTION_CANCEL, time = 1100, flags = MotionEvent.FLAG_CANCELED))
        handler.onInputDeviceRemoved(42)
        handler.cancel()
        assertEquals(listOf(true, false), actions.filterIsInstance<Action.Button>().map { it.down })
    }

    @Test fun rewrittenFreeformDownRetainsBothPointers() = withHandler { handler, actions ->
        handler.send(event(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN))
        handler.send(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            time = 1010, count = 2))
        handler.send(event(MotionEvent.ACTION_MOVE, time = 1050, x = 50f, count = 2))
        handler.send(event(MotionEvent.ACTION_POINTER_UP, time = 1100, x = 50f, count = 2))
        handler.send(event(MotionEvent.ACTION_UP, time = 1110, x = 150f,
            source = InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(listOf(Action.Scroll(50, 0)), actions)
    }

    private fun startThreeFingerSwitch(handler: CompatibilityTouchpadHandler) {
        handler.send(event(MotionEvent.ACTION_DOWN))
        handler.send(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 2))
        handler.send(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 3))
        handler.send(event(MotionEvent.ACTION_MOVE, time = 1050, x = 200f, count = 3))
    }

    @Test fun firstLiftConfirmsSelectionWithoutClickingRemainingContacts() = withHandler { handler, actions ->
        startThreeFingerSwitch(handler)
        handler.send(event(MotionEvent.ACTION_POINTER_UP, time = 2000, x = 200f, count = 3))
        handler.send(event(MotionEvent.ACTION_POINTER_UP, time = 2010, x = 200f, count = 2))
        handler.send(event(MotionEvent.ACTION_UP, time = 2020, x = 200f,
            source = InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(listOf(Action.Swipe(CompatibilityTouchpadGesture.Direction.RIGHT),
            Action.EndSwitch(cancelled = false)), actions)
    }

    @Test fun cancelledSelectionReleasesBeforeTheNextContact() = withHandler { handler, actions ->
        startThreeFingerSwitch(handler)
        handler.send(event(MotionEvent.ACTION_CANCEL, time = 1100, count = 3, flags = MotionEvent.FLAG_CANCELED))
        handler.cancel()
        assertEquals(listOf(Action.Swipe(CompatibilityTouchpadGesture.Direction.RIGHT),
            Action.EndSwitch(cancelled = true)), actions)
    }

    @Test fun deviceRemovalEndsSelectionEvenWithoutAnUpEvent() = withHandler { handler, actions ->
        startThreeFingerSwitch(handler)
        handler.onInputDeviceRemoved(42)
        assertEquals(listOf(Action.Swipe(CompatibilityTouchpadGesture.Direction.RIGHT),
            Action.EndSwitch(cancelled = true)), actions)
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test fun pointerPositionIncludesBatchedRelativeMotionButNotTapAxes() {
        val move = event(MotionEvent.ACTION_HOVER_MOVE, x = 100f)
        move.addBatch(1040, arrayOf(MotionEvent.PointerCoords().apply {
            x = 103f; y = 5f
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, 3f)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, 5f)
        }), 0)
        move.addBatch(1060, arrayOf(MotionEvent.PointerCoords().apply {
            x = 107f; y = 11f
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, 4f)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, 6f)
        }), 0)
        try {
            assertEquals(Action.Position(107f, 11f, 7f, 11f), CompatibilityTouchpadHandler.pointerPosition(move))
            move.action = MotionEvent.ACTION_DOWN
            assertEquals(Action.Position(107f, 11f), CompatibilityTouchpadHandler.pointerPosition(move))
        } finally { move.recycle() }
    }
}
