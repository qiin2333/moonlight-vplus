package com.limelight.preferences

import android.content.SharedPreferences
import java.io.File

object FramegenSettings {
    const val PREF_ENABLED = "checkbox_framegen_enabled"
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

    private const val MIN_INTERNAL_WIDTH = 640
    private const val MAX_INTERNAL_WIDTH = 1920

    fun isUserEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, false)

    fun isLosslessDllReady(prefs: SharedPreferences): Boolean {
        val path = prefs.getString(PREF_LOSSLESS_DLL_STAGED_PATH, null)
        return !path.isNullOrBlank() && File(path).let { it.isFile && it.length() > 0L }
    }

    fun isAllowedByUser(prefs: SharedPreferences): Boolean =
        isUserEnabled(prefs) && DeveloperUnlockSettings.isUnlocked(prefs) && isLosslessDllReady(prefs)

    fun resolveInternalWidth(prefs: SharedPreferences): Int {
        val width = when (prefs.getString(PREF_QUALITY_PRESET, null)) {
            QUALITY_PERFORMANCE -> 864
            QUALITY_BALANCED -> 960
            QUALITY_CLARITY -> 1200
            QUALITY_CUSTOM -> prefs.getInt(PREF_INTERNAL_WIDTH, DEFAULT_INTERNAL_WIDTH)
            else -> prefs.getInt(PREF_INTERNAL_WIDTH, DEFAULT_INTERNAL_WIDTH)
        }
        return width.coerceIn(MIN_INTERNAL_WIDTH, MAX_INTERNAL_WIDTH)
    }
}
