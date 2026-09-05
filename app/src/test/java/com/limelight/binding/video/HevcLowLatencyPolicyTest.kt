package com.limelight.binding.video

import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcLowLatencyPolicyTest {

    @Test
    fun unrecognizedModeUsesAutomaticCompatibilityWithoutAffectingOtherCodecs() {
        assertTrue(HevcLowLatencyPolicy.shouldSkipLowLatencyOptions("video/hevc", "c2.amlogic.hevc.decoder", -1))
        assertFalse(HevcLowLatencyPolicy.shouldSkipLowLatencyOptions("video/av01", "c2.amlogic.av1.decoder", -1))
        assertFalse(HevcLowLatencyPolicy.shouldSkipLowLatencyOptions("video/hevc", "c2.qti.hevc.decoder", -1))
    }

    @Test
    fun autoSkipsAmlogicHevcDecoders() {
        assertTrue(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "c2.amlogic.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
        assertTrue(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "OMX.amlogic.hevc.awesome.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
    }

    @Test
    fun autoKeepsLowLatencyForNonAmlogicHevcDecoders() {
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "c2.mtk.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "c2.qti.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
    }

    @Test
    fun autoNeverSkipsDolbyVisionOrOtherCodecs() {
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/dolby-vision",
                "c2.amlogic.dv.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/avc",
                "c2.amlogic.avc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/av01",
                "c2.amlogic.av01.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_AUTO,
            )
        )
    }

    @Test
    fun forcedOnSkipsNothingEvenOnAmlogicHevc() {
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "c2.amlogic.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_ON,
            )
        )
    }

    @Test
    fun forcedOffSkipsAllHevcAndDolbyVisionButNotOtherCodecs() {
        assertTrue(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/hevc",
                "c2.qti.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_OFF,
            )
        )
        assertTrue(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/dolby-vision",
                "c2.qti.dv.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_OFF,
            )
        )
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                "video/avc",
                "c2.amlogic.avc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_OFF,
            )
        )
    }

    @Test
    fun nullMimeTypeIsNeverSkipped() {
        assertFalse(
            HevcLowLatencyPolicy.shouldSkipLowLatencyOptions(
                null,
                "c2.amlogic.hevc.decoder",
                PreferenceConfiguration.HEVC_LOW_LATENCY_OFF,
            )
        )
    }
}
