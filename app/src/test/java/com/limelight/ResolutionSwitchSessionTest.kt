package com.limelight

import org.junit.Assert.*
import org.junit.Test

class ResolutionSwitchSessionTest {
    @Test fun oldFramesCannotCompleteAndTimeoutWinsOverLateFrames() {
        val attempt = ResolutionSwitchSession(2560, 1440)
        assertFalse(attempt.isWaitingFor(1920, 1080))
        assertTrue(attempt.isWaitingFor(2560, 1440))
        assertFalse(attempt.presented(1920, 1080))
        assertTrue(attempt.fallback())
        attempt.cancel()
        assertFalse(attempt.isWaitingFor(2560, 1440))
        assertFalse(attempt.presented(2560, 1440))
        assertFalse(attempt.fallback())
        assertEquals(ResolutionSwitchSession.State.RECONNECTING, attempt.state)
    }

    @Test fun presentedTargetPreventsLateTimeoutFromReconnecting() {
        val attempt = ResolutionSwitchSession(1920, 1080)
        assertTrue(attempt.presented(1920, 1080))
        attempt.cancel()
        assertEquals(ResolutionSwitchSession.State.COMPLETE, attempt.state)
        assertFalse(attempt.fallback())
        assertFalse(attempt.presented(1920, 1080))
    }

    @Test fun leavingTheActivityCancelsAllLateCompletions() {
        val attempt = ResolutionSwitchSession(1280, 720)
        attempt.cancel()
        assertFalse(attempt.presented(1280, 720))
        assertFalse(attempt.fallback())
    }

    @Test fun onlyCompatibleResolutionChangesCanKeepTheConnection() {
        val previous = DisplayReconnectSettings(false, false, 1920, 1080, 60, 20000, 1)
        val next = previous.copy(width = 2560, height = 1440, bitrate = 40000)
        assertTrue(next.canSwitchResolutionFrom(previous, adaptiveBitrate = false))
        assertTrue(next.copy(nativeResolution = true).canSwitchResolutionFrom(previous, adaptiveBitrate = false))
        assertTrue(next.copy(customResolution = true).canSwitchResolutionFrom(previous, adaptiveBitrate = false))
        assertFalse(previous.canSwitchResolutionFrom(previous, adaptiveBitrate = false))
        assertFalse(next.canSwitchResolutionFrom(previous, adaptiveBitrate = true))
        assertTrue(next.copy(bitrate = previous.bitrate).canSwitchResolutionFrom(previous, adaptiveBitrate = true))
        for (incompatible in listOf(
            next.copy(fps = 120), next.copy(screenCombinationMode = 2),
            next.copy(width = 1440, height = 2560), next.copy(width = 1440, height = 1440),
            next.copy(width = 2561), next.copy(width = 0), next.copy(width = 16386)
        )) assertFalse(incompatible.canSwitchResolutionFrom(previous, adaptiveBitrate = false))
    }
}
