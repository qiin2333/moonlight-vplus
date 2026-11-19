package com.limelight.computers

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicTimeoutManagerTest {
    @Test
    fun probeCooldownUsesTenSecondsForFirstThirtyFailuresThenCapsAtThirtySeconds() {
        assertEquals(0L, DynamicTimeoutManager.calculateProbeCooldownMs(0))
        assertEquals(0L, DynamicTimeoutManager.calculateProbeCooldownMs(2))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(3))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(30))
        assertEquals(30_000L, DynamicTimeoutManager.calculateProbeCooldownMs(31))
        assertEquals(30_000L, DynamicTimeoutManager.calculateProbeCooldownMs(100))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(31, 10_000L))
    }
}
