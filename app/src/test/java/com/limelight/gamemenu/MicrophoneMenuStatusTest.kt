package com.limelight.gamemenu

import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneMenuStatusTest {
    @Test fun systemSilencedRecordingDoesNotClaimItIsUsingTheDevice() {
        for (hasRoutedDevice in listOf(false, true)) {
            assertEquals(R.string.mic_input_system_silenced,
                microphoneRecordingStatusResource(29, true, hasRoutedDevice, systemSilenced = true))
        }
    }

    @Test fun stoppedRecordingDoesNotRetainTheSystemSilencedStatus() {
        assertEquals(R.string.mic_disabled,
            microphoneRecordingStatusResource(36, false, true, systemSilenced = true))
    }

    @Test fun legacyAndroidDoesNotClaimSystemSilenceDetection() {
        assertEquals(R.string.mic_input_active,
            microphoneRecordingStatusResource(28, true, true, systemSilenced = true))
    }

    @Test fun api22ActiveCaptureUsesSystemDefaultRatherThanWaitingForARoute() {
        assertEquals(R.string.mic_input_active,
            microphoneRecordingStatusResource(sdkInt = 22, capturing = true, hasRoutedDevice = false))
    }

    @Test fun inactiveCaptureIsDisabledRegardlessOfApiOrLastKnownRoute() {
        for (sdkInt in listOf(22, 23, 36)) {
            for (hasRoutedDevice in listOf(false, true)) {
                assertEquals(R.string.mic_disabled,
                    microphoneRecordingStatusResource(sdkInt, capturing = false, hasRoutedDevice))
            }
        }
    }

    @Test fun api23AndNewerWaitForAnUnresolvedRoute() {
        for (sdkInt in listOf(23, 36)) {
            assertEquals(R.string.mic_input_route_pending,
                microphoneRecordingStatusResource(sdkInt, capturing = true, hasRoutedDevice = false))
        }
    }

    @Test fun api23AndNewerDisplayTheResolvedRecordingDevice() {
        for (sdkInt in listOf(23, 36)) {
            assertEquals(R.string.mic_input_active,
                microphoneRecordingStatusResource(sdkInt, capturing = true, hasRoutedDevice = true))
        }
    }
}
