package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneRouteStageTest {
    @Test fun waitingDoesNotImplySuccessfulConnection() {
        val stage = MicrophoneRouteStage()
        assertNull(stage.result)
        stage.complete(true)
        assertEquals(true, stage.result)
    }

    @Test fun duplicateCallbackCannotTurnFailureIntoSuccess() {
        val stage = MicrophoneRouteStage()
        stage.complete(false)
        stage.complete(true)
        assertEquals(false, stage.result)
    }

    @Test fun cancelledRequestRejectsLateConnectionCallback() {
        val old = MicrophoneRouteStage()
        old.cancel()
        val next = MicrophoneRouteStage()
        old.complete(true)
        assertEquals(false, old.result)
        assertNull(next.result)
        next.complete(true)
        assertEquals(true, next.result)
    }

    @Test fun cancellationOverridesSuccessBeforeItIsConsumed() {
        val stage = MicrophoneRouteStage()
        stage.complete(true)
        stage.cancel()
        assertEquals(false, stage.result)
    }
}
