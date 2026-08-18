package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewFeatureGuidePolicyTest {
    @Test
    fun resumeTitleOnlyParticipatesWhenAStreamIsRunning() {
        assertFalse(shouldEnableAppViewResumeTitle(runningAppId = 0))
        assertTrue(shouldEnableAppViewResumeTitle(runningAppId = 42))
    }

    @Test
    fun waitsForInitialComputerState() {
        assertFalse(
            shouldScheduleAppViewFeatureGuide(
                alreadyScheduled = false,
                initialComputerStateLoaded = false
            )
        )
    }

    @Test
    fun schedulesOnceAfterInitialComputerState() {
        assertTrue(
            shouldScheduleAppViewFeatureGuide(
                alreadyScheduled = false,
                initialComputerStateLoaded = true
            )
        )
        assertFalse(
            shouldScheduleAppViewFeatureGuide(
                alreadyScheduled = true,
                initialComputerStateLoaded = true
            )
        )
    }
}
