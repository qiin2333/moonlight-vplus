package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class PyrowaveDynamicHdrPolicyTest {
    @Test
    fun dynamicModesUseOnlyTheirBaseTransferAndRequestedMetadataCapability() {
        val modes = listOf(
            Triple(MoonBridge.HDR_MODE_HDR10_PLUS, 1, 1),
            Triple(MoonBridge.HDR_MODE_VIVID_PQ, 1, 2),
            Triple(MoonBridge.HDR_MODE_VIVID_HLG, 2, 3),
            Triple(MoonBridge.HDR_MODE_DOLBY_VISION, 1, 4),
            Triple(MoonBridge.HDR_MODE_DOLBY_VISION_84, 2, 5),
        )
        for ((mode, base, format) in modes) {
            assertEquals(base, HdrModePolicy.toProtocolMode(mode))
            assertEquals(format, PyrowaveDynamicHdrPolicy.formatForSelection(mode))
            assertEquals(1 shl (format - 1), PyrowaveDynamicHdrPolicy.capsForSelection(mode))
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
