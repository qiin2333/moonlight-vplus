package com.limelight.binding.input.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDs5TapClickDetectorTest {
    private val detector = ScreenDs5TapClickDetector(movementThresholdPx = 24f)

    @Test
    fun quickStillTapClicks() {
        detector.onDown(0, 100f, 200f, 1_000L)
        detector.onMove(0, 108f, 212f)
        assertTrue(detector.onUp(0, 110f, 215f, 1_200L))
    }

    @Test
    fun slowLiftDoesNotClick() {
        detector.onDown(0, 100f, 200f, 1_000L)
        assertFalse(detector.onUp(0, 100f, 200f, 1_300L))
    }

    @Test
    fun driftingFingerDoesNotClick() {
        detector.onDown(0, 100f, 200f, 1_000L)
        detector.onMove(0, 140f, 240f)
        assertFalse(detector.onUp(0, 150f, 250f, 1_100L))
    }

    @Test
    fun secondFingerDisqualifiesTap() {
        detector.onDown(0, 100f, 200f, 1_000L)
        detector.onPointerDown()
        assertFalse(detector.onUp(0, 100f, 200f, 1_100L))
    }

    @Test
    fun eligibilityNeverReturnsAfterMovement() {
        detector.onDown(0, 100f, 200f, 1_000L)
        detector.onMove(0, 140f, 200f)
        detector.onMove(0, 100f, 200f)
        assertFalse(detector.onUp(0, 100f, 200f, 1_100L))
    }

    @Test
    fun cancelResetsGesture() {
        detector.onDown(0, 100f, 200f, 1_000L)
        detector.cancel()
        assertFalse(detector.onUp(0, 100f, 200f, 1_100L))
    }

    @Test
    fun upConsumesGestureForNextFinger() {
        detector.onDown(0, 100f, 200f, 1_000L)
        assertTrue(detector.onUp(0, 100f, 200f, 1_100L))
        // A stale second-lift event for the consumed gesture must not click.
        assertFalse(detector.onUp(0, 100f, 200f, 1_150L))
    }
}
