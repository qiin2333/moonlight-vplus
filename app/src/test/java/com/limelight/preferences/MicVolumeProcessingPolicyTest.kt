package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class MicVolumeProcessingPolicyTest {
    @Test
    fun modesMapToMutuallyExclusiveLegacyFlags() {
        assertEquals(
            MicVolumeProcessingPolicy.Flags(false, false, false),
            MicVolumeProcessingPolicy.flagsFor(MicVolumeProcessingPolicy.OFF),
        )
        assertEquals(
            MicVolumeProcessingPolicy.Flags(true, true, false),
            MicVolumeProcessingPolicy.flagsFor(MicVolumeProcessingPolicy.GAIN),
        )
        assertEquals(
            MicVolumeProcessingPolicy.Flags(true, false, true),
            MicVolumeProcessingPolicy.flagsFor(MicVolumeProcessingPolicy.BALANCE),
        )
    }

    @Test
    fun legacyMigrationMatchesRuntimePrecedence() {
        assertEquals(
            MicVolumeProcessingPolicy.OFF,
            MicVolumeProcessingPolicy.modeFor(processing = false, gain = true, balance = true),
        )
        assertEquals(
            MicVolumeProcessingPolicy.GAIN,
            MicVolumeProcessingPolicy.modeFor(processing = true, gain = true, balance = true),
        )
        assertEquals(
            MicVolumeProcessingPolicy.BALANCE,
            MicVolumeProcessingPolicy.modeFor(processing = true, gain = false, balance = true),
        )
        assertEquals(
            MicVolumeProcessingPolicy.LEGACY_PROCESSING_ONLY,
            MicVolumeProcessingPolicy.modeFor(processing = true, gain = false, balance = false),
        )
    }
}
