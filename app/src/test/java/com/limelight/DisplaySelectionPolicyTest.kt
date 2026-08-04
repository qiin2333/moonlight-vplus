package com.limelight

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplaySelectionPolicyTest {
    @Test
    fun disabledAlwaysUsesDefaultDisplay() {
        assertEquals(
            Display.DEFAULT_DISPLAY,
            DisplaySelectionPolicy.resolveStreamDisplayId(false, 4, listOf(0, 4))
        )
    }

    @Test
    fun selectedConnectedDisplayIsUsedForStreaming() {
        assertEquals(4, DisplaySelectionPolicy.resolveStreamDisplayId(true, 4, listOf(0, 4)))
    }

    @Test
    fun missingSelectionFallsBackToDefaultWithoutChangingPreference() {
        assertEquals(
            Display.DEFAULT_DISPLAY,
            DisplaySelectionPolicy.resolveStreamDisplayId(true, 4, listOf(0, 5))
        )
    }

    @Test
    fun anotherDisplayIsSelectedForControls() {
        assertEquals(4, DisplaySelectionPolicy.resolveControlDisplayId(0, listOf(0, 4)))
        assertEquals(0, DisplaySelectionPolicy.resolveControlDisplayId(4, listOf(0, 4)))
    }

    @Test
    fun controlsRequireASecondDisplay() {
        assertNull(DisplaySelectionPolicy.resolveControlDisplayId(0, listOf(0)))
    }

    @Test
    fun displayLabelIncludesCurrentResolution() {
        assertEquals(
            "display0 (1920×1080)",
            DisplaySelectionFormatter.label("display0", 0, 1920, 1080)
        )
    }

    @Test
    fun blankDisplayNameFallsBackToDisplayId() {
        assertEquals(
            "display4 (1240×1080)",
            DisplaySelectionFormatter.label("  ", 4, 1240, 1080)
        )
    }
}
