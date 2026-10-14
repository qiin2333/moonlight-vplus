package com.limelight.binding.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerArrivalTrackerTest {
    @Test fun withdrawnSlotMustReportANewArrival() {
        val tracker = ControllerArrivalTracker(true)
        tracker.reset()
        assertFalse(tracker.isReported)
        assertFalse(tracker.recordAttempt(-2))
        assertTrue(tracker.recordAttempt(0))
    }

    @Test
    fun failedAttemptRemainsPendingUntilRetrySucceeds() {
        val tracker = ControllerArrivalTracker()

        assertFalse(tracker.recordAttempt(-2))
        assertFalse(tracker.isReported)

        assertTrue(tracker.recordAttempt(0))
        assertTrue(tracker.isReported)
    }

    @Test
    fun successfulArrivalRemainsReported() {
        val tracker = ControllerArrivalTracker()

        assertTrue(tracker.recordAttempt(0))
        assertTrue(tracker.recordAttempt(-1))
    }
}
