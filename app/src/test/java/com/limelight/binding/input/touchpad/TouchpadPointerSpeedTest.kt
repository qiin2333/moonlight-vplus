package com.limelight.binding.input.touchpad

import org.junit.Assert.assertEquals
import org.junit.Test

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
        val (first, remainder) = TouchpadPointerSpeed.scaleRelativeDelta(1, 0f, 150)
        assertEquals(1, first)
        assertEquals(0.5f, remainder)

        val (second, nextRemainder) = TouchpadPointerSpeed.scaleRelativeDelta(1, remainder, 150)
        assertEquals(2, second)
        assertEquals(0f, nextRemainder)
    }

    @Test
    fun `relative 100 percent does not accumulate a remainder`() {
        val (scaled, remainder) = TouchpadPointerSpeed.scaleRelativeDelta(3, 0.25f, 100)
        assertEquals(3, scaled)
        assertEquals(0f, remainder)
    }
}
