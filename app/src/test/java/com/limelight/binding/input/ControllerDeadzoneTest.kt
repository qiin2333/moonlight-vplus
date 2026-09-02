package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerDeadzoneTest {
    @Test
    fun zeroPercentageDisablesClientDeadzone() {
        assertEquals(0.0, controllerStickDeadzoneRadius(0), 0.0)
    }

    @Test
    fun configuredPercentageConvertsToRadius() {
        assertEquals(0.01, controllerStickDeadzoneRadius(1), 0.000_001)
        assertEquals(0.07, controllerStickDeadzoneRadius(7), 0.000_001)
        assertEquals(0.20, controllerStickDeadzoneRadius(20), 0.000_001)
    }

    @Test
    fun negativeRestoredValueFallsBackToZero() {
        assertEquals(0.0, controllerStickDeadzoneRadius(-1), 0.0)
    }
}
