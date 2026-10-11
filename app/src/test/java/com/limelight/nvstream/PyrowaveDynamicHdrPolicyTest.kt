package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class PyrowaveDynamicHdrPolicyTest {
    @Test
    fun dynamicModesUseOnlyTheirBaseTransferAndRequestedMetadataCapability() {
        val modes = listOf(
            Triple(MoonBridge.HDR_MODE_HDR10_PLUS, 1, MoonBridge.NEGOTIATED_DYNAMIC_HDR_HDR10_PLUS),
            Triple(MoonBridge.HDR_MODE_VIVID_PQ, 1, 2),
            Triple(MoonBridge.HDR_MODE_VIVID_HLG, 2, 3),
            Triple(MoonBridge.HDR_MODE_DOLBY_VISION, 1, MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_81),
            Triple(MoonBridge.HDR_MODE_DOLBY_VISION_84, 2, MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_84),
        )
        for ((mode, base, format) in modes) {
            assertEquals(base, HdrModePolicy.toProtocolMode(mode))
            assertEquals(format, PyrowaveDynamicHdrPolicy.formatForSelection(mode))
            val expectedCaps = when (format) {
                MoonBridge.NEGOTIATED_DYNAMIC_HDR_HDR10_PLUS -> MoonBridge.DYNAMIC_HDR_CAPS_HDR10_PLUS
                MoonBridge.NEGOTIATED_DYNAMIC_HDR_VIVID_PQ -> MoonBridge.DYNAMIC_HDR_CAPS_VIVID_PQ
                MoonBridge.NEGOTIATED_DYNAMIC_HDR_VIVID_HLG -> MoonBridge.DYNAMIC_HDR_CAPS_VIVID_HLG
                MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_81 -> MoonBridge.DYNAMIC_HDR_CAPS_DOLBY_VISION_81
                MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_84 -> MoonBridge.DYNAMIC_HDR_CAPS_DOLBY_VISION_84
                else -> error("unexpected dynamic HDR format")
            }
            assertEquals(expectedCaps, PyrowaveDynamicHdrPolicy.capsForSelection(mode))
        }
    }

    @Test
    fun staticModesDoNotAdvertiseDynamicMetadata() {
        for (mode in listOf(0, 1, 2, 99)) {
            assertEquals(0, PyrowaveDynamicHdrPolicy.formatForSelection(mode))
            assertEquals(0, PyrowaveDynamicHdrPolicy.capsForSelection(mode))
        }
    }
}
