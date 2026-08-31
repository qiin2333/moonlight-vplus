package com.limelight.binding.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionCodecPolicyTest {
    @Test
    fun `dolby vision leaves low latency and dataspace to codec`() {
        assertFalse(
            DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions(
                DolbyVisionCodecPolicy.MIME_TYPE,
            ),
        )
        assertFalse(
            DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace(
                DolbyVisionCodecPolicy.MIME_TYPE,
            ),
        )
    }

    @Test
    fun `ordinary hdr codecs retain moonlight tuning`() {
        assertTrue(DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions("video/hevc"))
        assertTrue(DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace("video/hevc"))
        assertTrue(DolbyVisionCodecPolicy.shouldApplyLowLatencyOptions("video/av01"))
        assertTrue(DolbyVisionCodecPolicy.shouldForceSurfaceDataSpace("video/av01"))
    }
}
