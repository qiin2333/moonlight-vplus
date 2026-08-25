package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge

/**
 * Keeps client-only HDR selections out of the 0/1/2 host and framegen protocols.
 * Dolby Vision rides the same HDR10/PQ base layer as HDR10+; only the dynamic
 * metadata format negotiated with the host differs.
 */
internal object HdrModePolicy {
    fun isHdr10PlusMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_HDR10_PLUS

    fun isDolbyVisionMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_DOLBY_VISION

    fun isPqMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_HDR10 || isHdr10PlusMode(hdrMode) || isDolbyVisionMode(hdrMode)

    fun shouldRequestHdr10Plus(
        hdrEnabled: Boolean,
        hdrMode: Int,
        displaySupportsHdr10Plus: Boolean,
        framegenRequested: Boolean,
    ): Boolean = hdrEnabled &&
        isHdr10PlusMode(hdrMode) &&
        displaySupportsHdr10Plus &&
        !framegenRequested

    /**
     * Dolby Vision additionally needs the device's native decode path: a
     * video/dolby-vision decoder advertising the DvheSt profile at the
     * stream's dimensions, and a display that reports Dolby Vision support.
     */
    fun shouldRequestDolbyVision(
        hdrEnabled: Boolean,
        hdrMode: Int,
        displaySupportsDolbyVision: Boolean,
        decoderSupportsDolbyVision: Boolean,
        framegenRequested: Boolean,
    ): Boolean = hdrEnabled &&
        isDolbyVisionMode(hdrMode) &&
        displaySupportsDolbyVision &&
        decoderSupportsDolbyVision &&
        !framegenRequested

    fun toProtocolMode(hdrMode: Int): Int = when {
        isPqMode(hdrMode) -> MoonBridge.HDR_MODE_HDR10
        hdrMode == MoonBridge.HDR_MODE_HLG -> MoonBridge.HDR_MODE_HLG
        else -> MoonBridge.HDR_MODE_SDR
    }
}
