package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class AuthoredIrAllocatorTest {
    @Test fun uncalibratedRotorDoesNotTakeAuthoredTextureFromTheNativeActuator() {
        val context = GameRumbleContext(GameRumbleMode.COORDINATED, true, true)
        for (low in listOf(0f, .01f, .05f, .1f, .5f, 1f))
            for (high in listOf(0f, .01f, .05f, .1f, .5f, 1f)) {
                val plan = AuthoredIrAllocator.allocateNativeBody(context, ControllerRumbleState(low, high))
                val controller = plan.controller!!
                val device = plan.device!!
                assertEquals(high, device.highFrequency, 0f)
                assertTrue(controller.isZero)
                assertEquals(low, device.lowFrequency, 0f)
            }
    }

    @Test fun singleOutputAndExplicitModesKeepTheirFullContinuousSignal() {
        val body = ControllerRumbleState(.2f, .3f)
        for (mode in GameRumbleMode.entries)
            for (controller in listOf(false, true))
                for (device in listOf(false, true)) {
                    if (mode == GameRumbleMode.COORDINATED && controller && device) continue
                    val plan = AuthoredIrAllocator.allocateNativeBody(
                        GameRumbleContext(mode, controller, device), body)
                    assertEquals(body.takeIf { controller && mode != GameRumbleMode.DEVICE }, plan.controller)
                    assertEquals(body.takeIf { device && mode != GameRumbleMode.CONTROLLER }, plan.device)
                }
    }
}
