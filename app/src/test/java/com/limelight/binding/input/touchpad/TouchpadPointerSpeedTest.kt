package com.limelight.binding.input.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class TouchpadPointerSpeedTest {
    @Test
    fun `100 percent keeps the reported size`() {
        assertEquals(92, TouchpadPointerSpeed.scaledSizeMm(92, 100))
    }

    @Test
    fun `faster speed reports a larger pad`() {
        assertEquals(184, TouchpadPointerSpeed.scaledSizeMm(92, 200))
        assertEquals(276, TouchpadPointerSpeed.scaledSizeMm(92, 300))
    }

    @Test
    fun `slower speed reports a smaller pad`() {
        assertEquals(46, TouchpadPointerSpeed.scaledSizeMm(92, 50))
    }

    @Test
    fun `out of range speed is clamped`() {
        assertEquals(46, TouchpadPointerSpeed.scaledSizeMm(92, 1))
        assertEquals(276, TouchpadPointerSpeed.scaledSizeMm(92, 900))
    }

    @Test
    fun `scaled size never collapses to zero`() {
        assertEquals(1, TouchpadPointerSpeed.scaledSizeMm(1, 50))
    }

    @Test
    fun `relative counts keep the unused fraction`() {
        val (first, remainder) = TouchpadPointerSpeed.scaleRelativeDelta(1f, 0f, 150)
        assertEquals(1, first)
        assertFloat(0.5f, remainder)

        val (second, nextRemainder) = TouchpadPointerSpeed.scaleRelativeDelta(1f, remainder, 150)
        assertEquals(2, second)
        assertEquals(0f, nextRemainder)
    }

    @Test
    fun `relative 100 percent keeps a fractional axis delta`() {
        val (first, remainder) = TouchpadPointerSpeed.scaleRelativeDelta(0.6f, 0f, 100)
        assertEquals(0, first)
        assertFloat(0.6f, remainder)

        val (second, nextRemainder) = TouchpadPointerSpeed.scaleRelativeDelta(0.6f, remainder, 100)
        assertEquals(1, second)
        assertFloat(0.2f, nextRemainder)
    }

    private fun assertFloat(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.000001)
    }
}
