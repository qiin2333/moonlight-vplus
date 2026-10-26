package com.limelight.binding.input.driver

import com.limelight.nvstream.Ds5HapticsState
import org.junit.Assert.*
import org.junit.Test

class DualSenseUsbHapticsOutputTest {
    @Test fun compatibilityStopRestoresPcmAndUnrelatedOutputKeepsLiveRumble() {
        val output = DualSenseUsbHapticsOutput().apply { nativeActive = true }
        val rumble = DualSenseUsbOutputReport.rumble(0x5500, 0x3300)
        output.applyTo(rumble)
        assertEquals(3, rumble[1].toInt() and 3)
        val led = DualSenseUsbOutputReport.controllerLED(1, 2, 3)
        output.applyTo(led)
        assertEquals(3, led[1].toInt() and 3)
        assertEquals(0x55, led[4].toInt())
        val stop = DualSenseUsbOutputReport.rumble(0, 0)
        output.applyTo(stop)
        assertEquals(0, stop[1].toInt() and 3)
    }

    @Test fun hostModeAndClaimedControlsOverrideGenericRumbleUntilReset() {
        val output = DualSenseUsbHapticsOutput().apply {
            control = Ds5HapticsState(byteArrayOf(2, 4, 0x62, 0, 0, 0x10, 0x23, 1))
        }
        val report = DualSenseUsbOutputReport.controllerLED(1, 2, 3)
        output.applyTo(report)
        assertEquals(2, report[1].toInt() and 3)
        assertEquals(4, report[39].toInt() and 4)
        assertEquals(0x66, report[2].toInt())
        assertEquals(0x10, report[10].toInt())
        assertEquals(0x23, report[37].toInt())
        assertEquals(1, report[40].toInt())
        assertEquals(1, report[45].toInt())
        output.reset()
        val rumble = DualSenseUsbOutputReport.rumble(0x5500, 0)
        output.applyTo(rumble)
        assertEquals(3, rumble[1].toInt() and 3)
        assertEquals(0x55, rumble[4].toInt())
    }
}
