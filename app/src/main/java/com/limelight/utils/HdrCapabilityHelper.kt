@file:Suppress("DEPRECATION")
package com.limelight.utils

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import com.limelight.LimeLog

/**
 * 统一的 HDR 与屏幕能力检测工具类
 * 用于向服务端报告亮度信息以及在诊断页面展示
 */
object HdrCapabilityHelper {

    class BrightnessInfo {
        var report: HdrBrightness? = null

        /**
         * Peak as the driver's EDID/mode metadata claims it, before complete-report validation
         * and independent of [report]: the host report is withheld as one atomic set when the
         * black level is missing, but a claimed peak still exists to cross-check against the
         * HDR/SDR ratio. Null when the driver gave no positive finite value.
         */
        var edidPeakNits: Float? = null

        val maxLuminance: Float get() = report?.maxNits ?: Float.NaN
        val minLuminance: Float get() = report?.minNits ?: Float.NaN
        val maxAvgLuminance: Float get() = report?.maxFullFrameNits ?: Float.NaN
        val isFromHdrCaps: Boolean get() = report?.isEstimatedFromRatio == false
        val isDefault: Boolean get() = report == null

        var hdrSdrRatio: Float = 1.0f
        var highestHdrSdrRatio: Float = 1.0f
        var isHdrSdrRatioAvailable: Boolean = false

        /** Largest usable captured ratio, or null when none is usable. */
        val bestHdrSdrRatio: Float?
            get() = if (isHdrSdrRatioAvailable) {
                listOf(hdrSdrRatio, highestHdrSdrRatio)
                    .mapNotNull(HdrBrightness::usableHdrSdrRatio)
                    .maxOrNull()
            } else null
    }

    class HdrTypeSupport {
        var hasHlg: Boolean = false
        var hasHdr10: Boolean = false
        var hasHdr10Plus: Boolean = false
        var hasDolbyVision: Boolean = false
        var rawTypes: IntArray = IntArray(0)
    }

    class HdrCapabilityInfo {
        var brightness: BrightnessInfo = BrightnessInfo()
        var typeSupport: HdrTypeSupport = HdrTypeSupport()
        var isScreenHdr: Boolean = false
        var isWideColorGamut: Boolean = false
        var displayReportsHdr: Boolean = false
    }

    @SuppressLint("NewApi")
    fun getBrightnessInfoForDisplay(display: Display?): BrightnessInfo {
        val info = BrightnessInfo()
        if (display == null || !display.isValid) return info
        var maxLum = Float.NaN
        var minLum = Float.NaN
        var maxAvgLum = Float.NaN

        // 第一步：从 EDID (HdrCapabilities) 获取基础亮度值 (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val hdrCaps = display.hdrCapabilities
                if (hdrCaps != null) {
                    maxLum = hdrCaps.desiredMaxLuminance
                    minLum = hdrCaps.desiredMinLuminance
                    maxAvgLum = hdrCaps.desiredMaxAverageLuminance
                    LimeLog.info("HdrCapabilities raw: max=$maxLum, min=$minLum, avg=$maxAvgLum")
                }
            } catch (e: Exception) {
                LimeLog.warning("Failed to get display luminance: ${e.message}")
            }
        }

        // An available ratio can supply a rough estimate when the system peak is missing.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                info.isHdrSdrRatioAvailable = display.isHdrSdrRatioAvailable
                if (info.isHdrSdrRatioAvailable) {
                    info.hdrSdrRatio = display.hdrSdrRatio
                    LimeLog.info("HDR/SDR ratio: current=${info.hdrSdrRatio}, available=${info.isHdrSdrRatioAvailable}")

                    if (Build.VERSION.SDK_INT >= 36) {
                        try {
                            info.highestHdrSdrRatio = display.highestHdrSdrRatio
                            LimeLog.info("Highest HDR/SDR ratio: ${info.highestHdrSdrRatio}")
                        } catch (_: NoSuchMethodError) {
                            info.highestHdrSdrRatio = info.hdrSdrRatio
                        }
                    } else {
                        info.highestHdrSdrRatio = info.hdrSdrRatio
                    }
                }
            } catch (e: Exception) {
                LimeLog.warning("Failed to get HDR/SDR ratio: ${e.message}")
            }
        }

        info.edidPeakNits = maxLum.takeIf { it.isFinite() && it > 0f }
        info.report = HdrBrightness.fromDisplay(maxLum, minLum, maxAvgLum, info.bestHdrSdrRatio)
        if (info.report?.isEstimatedFromRatio == true) {
            LimeLog.info("HDR brightness estimated using ratio=${info.bestHdrSdrRatio} and assumed SDR white=300 nits: " +
                "max=${info.maxLuminance}, min=${info.minLuminance}, avg=${info.maxAvgLuminance}")
        }
        return info
    }

    @SuppressLint("NewApi")
    fun getHdrTypeSupport(context: Context?): HdrTypeSupport {
        if (context == null) return HdrTypeSupport()
        return getHdrTypeSupport(getDefaultDisplay(context))
    }

    /**
     * Returns display-wide HDR capabilities without restricting them to the current display mode.
     * This is intended for settings shown before a specific streaming mode has been selected.
     */
    @SuppressLint("NewApi")
    fun getDisplayWideHdrTypeSupport(display: Display?): HdrTypeSupport {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return HdrTypeSupport()
        }

        return parseHdrTypes(display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0))
    }

    /**
     * Returns HDR types for the selected display mode on Android 14+, where HDR support may vary
     * by mode. Older releases expose only display-wide HDR capabilities.
     */
    @SuppressLint("NewApi")
    fun getHdrTypeSupport(display: Display?, selectedModeId: Int = -1): HdrTypeSupport {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return HdrTypeSupport()
        }

        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val selectedMode = if (selectedModeId >= 0) {
                display.supportedModes.firstOrNull { it.modeId == selectedModeId }
            } else {
                null
            }
            (selectedMode ?: display.mode).supportedHdrTypes
        } else {
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        }

        return parseHdrTypes(types)
    }

    @SuppressLint("InlinedApi")
    private fun parseHdrTypes(types: IntArray): HdrTypeSupport {
        val support = HdrTypeSupport()
        support.rawTypes = types

        for (type in types) {
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> support.hasDolbyVision = true
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> support.hasHdr10 = true
                Display.HdrCapabilities.HDR_TYPE_HLG -> support.hasHlg = true
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> support.hasHdr10Plus = true
            }
        }

        return support
    }

    @SuppressLint("NewApi")
    fun getFullCapabilityInfo(context: Context?, display: Display? = context?.let(::getDefaultDisplay)): HdrCapabilityInfo {
        val capInfo = HdrCapabilityInfo()
        capInfo.brightness = getBrightnessInfoForDisplay(display)
        capInfo.typeSupport = getHdrTypeSupport(display)

        if (context != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val displayContext = display?.let(context::createDisplayContext) ?: context
                    capInfo.isScreenHdr = displayContext.resources.configuration.isScreenHdr
                } catch (_: Exception) {}
            }

            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    capInfo.isWideColorGamut = display.isWideColorGamut
                } catch (_: Exception) {}
            }

            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    capInfo.displayReportsHdr = display.isHdr
                } catch (_: NoSuchMethodError) {}
            }
        }

        return capInfo
    }

    fun getBrightnessReport(display: Display?, peakOverride: Int? = null): HdrBrightness? {
        return HdrBrightness.forSession(getBrightnessInfoForDisplay(display).report, peakOverride)
    }

    fun getSystemBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        } catch (_: Exception) {
            -1
        }
    }

    fun isAutoBrightnessEnabled(context: Context): Boolean {
        return try {
            val mode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("NewApi")
    private fun getDefaultDisplay(context: Context): Display? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (dm != null) {
                return dm.getDisplay(Display.DEFAULT_DISPLAY)
            }
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay
        } catch (e: Exception) {
            LimeLog.warning("Failed to get default display: ${e.message}")
            null
        }
    }
}
