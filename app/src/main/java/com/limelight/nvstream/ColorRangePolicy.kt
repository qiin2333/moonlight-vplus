package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge

/**
 * Keeps the host request, decoder configuration, and output Surface on the
 * same video quantization range. HDR transfer functions do not imply a range:
 * HLG and PQ both follow the user's explicit full-range preference.
 */
internal object ColorRangePolicy {
    fun fromPreference(fullRange: Boolean): Int =
        if (fullRange) MoonBridge.COLOR_RANGE_FULL else MoonBridge.COLOR_RANGE_LIMITED

    fun isFullRange(colorRange: Int): Boolean =
        colorRange == MoonBridge.COLOR_RANGE_FULL

    fun isFullRangeHdr(hdrMode: Int, colorRange: Int): Boolean =
        hdrMode != MoonBridge.HDR_MODE_SDR && isFullRange(colorRange)

    fun hdrDataSpace(hdrMode: Int, colorRange: Int): Int {
        val fullRange = isFullRange(colorRange)
        return if (hdrMode == MoonBridge.HDR_MODE_HLG) {
            if (fullRange) {
                MoonBridge.DATASPACE_BT2020_HLG_FULL
            } else {
                MoonBridge.DATASPACE_BT2020_HLG_LIMITED
            }
        } else {
            if (fullRange) {
                MoonBridge.DATASPACE_BT2020_PQ_FULL
            } else {
                MoonBridge.DATASPACE_BT2020_PQ_LIMITED
            }
        }
    }
}
