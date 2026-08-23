package com.limelight.handbook

import org.junit.Assert.assertEquals
import org.junit.Test

class HandbookControllerScrollTest {
    @Test
    fun centeredAndDriftingStickDoNotScroll() {
        assertEquals(0, handbookControllerScrollDelta(0f, 1_000))
        assertEquals(0, handbookControllerScrollDelta(0.34f, 1_000))
        assertEquals(0, handbookControllerScrollDelta(-0.34f, 1_000))
    }

    @Test
    fun verticalDirectionAndMagnitudeControlScrollStep() {
        assertEquals(60, handbookControllerScrollDelta(0.5f, 1_000))
        assertEquals(-60, handbookControllerScrollDelta(-0.5f, 1_000))
        assertEquals(120, handbookControllerScrollDelta(1f, 1_000))
        assertEquals(-120, handbookControllerScrollDelta(-1f, 1_000))
    }

    @Test
    fun invalidViewportDoesNotScroll() {
        assertEquals(0, handbookControllerScrollDelta(1f, 0))
        assertEquals(0, handbookControllerScrollDelta(1f, -1))
    }
}
