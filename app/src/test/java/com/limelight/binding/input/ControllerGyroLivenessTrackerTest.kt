package com.limelight.binding.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGyroLivenessTrackerTest {
    @Test
    fun requestsFallbackAfterConsecutiveZeroSamples() {
        val tracker = ControllerGyroLivenessTracker(zeroSampleLimit = 3)

        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertTrue(tracker.onSample(0f, 0f, 0f))
        assertFalse(tracker.onSample(0f, 0f, 0f))
    }

    @Test
    fun movementResetsTheZeroSampleRun() {
        val tracker = ControllerGyroLivenessTracker(zeroSampleLimit = 3)

        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertFalse(tracker.onSample(0.1f, 0f, 0f))
        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertTrue(tracker.onSample(0f, 0f, 0f))
    }

    @Test
    fun tinyNonZeroSensorNoiseKeepsTheSourceAlive() {
        val tracker = ControllerGyroLivenessTracker(zeroSampleLimit = 2)

        assertFalse(tracker.onSample(0f, 0f, 0f))
        assertFalse(tracker.onSample(0.000001f, 0f, 0f))
        assertFalse(tracker.onSample(0f, 0f, 0f))
    }

    @Test
    fun resetAllowsASecondFallbackDecision() {
        val tracker = ControllerGyroLivenessTracker(zeroSampleLimit = 1)

        assertTrue(tracker.onSample(0f, 0f, 0f))
        tracker.reset()
        assertTrue(tracker.onSample(0f, 0f, 0f))
    }
}
