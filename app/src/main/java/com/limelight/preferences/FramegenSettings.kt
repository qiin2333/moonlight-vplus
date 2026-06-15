package com.limelight.preferences

import android.content.SharedPreferences
import java.io.File
import kotlin.math.roundToInt

object FramegenSettings {
    const val PREF_ENABLED = "checkbox_framegen_enabled"
    const val PREF_ADAPTIVE_ENABLED = "checkbox_framegen_adaptive_enabled"
    const val PREF_QUALITY_PRESET = "list_framegen_quality_preset"
    const val PREF_INTERNAL_WIDTH = "seekbar_framegen_internal_width"
    const val PREF_SLOW_THRESHOLD_MS = "seekbar_framegen_slow_threshold_ms"
    const val PREF_PRESENT_REAL_FIRST = "checkbox_framegen_present_real_first"
    const val PREF_LOSSLESS_DLL_STAGED_PATH = "pref_framegen_lossless_dll_staged_path"

    const val QUALITY_PERFORMANCE = "performance"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_CLARITY = "clarity"
    const val QUALITY_CUSTOM = "custom"

    const val DEFAULT_INTERNAL_WIDTH = 864
    const val DEFAULT_SLOW_THRESHOLD_MS = 18
    const val DEFAULT_PRESENT_QUEUE_MAX = 2
    const val MAX_CAPTURE_PIXELS = 2560 * 2560

    private const val MIN_INTERNAL_WIDTH = 320
    private const val MAX_INTERNAL_WIDTH = 1920
    private const val PERFORMANCE_SCALE = 0.25f
    private const val BALANCED_SCALE = 0.50f
    private const val CLARITY_SCALE = 0.75f
    private const val DEFAULT_CUSTOM_SCALE_PERCENT = 50
    private const val MIN_CUSTOM_SCALE_PERCENT = 20
    private const val MAX_CUSTOM_SCALE_PERCENT = 100

    fun isUserEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, false)

    fun isAdaptiveEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ADAPTIVE_ENABLED, false)

    fun resolveLosslessDllPath(prefs: SharedPreferences): String? {
        val path = prefs.getString(PREF_LOSSLESS_DLL_STAGED_PATH, null)
            ?.takeUnless { it.isBlank() }
            ?: return null
        val file = File(path)

        return path.takeIf { file.isFile && file.length() > 0L }
    }

    fun isLosslessDllReady(prefs: SharedPreferences): Boolean =
        resolveLosslessDllPath(prefs) != null

    fun isReadyForUser(prefs: SharedPreferences): Boolean =
        DeveloperUnlockSettings.isUnlocked(prefs) && isLosslessDllReady(prefs)

    fun isCaptureResolutionSupported(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_CAPTURE_PIXELS.toLong()

    fun resolveInternalWidth(prefs: SharedPreferences, streamWidth: Int): Int {
        val width = when (prefs.getString(PREF_QUALITY_PRESET, null)) {
            QUALITY_PERFORMANCE -> scaledInternalWidth(streamWidth, PERFORMANCE_SCALE)
            QUALITY_BALANCED -> scaledInternalWidth(streamWidth, BALANCED_SCALE)
            QUALITY_CLARITY -> scaledInternalWidth(streamWidth, CLARITY_SCALE)
            QUALITY_CUSTOM -> scaledInternalWidth(
                streamWidth,
                resolveCustomScale(prefs, streamWidth)
            )
            else -> scaledInternalWidth(streamWidth, PERFORMANCE_SCALE)
        }
        return width.coerceIn(MIN_INTERNAL_WIDTH, MAX_INTERNAL_WIDTH)
    }

    fun migrateLegacyCustomScale(prefs: SharedPreferences, streamWidth: Int) {
        val stored = prefs.getInt(PREF_INTERNAL_WIDTH, DEFAULT_CUSTOM_SCALE_PERCENT)
        if (stored <= MAX_CUSTOM_SCALE_PERCENT || streamWidth <= 0) {
            return
        }

        val percent = (stored.toFloat() / streamWidth.toFloat() * 100f)
            .roundToInt()
            .coerceIn(MIN_CUSTOM_SCALE_PERCENT, MAX_CUSTOM_SCALE_PERCENT)
        prefs.edit().putInt(PREF_INTERNAL_WIDTH, percent).apply()
    }

    private fun scaledInternalWidth(streamWidth: Int, scale: Float): Int {
        if (streamWidth <= 0) {
            return DEFAULT_INTERNAL_WIDTH
        }
        return ((streamWidth * scale).toInt() / 16) * 16
    }

    private fun resolveCustomScale(prefs: SharedPreferences, streamWidth: Int): Float {
        val stored = prefs.getInt(PREF_INTERNAL_WIDTH, DEFAULT_CUSTOM_SCALE_PERCENT)
        if (stored > MAX_CUSTOM_SCALE_PERCENT && streamWidth > 0) {
            return (stored.toFloat() / streamWidth.toFloat())
                .coerceIn(
                    MIN_CUSTOM_SCALE_PERCENT / 100f,
                    MAX_CUSTOM_SCALE_PERCENT / 100f
                )
        }
        return stored.coerceIn(MIN_CUSTOM_SCALE_PERCENT, MAX_CUSTOM_SCALE_PERCENT) / 100f
    }
}
