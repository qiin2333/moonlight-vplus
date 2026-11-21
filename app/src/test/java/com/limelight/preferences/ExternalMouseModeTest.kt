package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalMouseModeTest {
    @Test
    fun externalMouseSwitchesInBothDirectionsWithoutSelectingTrackpad() {
        val modes = listOf(true, false, true).map { desktop ->
            ExternalMouseMode.resolve(externalMouseSelected = true, desktopModeSupported = true, desktop)
        }
        assertEquals(listOf(ExternalMouseMode.SYSTEM_POINTER, ExternalMouseMode.RELATIVE_CAPTURE,
            ExternalMouseMode.SYSTEM_POINTER), modes)
    }

    @Test
    fun devicesWithoutTheSwitchKeepTheirExistingSystemPointer() {
        for (desktop in listOf(true, false)) {
            assertEquals(ExternalMouseMode.SYSTEM_POINTER, ExternalMouseMode.resolve(true, false, desktop))
            assertEquals(ExternalMouseMode.OTHER_PRESET, ExternalMouseMode.resolve(false, true, desktop))
        }
    }
}
