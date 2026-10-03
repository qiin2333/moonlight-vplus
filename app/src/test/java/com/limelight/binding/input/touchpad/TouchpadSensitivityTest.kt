package com.limelight.binding.input.touchpad

import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadSensitivityTest {
    @Test fun defaultSpeedPreservesAbsoluteCoordinates() {
        val sensitivity = TouchpadSensitivity()
        sensitivity.position(Action.Position(40f, 60f), 300, 200)
        assertEquals(Action.Position(70f, 90f), sensitivity.position(Action.Position(70f, 90f, 10f, 5f), 300, 200))
    }

    @Test fun hoverTapAndDragKeepOneScaledCursor() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 2f)
        sensitivity.position(Action.Position(40f, 60f), 300, 200)
        val hover = sensitivity.position(Action.Position(50f, 65f, 10f, 5f), 300, 200)
        assertEquals(Action.Position(60f, 70f), hover)
        // A tap's absolute Android coordinate must not snap the host cursor back.
        assertEquals(hover, sensitivity.position(Action.Position(50f, 65f), 300, 200))
        assertEquals(Action.Position(80f, 80f), sensitivity.position(Action.Position(60f, 70f, 10f, 5f), 300, 200))
        assertEquals(Action.Position(80f, 80f), sensitivity.position(Action.Position(60f, 70f), 300, 200))
    }

    @Test fun relativeAxesContinueMovingAfterAndroidCursorReachesTheEdge() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 0.5f)
        sensitivity.position(Action.Position(90f, 30f), 100, 100)
        assertEquals(Action.Position(95f, 30f), sensitivity.position(Action.Position(100f, 30f, 10f, 0f), 100, 100))
        assertEquals(Action.Position(100f, 30f), sensitivity.position(Action.Position(100f, 30f, 10f, 0f), 100, 100))
        sensitivity.position(Action.Position(100f, 30f, 50f, 0f), 100, 100)
        assertEquals(Action.Position(95f, 30f), sensitivity.position(Action.Position(90f, 30f, -10f, 0f), 100, 100))
    }

    @Test fun devicesWithoutRelativeAxesRetainFullScreenReachAtLowSpeed() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 0.25f)
        sensitivity.position(Action.Position(0f, 0f), 300, 200)
        assertEquals(Action.Position(300f, 200f), sensitivity.position(Action.Position(300f, 200f), 300, 200))
        assertEquals(Action.Position(0f, 0f), sensitivity.position(Action.Position(0f, 0f), 300, 200))
    }

    @Test fun fractionalRelativeMovementAccumulatesWithoutRepositioningOnLift() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 0.25f)
        sensitivity.position(Action.Position(40f, 60f), 300, 200)
        repeat(4) { sensitivity.position(Action.Position(41f + it, 60f, 1f, 0f), 300, 200) }
        assertEquals(Action.Position(41f, 60f), sensitivity.position(Action.Position(44f, 60f), 300, 200))
    }

    @Test fun zoomAndTranslationApplyOnceToHoverTapAndRelativeAxes() {
        val hover = TouchpadSensitivity.toStream(Action.Position(340f, 100f, 20f, 8f), 40f, 20f, 2f, 2f)
        val tap = TouchpadSensitivity.toStream(Action.Position(340f, 100f), 40f, 20f, 2f, 2f)
        assertEquals(Action.Position(150f, 40f, 10f, 4f), hover)
        assertEquals(Action.Position(150f, 40f), tap)
        val sensitivity = TouchpadSensitivity()
        assertEquals(sensitivity.position(hover, 500, 300), sensitivity.position(tap, 500, 300))
    }

    @Test fun externalDisplayMapsTheInputDisplayIntoTheStream() {
        assertEquals(Action.Position(1000f, 500f, 20f, 10f),
            TouchpadSensitivity.toStream(Action.Position(500f, 250f, 10f, 5f), 0f, 0f, 0.5f, 0.5f))
    }

    @Test fun changingPointerDevicesDoesNotReuseRelativeAxisCapability() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 0.5f)
        sensitivity.position(Action.Position(0f, 0f), 100, 100)
        sensitivity.position(Action.Position(10f, 0f, 10f, 0f), 100, 100)
        sensitivity.resetPointer()
        sensitivity.position(Action.Position(0f, 0f), 100, 100)
        assertEquals(Action.Position(100f, 100f), sensitivity.position(Action.Position(100f, 100f), 100, 100))
    }

    @Test fun scrollSpeedIsIndependentAndRetainsFractionsInBothDirections() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 2f, scrollSpeed = 0.25f)
        repeat(3) { assertEquals(Action.Scroll(0, 0), sensitivity.scroll(1f, -1f)) }
        assertEquals(Action.Scroll(1, -1), sensitivity.scroll(1f, -1f))
        assertEquals(Action.Move(20, -10), sensitivity.move(10f, -5f))
        assertEquals(Action.Scroll(30, -60), sensitivity.scroll(120f, -240f))
    }

    @Test fun focusLossResetsTheCursorAndFractionalScroll() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 2f, scrollSpeed = 0.25f)
        sensitivity.position(Action.Position(40f, 60f), 300, 200)
        sensitivity.scroll(3f, 3f)
        sensitivity.reset()
        assertEquals(Action.Position(10f, 20f), sensitivity.position(Action.Position(10f, 20f), 300, 200))
        assertEquals(Action.Scroll(0, 0), sensitivity.scroll(1f, 1f))
    }

    @Test fun anotherMouseResetsOnlyThePointerAnchor() {
        val sensitivity = TouchpadSensitivity(pointerSpeed = 2f, scrollSpeed = 0.25f)
        sensitivity.position(Action.Position(40f, 60f), 300, 200)
        sensitivity.scroll(3f, 3f)
        sensitivity.resetPointer()
        assertEquals(Action.Position(10f, 20f), sensitivity.position(Action.Position(10f, 20f), 300, 200))
        assertEquals(Action.Scroll(1, 1), sensitivity.scroll(1f, 1f))
    }
}
