package com.limelight.perfhud

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfOverlayGesturesTest {
    @Test fun freshInstallDefaultsToFull() {
        assertEquals(PerfOverlayGestures.State(PerfOverlayGestures.FULL, true, true, true),
            PerfOverlayGestures.resolve(null, null, null, null))
    }

    @Test fun existingDefaultAndCustomCombinationsArePreserved() {
        assertEquals(PerfOverlayGestures.State(PerfOverlayGestures.LONG_ONLY, false, false, true),
            PerfOverlayGestures.resolve(null, false, false, true))
        assertEquals(PerfOverlayGestures.State(PerfOverlayGestures.CUSTOM, true, false, false),
            PerfOverlayGestures.resolve(null, true, false, false))
    }

    @Test fun explicitModeOverridesLegacyValues() {
        assertEquals(PerfOverlayGestures.State(PerfOverlayGestures.FULL, true, true, true),
            PerfOverlayGestures.resolve(PerfOverlayGestures.FULL, false, false, false))
        assertEquals(PerfOverlayGestures.State(PerfOverlayGestures.LONG_ONLY, false, false, true),
            PerfOverlayGestures.resolve(PerfOverlayGestures.LONG_ONLY, true, true, false))
    }
}
