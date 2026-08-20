package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameRumbleRouterTest {
    private val input = ControllerRumbleState(lowFrequency = 0.8f, highFrequency = 0.6f)

    @Test
    fun coordinatedModeSplitsFrequencyBandsWhenBothSinksAreAvailable() {
        val route = route(GameRumbleMode.COORDINATED, hasController = true, hasDevice = true)

        assertState(route.controller, low = 0.8f, high = 0.27f)
        assertState(route.device, low = 0.16f, high = 0.6f)
    }

    @Test
    fun coordinatedModePreservesFullSignalWithOnlyController() {
        val route = route(GameRumbleMode.COORDINATED, hasController = true, hasDevice = false)

        assertState(route.controller, low = 0.8f, high = 0.6f)
        assertNull(route.device)
    }

    @Test
    fun coordinatedModePreservesFullSignalWithOnlyDevice() {
        val route = route(GameRumbleMode.COORDINATED, hasController = false, hasDevice = true)

        assertNull(route.controller)
        assertState(route.device, low = 0.8f, high = 0.6f)
    }

    @Test
    fun deviceModeUsesOnlyDevice() {
        val route = route(GameRumbleMode.DEVICE, hasController = true, hasDevice = true)

        assertNull(route.controller)
        assertState(route.device, low = 0.8f, high = 0.6f)
    }

    @Test
    fun controllerModeUsesOnlyController() {
        val route = route(GameRumbleMode.CONTROLLER, hasController = true, hasDevice = true)

        assertState(route.controller, low = 0.8f, high = 0.6f)
        assertNull(route.device)
    }

    @Test
    fun selectedSinkMustBeAvailable() {
        val deviceRoute = route(GameRumbleMode.DEVICE, hasController = true, hasDevice = false)
        val controllerRoute = route(GameRumbleMode.CONTROLLER, hasController = false, hasDevice = true)

        assertNull(deviceRoute.controller)
        assertNull(deviceRoute.device)
        assertNull(controllerRoute.controller)
        assertNull(controllerRoute.device)
    }

    @Test
    fun invalidPreferenceFallsBackToControllerMode() {
        assertEquals(GameRumbleMode.CONTROLLER, GameRumbleMode.fromPreferenceValue("invalid"))
        assertEquals(GameRumbleMode.CONTROLLER, GameRumbleMode.fromPreferenceValue(null))
    }

    @Test
    fun persistedSmartValueMapsToCoordinatedMode() {
        assertEquals(GameRumbleMode.COORDINATED, GameRumbleMode.fromPreferenceValue("smart"))
    }

    @Test
    fun legacyFallbackMigratesToSafeModes() {
        assertEquals(GameRumbleMode.COORDINATED, GameRumbleMode.fromLegacyFallback(true))
        assertEquals(GameRumbleMode.CONTROLLER, GameRumbleMode.fromLegacyFallback(false))
    }

    private fun route(
        mode: GameRumbleMode,
        hasController: Boolean,
        hasDevice: Boolean
    ): GameRumbleRoute = GameRumbleRouter.route(mode, input, hasController, hasDevice)

    private fun assertState(
        actual: ControllerRumbleState?,
        low: Float,
        high: Float
    ) {
        requireNotNull(actual)
        assertEquals(low, actual.lowFrequency, 0.0001f)
        assertEquals(high, actual.highFrequency, 0.0001f)
    }
}
