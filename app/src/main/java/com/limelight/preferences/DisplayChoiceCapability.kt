package com.limelight.preferences

import android.content.Context
import android.os.Build
import android.view.Display
import com.limelight.binding.video.MediaCodecHelper
import kotlin.math.max
import kotlin.math.min

/** 设备对预设分辨率和帧率的支持范围。自定义列表本身不受这里影响。 */
internal data class DisplayChoiceLimits(
    val maxResolutionWidth: Int,
    val maxRefreshRate: Float
) {
    fun supportsResolution(value: String): Boolean {
        val resolution = ResolutionValidator.parseResolution(value) ?: return true
        if (resolution.width <= 0 || resolution.height <= 0) return false
        // Display modes and decoder width ranges are only a preset heuristic. A valid custom
        // size can still work (including rotated or wide modes), so let the user try it.
        if (value !in PreferenceConfiguration.RESOLUTIONS) return true
        return maxResolutionWidth == 0 ||
            max(resolution.width, resolution.height) <= maxResolutionWidth ||
            resolution.width == 1280 && resolution.height == 720
    }

    fun supportsFrameRate(value: String, unlockFps: Boolean): Boolean {
        val fps = value.toIntOrNull() ?: return true
        return unlockFps || fps <= 60 || fps.toFloat() <= maxRefreshRate + 3f
    }

    companion object {
        fun from(context: Context, display: Display): DisplayChoiceLimits {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return DisplayChoiceLimits(0, 60f)
            }
            var maxWidth = 0
            var maxFps = 60f
            for (mode in display.supportedModes) {
                if (mode.refreshRate > maxFps) maxFps = mode.refreshRate
                val width = max(mode.physicalWidth, mode.physicalHeight)
                val height = min(mode.physicalWidth, mode.physicalHeight)
                if (width >= 3840 || height >= 2160) {
                    if (maxWidth < 3840) maxWidth = 3840
                } else if (width >= 2560 || height >= 1440) {
                    if (maxWidth < 2560) maxWidth = 2560
                } else if (width >= 1920 || height >= 1080) {
                    if (maxWidth < 1920) maxWidth = 1920
                }
            }
            MediaCodecHelper.initialize(context, GlPreferences.readPreferences(context).glRenderer)
            maxWidth = max(maxWidth, decoderResolutionWidth("video/avc"))
            maxWidth = max(maxWidth, decoderResolutionWidth("video/hevc"))
            return DisplayChoiceLimits(maxWidth, maxFps)
        }

        private fun decoderResolutionWidth(mimeType: String): Int {
            val decoder = MediaCodecHelper.findProbableSafeDecoder(mimeType, -1) ?: return 0
            val widths = decoder.getCapabilitiesForType(mimeType).videoCapabilities?.supportedWidths ?: return 0
            if (!widths.contains(1280)) return 0
            return when {
                widths.contains(3840) -> 3840
                widths.contains(1920) -> 1920
                else -> 1280
            }
        }
    }
}
