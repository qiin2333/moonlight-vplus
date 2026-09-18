package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerPcmReadinessTest {
    @Test fun readinessWaitsForArrivalAndRetriesFailedQueuesWithoutDuplicatingSuccess() {
        val state = ControllerPcmReadiness()
        val messages = mutableListOf<Boolean>()
        val send: (Boolean) -> Boolean = { messages.add(it); true }
        state.update(0, false, true, send)
        state.update(0, true, false, send)
        assertEquals(emptyList<Boolean>(), messages)
        state.update(0, true, true) { false }
        state.update(0, true, true, send)
        state.update(0, true, true, send)
        state.update(1, true, false, send)
        state.update(0, true, false, send)
        assertEquals(listOf(true, false), messages)
    }

    @Test fun RecreatedPlayerMustDeclareReadinessAgain() {
        val state = ControllerPcmReadiness()
        val messages = mutableListOf<Boolean>()
        val send: (Boolean) -> Boolean = { messages.add(it); true }
        state.update(2, true, true, send)
        state.removed(2)
        state.update(2, true, true, send)
        state.update(2, false, false, send)
        assertEquals(listOf(true, true), messages)
    }
}
