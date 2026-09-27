package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickCenterTest {
    @Test fun neutralAndEndpointsArePreserved() {
        for (center in listOf(-0.9f, -0.2f, 0f, 0.3f, 0.9f)) {
            assertEquals(0f, recenterStickAxis(center, center), 0.00001f)
            assertEquals(-1f, recenterStickAxis(-1f, center), 0.00001f)
            assertEquals(1f, recenterStickAxis(1f, center), 0.00001f)
        }
    }

    @Test fun eachSideUsesItsRemainingTravel() {
        assertEquals(0.5f, recenterStickAxis(0.6f, 0.2f), 0.00001f)
        assertEquals(-0.5f, recenterStickAxis(-0.4f, 0.2f), 0.00001f)
    }

    @Test fun defaultCalibrationIsIdentity() {
        for (i in -100..100) {
            val value = i / 100f
            assertEquals(value, recenterStickAxis(value, 0f), 0f)
        }
    }

    @Test fun correctionIsBoundedAndMonotonic() {
        for (center in listOf(-0.9f, 0.15f, 0.9f)) {
            var previous = -1f
            for (i in -200..200) {
                val corrected = recenterStickAxis(i / 100f, center)
                assertTrue(corrected in -1f..1f)
                assertTrue(corrected >= previous)
                previous = corrected
            }
        }
    }

    @Test fun invalidValuesCannotReachTheHost() {
        assertEquals(0f, recenterStickAxis(Float.NaN, 0f), 0f)
        assertEquals(0f, recenterStickAxis(Float.POSITIVE_INFINITY, 0f), 0f)
        for (center in listOf(Float.NaN, 1f, -1f, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.4f, recenterStickAxis(0.4f, center), 0f)
        }
    }
}
