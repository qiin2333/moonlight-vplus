package com.limelight.binding.video

import android.media.MediaCodecInfo

/**
 * Dolby Vision decoders are terminal display pipelines rather than ordinary
 * HEVC decoders. The codec and its vendor compositor own the output buffer
 * metadata, including the Surface dataspace. Applying Moonlight's generic
 * low-latency tuning or replacing that dataspace can make otherwise valid
 * decoded buffers incompatible with the output Surface.
 */
internal object DolbyVisionCodecPolicy {
    const val MIME_TYPE = "video/dolby-vision"

    private const val CONFIGURATION_RECORD_SIZE = 24
    private const val PROFILE_8 = 8
    private const val PROFILE_8_1_COMPATIBILITY_ID = 1

    data class SignalLevel(
        val recordValue: Int,
        val codecValue: Int,
        val label: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFrameRate: Int,
    )

    private val signalLevels = listOf(
        SignalLevel(1, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24, "HD24", 1280, 720, 24),
        SignalLevel(2, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30, "HD30", 1280, 720, 30),
        SignalLevel(3, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24, "FHD24", 1920, 1080, 24),
        SignalLevel(4, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30, "FHD30", 1920, 1080, 30),
        SignalLevel(5, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60, "FHD60", 1920, 1080, 60),
        SignalLevel(6, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24, "UHD24", 3840, 2160, 24),
        SignalLevel(7, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30, "UHD30", 3840, 2160, 30),
        SignalLevel(8, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48, "UHD48", 3840, 2160, 48),
        SignalLevel(9, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60, "UHD60", 3840, 2160, 60),
        SignalLevel(10, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd120, "UHD120", 3840, 2160, 120),
        SignalLevel(11, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k30, "8K30", 7680, 4320, 30),
        SignalLevel(12, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60, "8K60", 7680, 4320, 60),
    )

    fun shouldApplyLowLatencyOptions(mimeType: String): Boolean =
        mimeType != MIME_TYPE

    fun shouldForceSurfaceDataSpace(mimeType: String): Boolean =
        mimeType != MIME_TYPE

    fun shouldAttachHdrStaticInfo(mimeType: String): Boolean =
        mimeType != MIME_TYPE

    /** Selects the smallest standardized Dolby Vision level covering the stream. */
    fun selectSignalLevel(width: Int, height: Int, frameRate: Int): SignalLevel {
        val normalizedWidth = width.coerceAtLeast(1)
        val normalizedHeight = height.coerceAtLeast(1)
        val normalizedFrameRate = frameRate.coerceAtLeast(1)
        return signalLevels.firstOrNull {
            normalizedWidth <= it.maxWidth &&
                normalizedHeight <= it.maxHeight &&
                normalizedFrameRate <= it.maxFrameRate
        } ?: signalLevels.last()
    }

    /** Builds the 24-byte dvvC record expected in MediaFormat csd-2. */
    fun buildProfile81ConfigurationRecord(level: SignalLevel): ByteArray =
        ByteArray(CONFIGURATION_RECORD_SIZE).apply {
            this[0] = 1 // dv_version_major
            this[1] = 0 // dv_version_minor
            this[2] = ((PROFILE_8 shl 1) or ((level.recordValue shr 5) and 0x1)).toByte()
            this[3] = (
                ((level.recordValue and 0x1F) shl 3) or
                    (1 shl 2) or // rpu_present_flag
                    1 // bl_present_flag; el_present_flag remains zero
                ).toByte()
            this[4] = (PROFILE_8_1_COMPATIBILITY_ID shl 4).toByte()
        }
}
