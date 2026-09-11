package com.limelight.binding.input.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHapticsCapabilitiesTest {
    private fun capabilities(
        hasVibrator: Boolean = true,
        hasAmplitudeControl: Boolean = true,
        supportsClickPrimitive: Boolean = false
    ) = DeviceHapticsCapabilities(
        hasVibrator = hasVibrator,
        hasAmplitudeControl = hasAmplitudeControl,
        supportsClickPrimitive = supportsClickPrimitive,
        resonantFrequencyHz = null,
        qFactor = null
    )

    @Test
    fun missingVibratorIsTierNone() {
        assertEquals(DeviceHapticsTier.NONE, capabilities(hasVibrator = false).tier)
    }

    @Test
    fun vibratorWithoutAmplitudeControlIsTierBinary() {
        assertEquals(DeviceHapticsTier.BINARY, capabilities(hasAmplitudeControl = false).tier)
    }

    @Test
    fun amplitudeControlWithoutCompositionIsTierAmplitude() {
        assertEquals(DeviceHapticsTier.AMPLITUDE, capabilities().tier)
    }

    @Test
    fun clickPrimitiveUpgradesToTierComposition() {
        assertEquals(
            DeviceHapticsTier.COMPOSITION,
            capabilities(supportsClickPrimitive = true).tier
        )
    }

    @Test
    fun unconfirmedCapabilitiesNeverUpgradeTheTier() {
        // Click primitive unconfirmed (false): stay conservative even though the field exists.
        assertEquals(DeviceHapticsTier.AMPLITUDE, capabilities().tier)
    }
}
