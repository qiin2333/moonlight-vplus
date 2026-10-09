package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateRangeTest {
    @Test
    fun everySelectableBaselineHasValidBoundsContainingItsStartingValue() {
        for (mode in listOf("quality", "balanced", "lowLatency")) {
            for (baseline in 500..800_000 step 500) {
                val range = AdaptiveBitrateService.bitrateRange(baseline, mode)
                assertTrue("$mode/$baseline must remain adjustable", range.first <= range.last)
                assertTrue("$mode/$baseline must contain the user's starting value", baseline in range)
                assertTrue(range.first >= 500 && range.last <= 800_000)
            }
        }
    }

    @Test
    fun ordinaryPresetsKeepTheirExistingRanges() {
        assertEquals(5000..15_000, AdaptiveBitrateService.bitrateRange(10_000, "quality"))
        assertEquals(3000..20_000, AdaptiveBitrateService.bitrateRange(10_000, "balanced"))
        assertEquals(2000..12_000, AdaptiveBitrateService.bitrateRange(10_000, "lowLatency"))
    }
}
