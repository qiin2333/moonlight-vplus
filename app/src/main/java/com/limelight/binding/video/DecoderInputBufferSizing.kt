package com.limelight.binding.video

internal enum class DecoderInputBufferMode(val preferenceValue: String) {
    AUTO("auto"),
    FORCE_ENABLED("force_enabled"),
    FORCE_DISABLED("force_disabled");

    companion object {
        fun fromPreferenceValue(value: String?): DecoderInputBufferMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AUTO
    }
}

internal object DecoderInputBufferSizing {
    private const val MIME_AVC = "video/avc"
    private const val MIME_AV1 = "video/av01"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_DOLBY_VISION = "video/dolby-vision"
    private const val MIN_AV1_INPUT_SIZE = 1024 * 1024
    private const val MIN_HEVC_INPUT_SIZE = 2 * 1024 * 1024
    private const val MIN_COMPRESSION_RATIO = 2L

    fun recommendedInputSize(mimeType: String, width: Int, height: Int): Int? {
        val minimumSize = when (mimeType) {
            MIME_AVC, MIME_AV1 -> MIN_AV1_INPUT_SIZE
            MIME_HEVC, MIME_DOLBY_VISION -> MIN_HEVC_INPUT_SIZE
            else -> return null
        }
        if (width <= 0 || height <= 0) return null

        val pixelCount = if (mimeType == MIME_AVC) {
            val alignedWidth = (width.toLong() + 15L) / 16L * 16L
            val alignedHeight = (height.toLong() + 15L) / 16L * 16L
            alignedWidth * alignedHeight
        } else {
            width.toLong() * height
        }
        val inferredSize = pixelCount * 3L / (2L * MIN_COMPRESSION_RATIO)
        return inferredSize.coerceIn(minimumSize.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun requestedInputSize(
        mode: DecoderInputBufferMode,
        mimeType: String,
        width: Int,
        height: Int,
        decoderDefaultSize: Int?,
    ): Int? {
        if (mode == DecoderInputBufferMode.FORCE_DISABLED) return null
        val recommended = recommendedInputSize(mimeType, width, height) ?: return null
        return when (mode) {
            DecoderInputBufferMode.AUTO ->
                if (decoderDefaultSize != null && decoderDefaultSize >= recommended) null else recommended
            DecoderInputBufferMode.FORCE_ENABLED -> maxOf(recommended, decoderDefaultSize ?: 0)
            DecoderInputBufferMode.FORCE_DISABLED -> null
        }
    }

    fun overrideAttempts(mode: DecoderInputBufferMode, requestedSize: Int?): BooleanArray =
        if (mode == DecoderInputBufferMode.AUTO && requestedSize != null) {
            booleanArrayOf(true, false)
        } else {
            booleanArrayOf(true)
        }
}
