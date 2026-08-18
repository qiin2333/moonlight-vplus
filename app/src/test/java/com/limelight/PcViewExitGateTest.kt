package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcViewExitGateTest {
    @Test
    fun exitsOnlyOnSecondRequestWithinTimeout() {
        val gate = PcViewExitGate(timeoutMillis = 2_000L)

        assertFalse(gate.requestExit(nowMillis = 1_000L))
        assertTrue(gate.requestExit(nowMillis = 2_500L))
    }

    @Test
    fun expiredOrCancelledRequestRequiresAnotherConfirmation() {
        val gate = PcViewExitGate(timeoutMillis = 2_000L)

        assertFalse(gate.requestExit(nowMillis = 1_000L))
        assertFalse(gate.requestExit(nowMillis = 3_001L))
        gate.cancel()
        assertFalse(gate.requestExit(nowMillis = 3_100L))
    }
}
