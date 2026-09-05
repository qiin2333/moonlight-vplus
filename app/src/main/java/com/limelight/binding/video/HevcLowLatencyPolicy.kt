package com.limelight.binding.video

import com.limelight.preferences.PreferenceConfiguration

/**
 * Decides whether MediaCodec low-latency options must be skipped for a stream.
 *
 * Amlogic C2 HEVC decoders stall the display pipeline when low-latency mode is
 * active (issue #499, moonlight-android#1584): the codec reports 60 FPS while
 * the surface refreshes around once per second, and configure() succeeds, so
 * the caller's option-sweep retry never triggers. Skipping low-latency options
 * may increase latency; the tradeoff depends on the decoder and firmware.
 *
 * AUTO skips for every Amlogic HEVC decoder; OFF skips for all HEVC/Dolby
 * Vision streams; ON skips nothing (previous behavior). H.264 and AV1 are
 * never touched. Realtime priority is an independent scheduling hint: the
 * compatibility path tries it on API 23+, then retries without optional tuning.
 * HEVC reference-frame invalidation remains governed by its existing policy.
 */
object HevcLowLatencyPolicy {

    private const val MIME_HEVC = "video/hevc"
    private const val MIME_DOLBY_VISION = "video/dolby-vision"

    // Mirrors MediaCodecHelper.amlogicDecoderPrefixes (kept private there and
    // gated behind native decoder enumeration, so it cannot be shared with
    // this JVM-testable policy).
    private val amlogicDecoderPrefixes = listOf("omx.amlogic", "c2.amlogic")

    fun shouldSkipLowLatencyOptions(
        mimeType: String?,
        decoderName: String,
        hevcLowLatencyMode: Int,
    ): Boolean {
        if (mimeType != MIME_HEVC && mimeType != MIME_DOLBY_VISION) return false
        return when (hevcLowLatencyMode) {
            PreferenceConfiguration.HEVC_LOW_LATENCY_OFF -> true
            PreferenceConfiguration.HEVC_LOW_LATENCY_ON -> false
            else -> mimeType == MIME_HEVC && isAmlogicDecoder(decoderName)
        }
    }

    private fun isAmlogicDecoder(decoderName: String): Boolean =
        amlogicDecoderPrefixes.any { prefix ->
            decoderName.length >= prefix.length &&
                decoderName.substring(0, prefix.length).equals(prefix, ignoreCase = true)
        }
}
