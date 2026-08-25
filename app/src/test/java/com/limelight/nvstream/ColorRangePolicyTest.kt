package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorRangePolicyTest {
    @Test
    fun defaultPreferenceKeepsEveryTransferModeLimited() {
        val colorRange = ColorRangePolicy.fromPreference(fullRange = false)

        assertEquals(MoonBridge.COLOR_RANGE_LIMITED, colorRange)
        assertFalse(ColorRangePolicy.isFullRangeHdr(MoonBridge.HDR_MODE_HDR10, colorRange))
        assertFalse(ColorRangePolicy.isFullRangeHdr(MoonBridge.HDR_MODE_HLG, colorRange))
    }

    @Test
    fun explicitPreferenceKeepsEveryTransferModeFull() {
        val colorRange = ColorRangePolicy.fromPreference(fullRange = true)

        assertEquals(MoonBridge.COLOR_RANGE_FULL, colorRange)
        assertTrue(ColorRangePolicy.isFullRangeHdr(MoonBridge.HDR_MODE_HDR10, colorRange))
        assertTrue(ColorRangePolicy.isFullRangeHdr(MoonBridge.HDR_MODE_HLG, colorRange))
        assertFalse(ColorRangePolicy.isFullRangeHdr(MoonBridge.HDR_MODE_SDR, colorRange))
    }

    @Test
    fun hlgDataSpaceFollowsTheRequestedRange() {
        assertEquals(
            MoonBridge.DATASPACE_BT2020_HLG_LIMITED,
            ColorRangePolicy.hdrDataSpace(
                MoonBridge.HDR_MODE_HLG,
                MoonBridge.COLOR_RANGE_LIMITED,
            ),
        )
        assertEquals(
            MoonBridge.DATASPACE_BT2020_HLG_FULL,
            ColorRangePolicy.hdrDataSpace(
                MoonBridge.HDR_MODE_HLG,
                MoonBridge.COLOR_RANGE_FULL,
            ),
        )
    }

    @Test
    fun pqDataSpaceFollowsTheRequestedRange() {
        assertEquals(
            MoonBridge.DATASPACE_BT2020_PQ_LIMITED,
            ColorRangePolicy.hdrDataSpace(
                MoonBridge.HDR_MODE_HDR10,
                MoonBridge.COLOR_RANGE_LIMITED,
            ),
        )
        assertEquals(
            MoonBridge.DATASPACE_BT2020_PQ_FULL,
            ColorRangePolicy.hdrDataSpace(
                MoonBridge.HDR_MODE_HDR10,
                MoonBridge.COLOR_RANGE_FULL,
            ),
        )
    }
}
