package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPointerSensitivityPolicyTest {
    @Test
    fun `normalizes existing preference range`() {
        assertEquals(0, TouchPointerSensitivityPolicy.normalize(-20f))
        assertEquals(123, TouchPointerSensitivityPolicy.normalize(122.6f))
        assertEquals(500, TouchPointerSensitivityPolicy.normalize(900f))
    }

    @Test
    fun `converts percentage to runtime factor`() {
        assertEquals(0f, TouchPointerSensitivityPolicy.runtimeFactor(0), 0.0001f)
        assertEquals(1f, TouchPointerSensitivityPolicy.runtimeFactor(100), 0.0001f)
        assertEquals(5f, TouchPointerSensitivityPolicy.runtimeFactor(500), 0.0001f)
    }

    @Test
    fun `only split direct touch exposes pointer speed`() {
        assertTrue(TouchPointerSensitivityPolicy.isApplicable(true, false, false, false))
        assertFalse(TouchPointerSensitivityPolicy.isApplicable(false, false, false, false))
        assertFalse(TouchPointerSensitivityPolicy.isApplicable(true, true, false, false))
        assertFalse(TouchPointerSensitivityPolicy.isApplicable(true, false, true, false))
        assertFalse(TouchPointerSensitivityPolicy.isApplicable(true, false, false, true))
    }

    @Test
    fun `accepts localized printable preset names`() {
        assertTrue(TouchPointerSensitivityPolicy.isValidName("预设1"))
        assertTrue(TouchPointerSensitivityPolicy.isValidName("Preset1"))
        assertTrue(TouchPointerSensitivityPolicy.isValidName("FPS 精准"))
    }

    @Test
    fun `rejects blank control and overlong preset names`() {
        assertFalse(TouchPointerSensitivityPolicy.isValidName("   "))
        assertFalse(TouchPointerSensitivityPolicy.isValidName("bad\nname"))
        assertFalse(TouchPointerSensitivityPolicy.isValidName("x".repeat(21)))
    }
}
