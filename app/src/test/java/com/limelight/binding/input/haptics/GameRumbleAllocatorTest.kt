package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameRumbleAllocatorTest {
    private val input = ControllerRumbleState(lowFrequency = 0.8f, highFrequency = 0.6f)

    private val decomposition = RumbleDecomposition(
        sustainedLow = 0.5f,
        transientLow = 0.3f,
        sustainedHigh = 0.2f,
        transientHigh = 0.4f
    )

    @Test
    fun coordinatedModeGivesTheControllerTheFullSignalAndTheBodyTransientCompensation() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = true,
            hasDevice = true,
            decomposition = decomposition
        )

        // The controller is never attenuated in coordinated mode: the body supplements, it
        // does not take over.
        assertState(route.controller, low = 0.8f, high = 0.6f)
        // 0.25 * transientLow = 0.075; 1.0 * transientHigh + 0.30 * sustainedHigh = 0.46.
        // Sustained low is intentionally absent.
        assertState(route.device, low = 0.075f, high = 0.46f)
    }

    @Test
    fun coordinatedModeWithoutTemporalContextKeepsTheBodySilent() {
        val route = route(GameRumbleMode.COORDINATED, hasController = true, hasDevice = true)

        assertState(route.controller, low = 0.8f, high = 0.6f)
        assertNull(route.device)
    }

    @Test
    fun coordinatedModeKeepsTheBodySilentOnBinaryTierDevices() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = true,
            hasDevice = true,
            deviceTier = DeviceHapticsTier.BINARY,
            decomposition = decomposition
        )

        // A body that can only switch on/off cannot carry compensated detail: the controller
        // keeps the full signal and the body gets nothing.
        assertState(route.controller, low = 0.8f, high = 0.6f)
        assertNull(route.device)
    }

    @Test
    fun compositionSupportDoesNotAttenuateTheController() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = true,
            hasDevice = true,
            deviceTier = DeviceHapticsTier.COMPOSITION,
            decomposition = decomposition
        )

        // Primitive support does not establish equivalent physical compensation.
        assertState(route.controller, low = 0.8f, high = 0.6f)
        // Body compensation is identical regardless of tier.
        assertState(route.device, low = 0.075f, high = 0.46f)
    }

    @Test
    fun compositionTierWithoutTemporalContextYieldsNothing() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = true,
            hasDevice = true,
            deviceTier = DeviceHapticsTier.COMPOSITION
        )

        assertState(route.controller, low = 0.8f, high = 0.6f)
        assertNull(route.device)
    }

    @Test
    fun coordinatedModeWithOnlyDeviceKeepsFullSignalEvenOnBinaryTier() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = false,
            hasDevice = true,
            deviceTier = DeviceHapticsTier.BINARY
        )

        // Without a controller the body is the only sink; PWM full rumble beats silence.
        assertNull(route.controller)
        assertState(route.device, low = 0.8f, high = 0.6f)
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

    @Test
    fun coordinatedDeviceEffectiveGainIsFoldedExactlyOnce() {
        val route = route(
            GameRumbleMode.COORDINATED,
            hasController = true,
            hasDevice = true,
            decomposition = decomposition
        )

        // Policy gains applied once in the router, single-motor fold applied once downstream:
        // 0.25*0.3*0.80 + (1.0*0.4 + 0.30*0.2)*0.33 = 0.06 + 0.1518 = 0.2118 -> 54/255.
        // This pins the end-to-end device gain so no layer can silently re-weight it.
        val target = SingleMotorRumbleFold.amplitude(
            route.device!!.lowFrequency,
            route.device!!.highFrequency
        )
        assertEquals(54, target)
    }

    @Test
    fun singleMotorFoldSaturatesAndAcceptsMotorShorts() {
        assertEquals(204, SingleMotorRumbleFold.amplitude(lowFrequency = 1f, highFrequency = 0f))
        assertEquals(84, SingleMotorRumbleFold.amplitude(lowFrequency = 0f, highFrequency = 1f))
        assertEquals(255, SingleMotorRumbleFold.amplitude(lowFrequency = 1f, highFrequency = 1f))
        assertEquals(
            204,
            SingleMotorRumbleFold.amplitude((-0x0100).toShort(), 0.toShort())
        )
    }

    private fun route(
        mode: GameRumbleMode,
        hasController: Boolean,
        hasDevice: Boolean,
        deviceTier: DeviceHapticsTier = DeviceHapticsTier.AMPLITUDE,
        decomposition: RumbleDecomposition? = null
    ): GameRumbleRoute =
        GameRumbleAllocator.allocate(
            GameRumbleContext(mode, hasController, hasDevice, deviceTier),
            RumbleSignalFeatures(input, decomposition)
        )

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
