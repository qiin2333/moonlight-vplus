package com.limelight.preferences


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddComputerWorkerGenerationTest {
    @Test
    fun invalidationMakesPreviousWorkerStale() {
        val gate = AddComputerWorkerGenerationGate()
        val first = gate.nextGeneration()

        assertTrue(gate.isCurrent(first))
        gate.invalidate()

        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun startingNewGenerationMakesOlderWorkerStale() {
        val gate = AddComputerWorkerGenerationGate()
        val first = gate.nextGeneration()
        val second = gate.nextGeneration()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

}
