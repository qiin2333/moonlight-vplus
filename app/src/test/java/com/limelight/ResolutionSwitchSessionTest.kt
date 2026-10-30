package com.limelight

import org.junit.Assert.*
import org.junit.Test

class ResolutionSwitchSessionTest {
    @Test fun oldFramesCannotCompleteAndTimeoutWinsOverLateFrames() {
        val attempt = ResolutionSwitchSession(2560, 1440)
        assertFalse(attempt.presented(1920, 1080))
        assertTrue(attempt.fallback())
        assertFalse(attempt.presented(2560, 1440))
        assertFalse(attempt.fallback())
        assertEquals(ResolutionSwitchSession.State.RECONNECTING, attempt.state)
    }

    @Test fun presentedTargetPreventsLateTimeoutFromReconnecting() {
        val attempt = ResolutionSwitchSession(1920, 1080)
        assertTrue(attempt.presented(1920, 1080))
        assertFalse(attempt.fallback())
        assertFalse(attempt.presented(1920, 1080))
    }

    @Test fun leavingTheActivityCancelsAllLateCompletions() {
        val attempt = ResolutionSwitchSession(1280, 720)
        attempt.cancel()
        assertFalse(attempt.presented(1280, 720))
        assertFalse(attempt.fallback())
    }

    @Test fun combinedFpsOrScreenModeChangesAndSemanticOnlyChangesRequireReconnect() {
        val previous = DisplayReconnectSettings(false, false, 1920, 1080, 60, 20000, 1)
        val next = previous.copy(width = 2560, height = 1440, bitrate = 40000)
        assertTrue(next.isResolutionOnlyChangeFrom(previous))
        assertFalse(next.copy(fps = 120).isResolutionOnlyChangeFrom(previous))
        assertFalse(next.copy(screenCombinationMode = 2).isResolutionOnlyChangeFrom(previous))
        assertFalse(previous.copy(nativeResolution = true).isResolutionOnlyChangeFrom(previous))
    }
}
