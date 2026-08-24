package com.limelight.binding.video

import android.annotation.SuppressLint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.limelight.LimeLog

/**
 * Probes the device's native Dolby Vision decode path for the Sunshine
 * dynamic HDR negotiation: a video/dolby-vision decoder advertising the
 * DvheSt profile (Dolby Vision Profile 8 — deliberately NOT DvheDtb, which
 * is Profile 7) that accepts the stream's dimensions and frame rate.
 *
 * Display-side capability comes separately via HdrCapabilityHelper; both
 * gates must pass before the client reports the DV capability bit. This
 * mirrors HdrDecoderProfileSelector's strict resolution/fps/profile probing:
 * a generic HEVC decoder handling 4K120 says nothing about the Dolby Vision
 * path at that size.
 */
internal class DolbyVisionCapabilityProbe(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
) {
    class Result {
        var decoderAvailable = false
        var decoderName: String? = null
        var maxLevel = 0
    }

    companion object {
        private const val MIME_DOLBY_VISION = "video/dolby-vision"

        @SuppressLint("InlinedApi")
        private const val DV_PROFILE_DVHE_ST =
            android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt
    }

    fun probe(): Result {
        val result = Result()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return result
        }

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (decoderInfo in codecList.codecInfos) {
            if (decoderInfo.isEncoder) {
                continue
            }

            val capabilities = try {
                decoderInfo.getCapabilitiesForType(MIME_DOLBY_VISION)
            } catch (e: IllegalArgumentException) {
                continue  // not a video/dolby-vision decoder
            } catch (e: RuntimeException) {
                LimeLog.warning("Failed to query ${decoderInfo.name} Dolby Vision capabilities: ${e.message}")
                continue
            }

            var level = 0
            var hasDvheSt = false
            for (profileLevel in capabilities.profileLevels) {
                if (profileLevel.profile == DV_PROFILE_DVHE_ST) {
                    hasDvheSt = true
                    if (profileLevel.level > level) {
                        level = profileLevel.level
                    }
                }
            }
            if (!hasDvheSt) {
                continue
            }

            val probeFormat = MediaFormat.createVideoFormat(MIME_DOLBY_VISION, width, height)
            probeFormat.setInteger(MediaFormat.KEY_PROFILE, DV_PROFILE_DVHE_ST)
            probeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            probeFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            probeFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            probeFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)

            val supported = try {
                capabilities.isFormatSupported(probeFormat)
            } catch (e: RuntimeException) {
                LimeLog.warning("Dolby Vision format probe failed for ${decoderInfo.name}: ${e.message}")
                false
            }

            if (supported) {
                result.decoderAvailable = true
                result.decoderName = decoderInfo.name
                result.maxLevel = level
                LimeLog.info(
                    "${decoderInfo.name} supports Dolby Vision DvheSt (level $level) " +
                        "at ${width}x${height}@${frameRate}"
                )
                break
            } else {
                LimeLog.warning(
                    "${decoderInfo.name} advertises Dolby Vision DvheSt but rejects " +
                        "${width}x${height}@${frameRate}"
                )
            }
        }

        return result
    }
}
