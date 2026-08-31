package com.limelight.binding.video

/**
 * Dolby Vision decoders are terminal display pipelines rather than ordinary
 * HEVC decoders. The codec and its vendor compositor own the output buffer
 * metadata, including the Surface dataspace. Applying Moonlight's generic
 * low-latency tuning or replacing that dataspace can make otherwise valid
 * decoded buffers incompatible with the output Surface.
 */
internal object DolbyVisionCodecPolicy {
    const val MIME_TYPE = "video/dolby-vision"

    fun shouldApplyLowLatencyOptions(mimeType: String): Boolean =
        mimeType != MIME_TYPE

    fun shouldForceSurfaceDataSpace(mimeType: String): Boolean =
        mimeType != MIME_TYPE
}
