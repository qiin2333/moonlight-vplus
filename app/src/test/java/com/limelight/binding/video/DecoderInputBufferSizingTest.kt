package com.limelight.binding.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderInputBufferSizingTest {
    @Test
    fun av1UsesResolutionBasedMaximumInputSize() {
        assertEquals(2_764_800, DecoderInputBufferSizing.recommendedInputSize("video/av01", 2560, 1440))
        assertEquals(6_220_800, DecoderInputBufferSizing.recommendedInputSize("video/av01", 3840, 2160))
    }

    @Test
    fun av1NeverRequestsLessThanPlatformVideoDefault() {
        assertEquals(1_048_576, DecoderInputBufferSizing.recommendedInputSize("video/av01", 1280, 720))
    }

    @Test
    fun hevcAndDolbyVisionUseHevcMinimumAndResolutionSizing() {
        assertEquals(2_097_152, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080))
        assertEquals(2_764_800, DecoderInputBufferSizing.recommendedInputSize("video/hevc", 2560, 1440))
        assertEquals(6_220_800, DecoderInputBufferSizing.recommendedInputSize("video/dolby-vision", 3840, 2160))
    }

    @Test
    fun avcUsesMacroblockAlignedResolutionSizing() {
        assertEquals(1_579_776, DecoderInputBufferSizing.recommendedInputSize("video/avc", 1921, 1081))
    }

    @Test
    fun otherFormatsAndInvalidDimensionsKeepCodecDefaults() {
        assertNull(DecoderInputBufferSizing.recommendedInputSize("video/x-vnd.on2.vp9", 2560, 1440))
        assertNull(DecoderInputBufferSizing.recommendedInputSize("video/av01", 0, 1440))
        assertNull(DecoderInputBufferSizing.recommendedInputSize("video/hevc", 2560, -1))
    }

    @Test
    fun largeDimensionsAreCalculatedWithoutIntegerOverflow() {
        assertEquals(50_331_648, DecoderInputBufferSizing.recommendedInputSize("video/av01", 8192, 8192))
    }

    @Test
    fun automaticModeOnlyOverridesMissingOrUndersizedDefaults() {
        assertEquals(
            2_764_800,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440, null
            )
        )
        assertEquals(
            2_764_800,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440, 1_048_576
            )
        )
        assertNull(
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440, 4_194_304
            )
        )
    }

    @Test
    fun forcedModesApplyOrDisableExplicitly() {
        assertEquals(
            8_388_608,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.FORCE_ENABLED, "video/hevc", 2560, 1440, 8_388_608
            )
        )
        assertNull(
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.FORCE_DISABLED, "video/hevc", 2560, 1440, null
            )
        )
    }

    @Test
    fun unknownStoredModeFallsBackToAutomatic() {
        assertEquals(DecoderInputBufferMode.AUTO, DecoderInputBufferMode.fromPreferenceValue("future"))
    }

    @Test
    fun onlyAutomaticOverridesReceiveACompatibilityRetry() {
        assertArrayEquals(
            booleanArrayOf(true, false),
            DecoderInputBufferSizing.overrideAttempts(DecoderInputBufferMode.AUTO, 2_764_800),
        )
        assertArrayEquals(
            booleanArrayOf(true),
            DecoderInputBufferSizing.overrideAttempts(DecoderInputBufferMode.AUTO, null),
        )
        assertArrayEquals(
            booleanArrayOf(true),
            DecoderInputBufferSizing.overrideAttempts(DecoderInputBufferMode.FORCE_ENABLED, 2_764_800),
        )
    }
}
