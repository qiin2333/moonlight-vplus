package com.limelight.binding.input.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadScrollScalerTest {
    @Test
    fun defaultPreservesExistingOutput() {
        val output = mutableListOf<Short>()
        val scaler = TouchpadScrollScaler()
        for (delta in listOf(-500, -1, 0, 1, 500)) scaler.send(delta, 100, output::add)
        assertEquals(listOf<Short>(-2500, -5, 5, 2500), output)
    }

    @Test
    fun smallMotionAccumulatesAndResetDoesNotLeakToNextGesture() {
        val output = mutableListOf<Short>()
        val scaler = TouchpadScrollScaler()
        repeat(20) { scaler.send(1, 1, output::add) }
        assertEquals(1, output.sumOf { it.toInt() })
        output.clear()
        repeat(10) { scaler.send(-1, 1, output::add) }
        scaler.reset()
        repeat(10) { scaler.send(-1, 1, output::add) }
        assertTrue(output.isEmpty())
    }

    @Test
    fun largeScrollSplitsWithoutChangingSignOrSum() {
        val scaler = TouchpadScrollScaler()
        for (delta in listOf(-4000, 4000)) {
            val output = mutableListOf<Short>()
            scaler.send(delta, 500, output::add)
            assertEquals(delta * 25, output.sumOf { it.toInt() })
            assertTrue(output.all { if (delta < 0) it < 0 else it > 0 })
        }
    }

    @Test
    fun extremeInputHasBoundedWork() {
        val output = mutableListOf<Short>()
        TouchpadScrollScaler().send(Int.MAX_VALUE, 500, output::add)
        assertTrue(output.size <= 32)
        assertTrue(output.all { it > 0 })
    }
}
