package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrFrame
import com.limelight.nvstream.Ds5HapticsIrLane
import org.junit.Assert.*
import org.junit.Test

class AuthoredHapticsRendererTest {
    @Test fun fineImpulsesGoToTheDeviceWithoutDrivingTheErmBody() {
        val output = AuthoredHapticsRenderer().render(frame(5_000))!!
        assertTrue(output.tick)
        assertTrue(output.body.isZero)
        assertFalse(output.rumble.isZero) // A real fallback still exists without a tick-capable device.
    }

    @Test fun heldEnergyCannotGenerateAnArtificialRainTrain() {
        val renderer = AuthoredHapticsRenderer()
        val ticks = (1..100).count { renderer.render(frame(it * 5_000L))!!.tick }
        assertEquals(1, ticks)
        assertFalse(renderer.render(frame(505_000, transient = 0f))!!.tick)
        assertTrue(renderer.render(frame(550_000))!!.tick)
    }

    @Test fun lowBandImpactRetainsControllerBodyAndStreamEndClearsIt() {
        val renderer = AuthoredHapticsRenderer()
        val lane = Ds5HapticsIrLane(0.6f, 0.9f, 0.8f, 0.9f, 160f)
        val impact = renderer.render(frame(5_000).copy(left = lane, right = lane))!!
        assertTrue(impact.body.lowFrequency > 0.5f)
        val end = renderer.render(frame(10_000).copy(flags = Ds5HapticsIrFrame.FLAG_STREAM_END))!!
        assertTrue(end.ended)
        assertTrue(end.body.isZero)
        assertTrue(end.rumble.isZero)
        assertFalse(end.tick)
    }

    @Test fun duplicatePacketsAndNonFiniteInputsCannotCreateMoreOutput() {
        val renderer = AuthoredHapticsRenderer()
        val first = frame(5_000)
        assertNotNull(renderer.render(first))
        assertNull(renderer.render(first))
        val invalid = Ds5HapticsIrLane(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, 0f)
        val output = renderer.render(frame(10_000).copy(left = invalid, right = invalid))!!
        assertFalse(output.tick)
        assertTrue(output.rumble.isZero)
    }

    @Test fun discontinuityRearmsAnOnsetInsteadOfKeepingAnOldAttackLatched() {
        val renderer = AuthoredHapticsRenderer()
        assertTrue(renderer.render(frame(50_000))!!.tick)
        assertTrue(renderer.render(frame(5_000).copy(flags = Ds5HapticsIrFrame.FLAG_DISCONTINUITY))!!.tick)
    }

    @Test fun aPhoneOnlyRouteRetainsHeavyEffectsWhileFineTexturesUseTicks() {
        val body = ControllerRumbleState(0.5f, 0.1f)
        val heavy = AuthoredHapticsRenderer.Output(body, body, true, false)
        assertFalse(heavy.usesDeviceDetails(GameRumbleMode.DEVICE, true, true, true))
        assertFalse(heavy.usesDeviceDetails(GameRumbleMode.COORDINATED, false, true, true))
        assertTrue(heavy.usesDeviceDetails(GameRumbleMode.COORDINATED, true, true, true))
        val fine = heavy.copy(body = ControllerRumbleState.ZERO)
        assertTrue(fine.usesDeviceDetails(GameRumbleMode.DEVICE, false, true, true))
        assertFalse(fine.usesDeviceDetails(GameRumbleMode.COORDINATED, true, true, false))
        assertFalse(fine.usesDeviceDetails(GameRumbleMode.CONTROLLER, true, true, true))
    }

    @Test fun legacyTickCalibrationOnlyUsesTheMeasuredFirmware() {
        assertEquals(40L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 33, "Flyme 10.5.0.0A"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 34, "Flyme 10.5.0.0A"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("meizu", "meizu 17", 33, "other firmware"))
        assertEquals(90L, DeviceDetailProfile.tickIntervalMs("other", "other", 33, "Flyme 10.5.0.0A"))
    }

    private fun frame(timeUs: Long, transient: Float = 0.25f): Ds5HapticsIrFrame {
        val lane = Ds5HapticsIrLane(0.05f, 0.1f, transient, 0.1f, 440f)
        return Ds5HapticsIrFrame(0, 0, (timeUs / 5_000).toInt(), timeUs, 240, lane, lane)
    }
}
