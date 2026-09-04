package com.limelight.binding.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGyroLivenessTrackerTest {
    @Test
    fun requestsFallbackAfterContinuousZeroDuration() {
        val tracker = ControllerGyroLivenessTracker(zeroDurationNanos = 10)

        assertFalse(tracker.onSample(0f, 0f, 0f, 1))
        assertFalse(tracker.onSample(0f, 0f, 0f, 10))
        assertTrue(tracker.onSample(0f, 0f, 0f, 11))
        assertFalse(tracker.onSample(0f, 0f, 0f, 12))
    }

    @Test
    fun movementResetsTheZeroSampleRun() {
        val tracker = ControllerGyroLivenessTracker(zeroDurationNanos = 10)

        assertFalse(tracker.onSample(0f, 0f, 0f, 1))
        assertFalse(tracker.onSample(0.1f, 0f, 0f, 5))
        assertFalse(tracker.onSample(0f, 0f, 0f, 6))
        assertFalse(tracker.onSample(0f, 0f, 0f, 15))
        assertTrue(tracker.onSample(0f, 0f, 0f, 16))
    }

    @Test
    fun tinyNonZeroSensorNoiseKeepsTheSourceAlive() {
        val tracker = ControllerGyroLivenessTracker(zeroDurationNanos = 2)

        assertFalse(tracker.onSample(0f, 0f, 0f, 1))
        assertFalse(tracker.onSample(0.000001f, 0f, 0f, 2))
        assertFalse(tracker.onSample(0f, 0f, 0f, 3))
    }

    @Test
    fun nonMonotonicTimestampRestartsTheZeroWindow() {
        val tracker = ControllerGyroLivenessTracker(zeroDurationNanos = 10)

        assertFalse(tracker.onSample(0f, 0f, 0f, 20))
        assertFalse(tracker.onSample(0f, 0f, 0f, 15))
        assertFalse(tracker.onSample(0f, 0f, 0f, 24))
        assertTrue(tracker.onSample(0f, 0f, 0f, 25))
    }

    @Test
    fun resetAllowsASecondFallbackDecision() {
        val tracker = ControllerGyroLivenessTracker(zeroDurationNanos = 1)

        assertFalse(tracker.onSample(0f, 0f, 0f, 1))
        assertTrue(tracker.onSample(0f, 0f, 0f, 2))
        tracker.reset()
        assertFalse(tracker.onSample(0f, 0f, 0f, 3))
        assertTrue(tracker.onSample(0f, 0f, 0f, 4))
    }
}
