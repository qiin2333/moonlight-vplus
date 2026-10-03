package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderConfigurationTrackerTest {
    @Test
    fun newerGrowthCannotBeClearedByOlderConfiguration() {
        val tracker = DecoderConfigurationTracker()
        tracker.reset(1920, 1080)

        val initial = tracker.snapshot()
        assertTrue(tracker.markConfigured(initial))

        assertTrue(tracker.updateResolution(2560, 1440))
        val firstGrowth = tracker.snapshot()
        assertTrue(tracker.updateResolution(3840, 2160))

        assertFalse(tracker.markConfigured(firstGrowth))
        assertEquals(
            DecoderConfigurationSnapshot(3840, 2160, 2),
            tracker.snapshot(),
        )
        assertTrue(tracker.markConfigured(tracker.snapshot()))
    }

    @Test
    fun shrinkDuringReconfigurationDoesNotInvalidateLargerConfiguration() {
        val tracker = DecoderConfigurationTracker()
        tracker.reset(1920, 1080)
        assertTrue(tracker.markConfigured(tracker.snapshot()))

        assertTrue(tracker.updateResolution(3840, 2160))
        val largerConfiguration = tracker.snapshot()
        assertFalse(tracker.updateResolution(2560, 1440))

        assertTrue(tracker.markConfigured(largerConfiguration))
        assertFalse(tracker.updateResolution(3200, 1800))
    }

    @Test
    fun growthInEitherDimensionCreatesANewGeneration() {
        val tracker = DecoderConfigurationTracker()
        tracker.reset(1920, 1080)
        assertTrue(tracker.markConfigured(tracker.snapshot()))

        assertTrue(tracker.updateResolution(1600, 1200))
        assertEquals(1L, tracker.snapshot().generation)
    }
}
