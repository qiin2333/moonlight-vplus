package com.limelight.computers

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicTimeoutManagerTest {
    @Test
    fun probeCooldownUsesTenSecondStepsAndCapsAtThirtySeconds() {
        assertEquals(0L, DynamicTimeoutManager.calculateProbeCooldownMs(0))
        assertEquals(0L, DynamicTimeoutManager.calculateProbeCooldownMs(2))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(3))
        assertEquals(20_000L, DynamicTimeoutManager.calculateProbeCooldownMs(4))
        assertEquals(30_000L, DynamicTimeoutManager.calculateProbeCooldownMs(5))
        assertEquals(30_000L, DynamicTimeoutManager.calculateProbeCooldownMs(20))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(4, 10_000L))
        assertEquals(10_000L, DynamicTimeoutManager.calculateProbeCooldownMs(20, 10_000L))
    }
}
