package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGyroDemandStateTest {
    @Test
    fun disablingAssistantPreservesHostMotionDemand() {
        val state = ControllerGyroDemandState()

        state.updateHostReportRate(100)
        state.updateAssistantEnabled(true)
        state.updateAssistantEnabled(false)

        assertTrue(state.shouldSample)
        assertEquals(100, state.effectiveReportRateHz.toInt())
    }

    @Test
    fun assistantUsesDefaultRateWithoutHostDemand() {
        val state = ControllerGyroDemandState(assistantReportRateHz = 120)

        state.updateAssistantEnabled(true)

        assertTrue(state.shouldSample)
        assertEquals(120, state.effectiveReportRateHz.toInt())
    }

    @Test
    fun samplingStopsOnlyAfterEveryConsumerReleasesDemand() {
        val state = ControllerGyroDemandState()

        state.updateHostReportRate(100)
        state.updateAssistantEnabled(true)
        state.updateHostReportRate(0)
        assertTrue(state.shouldSample)
        assertEquals(120, state.effectiveReportRateHz.toInt())

        state.updateAssistantEnabled(false)
        assertFalse(state.shouldSample)
    }

    @Test
    fun clearDropsAllDemandAtEndOfStream() {
        val state = ControllerGyroDemandState()
        state.updateHostReportRate(100)
        state.updateAssistantEnabled(true)

        state.clear()

        assertFalse(state.shouldSample)
        assertEquals(0, state.hostReportRateHz.toInt())
    }
}
