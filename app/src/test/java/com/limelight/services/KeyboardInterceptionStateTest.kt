package com.limelight.services

import com.limelight.services.KeyboardInterceptionState.Key
import com.limelight.services.KeyboardInterceptionState.Route.*
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardInterceptionStateTest {
    private val state = KeyboardInterceptionState()
    private val ctrl = Key(1, 113, 29)
    private val a = Key(1, 29, 30)

    @Test fun passedChordStaysPassedWhenCaptureResumes() {
        assertEquals(PASS, state.dispatch(ctrl, true, 0, null).route)
        assertEquals(PASS, state.dispatch(a, true, 0, null).route)
        assertEquals(PASS, state.dispatch(ctrl, false, 0, 113).route)
        assertEquals(PASS, state.dispatch(a, false, 0, 29).route)
    }

    @Test fun consumedChordDrainsAcrossPauseAndResume() {
        state.dispatch(ctrl, true, 0, 113)
        state.dispatch(a, true, 0, 29)
        state.pause()
        assertEquals(DRAIN, state.dispatch(ctrl, true, 1, 113).route)
        assertEquals(DRAIN, state.dispatch(a, false, 0, 29).route)
        assertEquals(DRAIN, state.dispatch(ctrl, false, 0, 113).route)
        assertEquals(FORWARD, state.dispatch(a, true, 0, 29).route)
    }

    @Test fun freshDownRecoversFromLostUp() {
        state.dispatch(a, true, 0, 29)
        state.pause()
        assertEquals(PASS, state.dispatch(a, true, 0, null).route)
        assertEquals(PASS, state.dispatch(a, false, 0, 29).route)
    }

    @Test fun mappingIsFrozenUntilRelease() {
        state.dispatch(a, true, 0, 131)
        assertEquals(131, state.dispatch(a, true, 1, 132).keyCode)
        assertEquals(131, state.dispatch(a, false, 0, 132).keyCode)
    }

    @Test fun unmatchedUpAndRepeatPassThrough() {
        assertEquals(PASS, state.dispatch(a, false, 0, 29).route)
        assertEquals(PASS, state.dispatch(a, true, 2, 29).route)
    }

    @Test fun devicesHaveIndependentOwnership() {
        val second = a.copy(deviceId = 2)
        state.dispatch(a, true, 0, 29)
        state.dispatch(second, true, 0, null)
        assertEquals(FORWARD, state.dispatch(a, false, 0, 29).route)
        assertEquals(PASS, state.dispatch(second, false, 0, 29).route)
    }

    @Test fun multiplePausesKeepPassedAndDrainingSequences() {
        state.dispatch(ctrl, true, 0, null)
        state.dispatch(a, true, 0, 29)
        repeat(3) { state.pause() }
        assertEquals(PASS, state.dispatch(ctrl, false, 0, 113).route)
        assertEquals(DRAIN, state.dispatch(a, false, 0, null).route)
    }
}
