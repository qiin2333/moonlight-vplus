package com.limelight.binding.video

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecDecoderRendererTest {
    @Test
    fun `qualcomm omx decoder uses capture surface from configure`() {
        assertFalse(
            MediaCodecDecoderRenderer.supportsDelayedFramegenSurfaceSwitch(
                "OMX.qcom.video.decoder.hevc",
                Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    @Test
    fun `qualcomm codec2 decoder uses capture surface from configure`() {
        assertFalse(
            MediaCodecDecoderRenderer.supportsDelayedFramegenSurfaceSwitch(
                "c2.qti.hevc.decoder",
                Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    @Test
    fun `non qualcomm decoder keeps delayed capture switch`() {
        assertTrue(
            MediaCodecDecoderRenderer.supportsDelayedFramegenSurfaceSwitch(
                "OMX.Nvidia.hevc.decode",
                Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    @Test
    fun `pre marshmallow decoder cannot switch output surface`() {
        assertFalse(
            MediaCodecDecoderRenderer.supportsDelayedFramegenSurfaceSwitch(
                "OMX.Nvidia.hevc.decode",
                Build.VERSION_CODES.LOLLIPOP_MR1
            )
        )
    }
}
