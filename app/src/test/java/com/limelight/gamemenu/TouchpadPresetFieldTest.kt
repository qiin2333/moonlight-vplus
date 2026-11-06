package com.limelight.gamemenu

import com.limelight.preferences.TouchpadInputPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchpadPresetFieldTest {
    @Test
    fun touchpadSnapshotsExcludeCrownWheelRepeatAndProfileFlags() {
        assertEquals(setOf(TouchpadInputPreferences.SENSITIVITY, TouchpadInputPreferences.SCROLL_SPEED),
            TouchpadPresetField.entries.map { it.key }.toSet())
    }

    @Test
    fun valuesKeepSeparateRangesAndRejectNonNumbers() {
        assertEquals(1000, TouchpadPresetField.SENSITIVITY.normalize("1200"))
        assertEquals(500, TouchpadPresetField.SCROLL_SPEED.normalize("1200"))
        TouchpadPresetField.entries.forEach { field ->
            assertEquals(1, field.normalize("0"))
            assertEquals(100, field.normalize("100"))
            assertNull(field.normalize("not-a-number"))
        }
    }
}
