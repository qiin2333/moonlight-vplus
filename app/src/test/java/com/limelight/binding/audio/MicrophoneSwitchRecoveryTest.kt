package com.limelight.binding.audio

import com.limelight.binding.audio.MicrophoneSwitchRecovery.Action.*
import org.junit.Assert.*
import org.junit.Test

class MicrophoneSwitchRecoveryTest {
    private val pcm = MicrophoneConfig.BYTES_PER_FRAME * 5

    @Test fun rejectedRequestPreservesAHealthyRecorder() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, false, 0)
        assertEquals(WAIT, state.observe(10, 1, pcm / 2))
        assertEquals(FAILED, state.observe(60, 1, pcm / 2))
    }

    @Test fun rejectedRequestDoesNotMistakeTheNewRouteForTheOriginalOne() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, false, 0)
        assertEquals(WAIT, state.observe(10, 2, pcm))
        assertEquals(RESTORE, state.observe(2000, 2, pcm))
        state.recoveryStarted(2100)
        assertEquals(FAILED, state.observe(2200, 1, pcm))
    }

    @Test fun ignoredRequestRestoresPreferenceInPlaceWithoutRecreating() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(WAIT, state.observe(10, 1, pcm))
        // A nonblocking read may return zero exactly at the deadline while audio is healthy.
        assertEquals(RESTORE, state.observe(2000, 1, 0))
        state.recoveryStarted(2010)
        assertEquals(FAILED, state.observe(2020, 1, pcm))
    }

    @Test fun matchingTargetConfirmsSuccess() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(SUCCEEDED, state.observe(100, 2, pcm))
    }

    @Test fun deadRecorderIsRecreatedOnceAndRestoresTheOldInput() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RECREATE, state.observe(10, 2, -6))
        state.recoveryStarted(200)
        assertEquals(FAILED, state.observe(300, 1, pcm))
    }

    @Test fun rejectedRequestStillRecoversIfTheRecorderActuallyDied() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, false, 0)
        assertEquals(RECREATE, state.observe(10, 1, -3))
        state.recoveryStarted(200)
        assertEquals(FAILED, state.observe(300, 1, pcm))
    }

    @Test fun noDataHasABoundedRecoveryAndDoesNotLoop() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RECREATE, state.observe(2000, null, 0))
        state.recoveryStarted(2500)
        assertEquals(STOP, state.observe(4500, null, 0))
        assertEquals(STOP, state.recreate(5000))
    }

    @Test fun errorAfterInPlaceRestoreCanRecreateButOnlyOnce() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RESTORE, state.observe(2000, 1, pcm))
        state.recoveryStarted(2100)
        assertEquals(RECREATE, state.observe(2200, 1, -6))
        state.recoveryStarted(2300)
        assertEquals(STOP, state.observe(2400, 1, -6))
    }

    @Test fun routeThatCannotBeRestoredHasNoUnboundedRetry() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RESTORE, state.observe(2000, 3, pcm))
        state.recoveryStarted(2100)
        assertEquals(RECREATE, state.observe(4100, 3, pcm))
        state.recoveryStarted(4200)
        assertEquals(STOP, state.observe(6200, 3, pcm))
    }

    @Test fun defaultRecoveryAcceptsPcmWithoutVendorRouteReporting() {
        val state = MicrophoneSwitchRecovery(2, 1, null, true, 0)
        assertEquals(RECREATE, state.observe(20, null, -6))
        state.recoveryStarted(100)
        assertEquals(FAILED, state.observe(200, null, pcm))
    }

    @Test fun defaultPreferenceNeverPinsOnePhysicalMicrophone() {
        assertNull(microphoneRestoreInputId(null, 1))
        assertNull(microphoneRestoreInputId(null, 2))
    }

    @Test fun ignoredPreferenceRestoresActualDeviceNotTheSavedChoice() {
        assertEquals(1, microphoneRestoreInputId(2, 1))
        assertEquals(2, microphoneRestoreInputId(2, 2))
        assertNull(microphoneRestoreInputId(2, null))
    }

    @Test fun startupFailureCanStartOneRecoveryBeforeTheFirstRead() {
        val state = MicrophoneSwitchRecovery(2, null, null, true, 0)
        assertEquals(RECREATE, state.recreate(10))
        state.recoveryStarted(100)
        assertEquals(STOP, state.recreate(110))
    }

    @Test fun bluetoothRollbackCanReceiveANewInputId() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RESTORE, state.observe(2000, 3, pcm))
        state.recoveryStarted(7000, 4)
        assertEquals(WAIT, state.observe(7010, 1, pcm))
        assertEquals(FAILED, state.observe(7100, 4, pcm))
    }

    @Test fun failedBluetoothPreparationRestoresWithoutSpendingTheRecordingDeadline() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, false, 5000)
        state.recoveryStarted(10000, 1)
        assertEquals(FAILED, state.observe(10100, 1, pcm))
    }

    @Test fun lostCommunicationRouteDoesNotDiscardAHealthyRecorder() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RESTORE, state.observe(10, 2, pcm, routeUsable = false))
        state.recoveryStarted(5000)
        assertEquals(FAILED, state.observe(5100, 1, pcm))
    }

    @Test fun systemSilencedInputCannotBeConfirmedFromPcmLengthAlone() {
        val state = MicrophoneSwitchRecovery(2, 1, 1, true, 0)
        assertEquals(RESTORE, state.observe(10, 2, pcm, routeUsable = false))
        state.recoveryStarted(20)
        assertEquals(RECREATE, state.observe(30, 1, pcm, routeUsable = false))
        state.recoveryStarted(40)
        assertEquals(STOP, state.observe(50, 1, pcm, routeUsable = false))
    }
}
