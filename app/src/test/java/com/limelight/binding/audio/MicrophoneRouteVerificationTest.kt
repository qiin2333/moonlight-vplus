package com.limelight.binding.audio

import com.limelight.binding.audio.MicrophoneRouteVerification.Result.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneRouteVerificationTest {
    @Test fun acceptedRequestWithoutActualRouteIsNotSuccess() {
        val check = MicrophoneRouteVerification(2, 0, 100)
        assertEquals(WAITING, check.observe(1, 1, 100))
        assertEquals(FAILED, check.observe(2000, 1, 100))
    }

    @Test fun actualRouteWithoutPcmIsNotSuccess() {
        val check = MicrophoneRouteVerification(2, 0, 100)
        assertEquals(WAITING, check.observe(1, 2, 0))
        assertEquals(FAILED, check.observe(2000, 2, 0))
    }

    @Test fun requiresContinuousPcmOnTheRequestedRoute() {
        val check = MicrophoneRouteVerification(2, 0, 100)
        assertEquals(WAITING, check.observe(1, 2, 50))
        assertEquals(WAITING, check.observe(2, 1, 100))
        assertEquals(WAITING, check.observe(3, 2, 50))
        assertEquals(READY, check.observe(4, 2, 50))
    }

    @Test fun nullRouteResetsPreviouslyObservedData() {
        val check = MicrophoneRouteVerification(2, 0, 100)
        assertEquals(WAITING, check.observe(1, 2, 50))
        assertEquals(WAITING, check.observe(2, null, 100))
        assertEquals(WAITING, check.observe(3, 2, 50))
    }

    @Test fun negativeReadFailsImmediatelyEvenOnRequestedRoute() {
        for (error in listOf(-1, -2, -3, -6)) {
            assertEquals(FAILED, MicrophoneRouteVerification(2, 0).observe(1, 2, error))
        }
    }

    @Test fun systemDefaultPreservesCaptureEvenWhenVendorDoesNotReportARoute() {
        val check = MicrophoneRouteVerification(null, 0, 100)
        assertEquals(WAITING, check.observe(1, null, 50))
        assertEquals(READY, check.observe(2, null, 50))
        assertEquals(READY, MicrophoneRouteVerification(null, 0, 100).observe(1, 9, 100))
    }

    @Test fun defaultRouteMustAlsoRemainStableAcrossSamples() {
        val check = MicrophoneRouteVerification(null, 0, 100)
        assertEquals(WAITING, check.observe(1, 8, 50))
        assertEquals(WAITING, check.observe(2, 9, 50))
        assertEquals(READY, check.observe(3, 9, 50))
    }

    @Test fun validPcmDoesNotNeedAnyLoudnessThreshold() {
        assertEquals(READY, MicrophoneRouteVerification(2, 0, 100).observe(1, 2, 100))
    }

    @Test fun deadlineUsesMonotonicElapsedTimeRelativeToRequest() {
        val check = MicrophoneRouteVerification(2, 10000, 100)
        assertEquals(WAITING, check.observe(11999, 2, 50))
        assertEquals(FAILED, check.observe(12000, 2, 50))
    }

    @Test fun recoveryHasItsOwnBoundedValidation() {
        val switch = MicrophoneRouteVerification(2, 0, 100)
        assertEquals(FAILED, switch.observe(2000, 1, 100))
        val recovery = MicrophoneRouteVerification(1, 2000, 100)
        assertEquals(WAITING, recovery.observe(2001, 1, 50))
        assertEquals(READY, recovery.observe(2002, 1, 50))
        assertEquals(FAILED, MicrophoneRouteVerification(1, 2000).observe(4000, 1, 0))
    }
}
