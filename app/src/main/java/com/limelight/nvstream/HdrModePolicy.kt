package com.limelight.nvstream

import com.limelight.nvstream.jni.MoonBridge

/**
 * Keeps client-only HDR selections out of the 0/1/2 host and framegen protocols.
 * Dynamic selections reuse their PQ or HLG base layer; the metadata type is
 * negotiated independently of the base transfer.
 */
internal object HdrModePolicy {
    fun isHdr10PlusMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_HDR10_PLUS

    fun isDolbyVisionMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_DOLBY_VISION ||
            hdrMode == MoonBridge.HDR_MODE_DOLBY_VISION_84

    fun isDolbyVisionHlgMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_DOLBY_VISION_84

    fun isPqMode(hdrMode: Int): Boolean =
        hdrMode == MoonBridge.HDR_MODE_HDR10 || isHdr10PlusMode(hdrMode) ||
            hdrMode == MoonBridge.HDR_MODE_DOLBY_VISION

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
     * Both the 8.1 (PQ base) and 8.4 (HLG base) selections share it.
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
        // 8.4 rides the HLG base layer; everything else Dolby/HDR10+ is PQ.
        isDolbyVisionHlgMode(hdrMode) || hdrMode == MoonBridge.HDR_MODE_VIVID_HLG -> MoonBridge.HDR_MODE_HLG
        hdrMode == MoonBridge.HDR_MODE_VIVID_PQ -> MoonBridge.HDR_MODE_HDR10
        isPqMode(hdrMode) -> MoonBridge.HDR_MODE_HDR10
        hdrMode == MoonBridge.HDR_MODE_HLG -> MoonBridge.HDR_MODE_HLG
        else -> MoonBridge.HDR_MODE_SDR
    }
}

/** PyroWave consumes frame metadata in Vulkan, independently of native MediaCodec profiles. */
internal object PyrowaveDynamicHdrPolicy {
    fun formatForSelection(mode: Int): Int = when (mode) {
        MoonBridge.HDR_MODE_HDR10_PLUS -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_HDR10_PLUS
        MoonBridge.HDR_MODE_VIVID_PQ -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_VIVID_PQ
        MoonBridge.HDR_MODE_VIVID_HLG -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_VIVID_HLG
        MoonBridge.HDR_MODE_DOLBY_VISION -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_81
        MoonBridge.HDR_MODE_DOLBY_VISION_84 -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_DOLBY_VISION_PROFILE_84
        else -> MoonBridge.NEGOTIATED_DYNAMIC_HDR_NONE
    }

    fun capsForSelection(mode: Int): Int = when (mode) {
        MoonBridge.HDR_MODE_HDR10_PLUS -> MoonBridge.DYNAMIC_HDR_CAPS_HDR10_PLUS
        MoonBridge.HDR_MODE_VIVID_PQ -> MoonBridge.DYNAMIC_HDR_CAPS_VIVID_PQ
        MoonBridge.HDR_MODE_VIVID_HLG -> MoonBridge.DYNAMIC_HDR_CAPS_VIVID_HLG
        MoonBridge.HDR_MODE_DOLBY_VISION -> MoonBridge.DYNAMIC_HDR_CAPS_DOLBY_VISION_81
        MoonBridge.HDR_MODE_DOLBY_VISION_84 -> MoonBridge.DYNAMIC_HDR_CAPS_DOLBY_VISION_84
        else -> MoonBridge.DYNAMIC_HDR_CAPS_NONE
    }

    fun preferenceForSelection(mode: Int): Int = when (mode) {
        MoonBridge.HDR_MODE_HDR10_PLUS -> MoonBridge.DYNAMIC_HDR_PREFERENCE_HDR10_PLUS
        MoonBridge.HDR_MODE_DOLBY_VISION, MoonBridge.HDR_MODE_DOLBY_VISION_84 ->
            MoonBridge.DYNAMIC_HDR_PREFERENCE_DOLBY_VISION
        else -> MoonBridge.DYNAMIC_HDR_PREFERENCE_AUTOMATIC
    }
}
