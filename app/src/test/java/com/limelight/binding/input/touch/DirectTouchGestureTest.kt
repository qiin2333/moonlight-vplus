package com.limelight.binding.input.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTouchGestureTest {
    private class Recorder : DirectTouchGesture.Output {
        val events = mutableListOf<String>()
        val transforms = mutableListOf<List<Float>>()
        override fun tap(x: Float, y: Float) { events += "tap:$x,$y" }
        override fun move(x: Float, y: Float) { events += "move:$x,$y" }
        override fun press(x: Float, y: Float, secondary: Boolean) { events += "press:$x,$y,$secondary" }
        override fun release() { events += "release" }
        override fun scroll(dy: Float) { events += "scroll:$dy" }
        override fun transform(fromX: Float, fromY: Float, toX: Float, toY: Float, ratio: Float) {
            transforms += listOf(fromX, fromY, toX, toY, ratio)
        }
        override fun keyboard() { events += "keyboard" }
    }

    private val output = Recorder()
    private val gesture = DirectTouchGesture(8f, output)

    @Test fun pinchIsLocalAndRemainingFingerCanNotClick() {
        gesture.down(40f, 50f)
        gesture.secondFinger(40f, 50f, 60f, 50f, zoomed = false)
        gesture.movePair(30f, 50f, 70f, 50f)
        gesture.pointerUp(canceled = false)
        gesture.moveSingle(100f, 90f)
        gesture.up(100, canceled = false)
        assertEquals(listOf(listOf(50f, 50f, 50f, 50f, 2f)), output.transforms)
        assertTrue(output.events.isEmpty())
        assertFalse(gesture.ownsSequence)
        gesture.down(20f, 30f)
        gesture.moveSingle(22f, 31f)
        gesture.up(130, canceled = false)
        assertEquals(listOf("tap:20.0,30.0"), output.events)
    }

    @Test fun sameDirectionAtBaseScrollsAndDoesNotSwitchToPinchMidSequence() {
        gesture.down(10f, 10f)
        gesture.secondFinger(10f, 10f, 40f, 10f, zoomed = false)
        gesture.movePair(10f, 13f, 40f, 14f)
        assertTrue(output.events.isEmpty())
        gesture.movePair(10f, 25f, 40f, 25f)
        gesture.movePair(0f, 29f, 50f, 29f)
        assertEquals(listOf("scroll:15.0", "scroll:4.0"), output.events)
        assertTrue(output.transforms.isEmpty())
    }

    @Test fun sameDirectionInFreshZoomedGesturePansNotScrolls() {
        gesture.down(10f, 10f)
        gesture.secondFinger(10f, 10f, 40f, 10f, zoomed = true)
        gesture.movePair(22f, 29f, 52f, 29f)
        assertEquals(listOf(listOf(25f, 10f, 37f, 29f, 1f)), output.transforms)
        assertTrue(output.events.isEmpty())
    }

    @Test fun pressReleasesExactlyOnceWhenPinchTakesOver() {
        gesture.down(10f, 10f)
        gesture.moveSingle(24f, 10f)
        gesture.secondFinger(24f, 10f, 50f, 10f, zoomed = false)
        gesture.movePair(12f, 10f, 62f, 10f)
        gesture.cancel(suppressRemaining = true)
        gesture.up(120, canceled = false)
        assertEquals(listOf("press:10.0,10.0,false", "move:24.0,10.0", "release"), output.events)
        assertEquals(1, output.transforms.size)
    }

    @Test fun longPressAndLifecycleCancellationDoNotLeakOrLeaveAButtonDown() {
        gesture.down(10f, 10f)
        gesture.longPress()
        gesture.cancel(suppressRemaining = true)
        assertTrue(gesture.ownsSequence)
        gesture.moveSingle(0f, 0f)
        gesture.up(700, canceled = false)
        assertEquals(listOf("press:10.0,10.0,true", "release"), output.events)
        assertFalse(gesture.ownsSequence)
        gesture.down(21f, 21f)
        gesture.up(720, canceled = true)
        assertEquals(2, output.events.size)
    }

    @Test fun thirdFingerDoesNotChangeAnActiveTransformOrRevealKeyboard() {
        gesture.down(10f, 10f)
        gesture.secondFinger(10f, 10f, 40f, 10f, zoomed = false)
        gesture.movePair(0f, 10f, 50f, 10f)
        gesture.extraFinger(3, 40)
        gesture.pointerUp(canceled = false)
        gesture.movePair(5f, 10f, 60f, 10f)
        gesture.pointerUp(canceled = false)
        gesture.up(90, canceled = false)
        assertEquals(1, output.transforms.size)
        assertTrue(output.events.isEmpty())
    }

    @Test fun threeFingerTapStillOpensKeyboard() {
        gesture.down(10f, 10f)
        gesture.secondFinger(10f, 10f, 40f, 10f, zoomed = false)
        gesture.extraFinger(3, 40)
        gesture.pointerUp(canceled = false)
        gesture.pointerUp(canceled = false)
        gesture.up(90, canceled = false)
        assertEquals(listOf("keyboard"), output.events)
    }
}
