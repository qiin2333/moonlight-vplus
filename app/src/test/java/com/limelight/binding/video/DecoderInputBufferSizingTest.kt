package com.limelight.binding.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun extremeBitrateRaisesSingleFrameEstimate() {
        // 800 Mbps @ 120fps on 1080p: worst-case single frame with 2x margin =
        // 800_000 kbps * 1000 / 8 / 120 * 2 = 1_666_666 bytes... still below the
        // resolution estimate for 1080p (2_097_152 minimum), so use a resolution
        // whose estimate the floor actually exceeds. 720p min is 1 MiB:
        // 1_666_666 > 1_048_576, floor wins.
        assertEquals(
            1_666_666,
            DecoderInputBufferSizing.recommendedInputSize("video/av01", 1280, 720, 800_000, 120)
        )
    }

    @Test
    fun moderateBitrateKeepsResolutionEstimate() {
        // 150 Mbps @ 120fps floor = 312_500 < 6_220_800 resolution estimate.
        assertEquals(
            6_220_800,
            DecoderInputBufferSizing.recommendedInputSize("video/av01", 3840, 2160, 150_000, 120)
        )
    }

    @Test
    fun bitrateFloorRespectsFormatMinimum() {
        // 10 Mbps @ 240fps floor = 10_416, below the 2 MiB HEVC minimum.
        assertEquals(
            2_097_152,
            DecoderInputBufferSizing.recommendedInputSize("video/hevc", 1920, 1080, 10_000, 240)
        )
    }

    @Test
    fun automaticModeOnlyOverridesMissingOrUndersizedDefaults() {
        assertEquals(
            2_764_800,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440
            )
        )
        assertEquals(
            2_764_800,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440,
                decoderDefaultSize = 1_048_576
            )
        )
        assertNull(
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.AUTO, "video/av01", 2560, 1440,
                decoderDefaultSize = 4_194_304
            )
        )
    }

    @Test
    fun forcedModesApplyOrDisableExplicitly() {
        assertEquals(
            8_388_608,
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.FORCE_ENABLED, "video/hevc", 2560, 1440,
                decoderDefaultSize = 8_388_608
            )
        )
        assertNull(
            DecoderInputBufferSizing.requestedInputSize(
                DecoderInputBufferMode.FORCE_DISABLED, "video/hevc", 2560, 1440
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

    @Test
    fun resolutionGrowthRequiresARecomputedConfiguration() {
        assertFalse(DecoderInputBufferSizing.requiresReconfiguration(1920, 1080, 1280, 720))
        assertFalse(DecoderInputBufferSizing.requiresReconfiguration(1920, 1080, 1920, 1080))
        assertTrue(DecoderInputBufferSizing.requiresReconfiguration(1920, 1080, 2560, 1080))
        assertTrue(DecoderInputBufferSizing.requiresReconfiguration(1920, 1080, 1920, 1440))
    }
}
