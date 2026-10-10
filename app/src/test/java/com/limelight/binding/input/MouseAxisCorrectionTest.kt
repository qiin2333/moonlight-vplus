package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseAxisCorrectionTest {
    @Test fun offPreservesRelativeMovement() {
        assertEquals(12f to -7f, MouseAxisCorrection.OFF.apply(12f, -7f))
    }

    @Test fun rotationsCoverBothSwappedAxisDirections() {
        assertEquals(7f to 12f, MouseAxisCorrection.ROTATE_90.apply(12f, -7f))
        assertEquals(-12f to 7f, MouseAxisCorrection.ROTATE_180.apply(12f, -7f))
        assertEquals(-7f to -12f, MouseAxisCorrection.ROTATE_270.apply(12f, -7f))
    }

    @Test fun unknownPreferenceValueFallsBackToOff() {
        assertEquals(MouseAxisCorrection.OFF, MouseAxisCorrection.fromPreferenceValue("unknown"))
        assertEquals(MouseAxisCorrection.OFF, MouseAxisCorrection.fromPreferenceValue(null))
        assertEquals(MouseAxisCorrection.ROTATE_270, MouseAxisCorrection.fromPreferenceValue("270"))
    }
}
