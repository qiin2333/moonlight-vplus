package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneInitialStateTest {
    @Test
    fun unknownPreferenceValuesAreSafeAndDefaultToOff() {
        assertEquals(MicrophoneInitialState.OFF, MicrophoneInitialState.fromPreferenceValue("unknown"))
        assertEquals(MicrophoneInitialState.OFF, MicrophoneInitialState.fromPreferenceValue(null))
    }

    @Test
    fun policyResolvesWithoutOverwritingLastState() {
        assertEquals(false, MicrophoneInitialState.OFF.resolve(true))
        assertEquals(true, MicrophoneInitialState.ON.resolve(false))
        assertEquals(false, MicrophoneInitialState.FOLLOW_LAST.resolve(null))
        assertEquals(true, MicrophoneInitialState.FOLLOW_LAST.resolve(true))
    }
}
