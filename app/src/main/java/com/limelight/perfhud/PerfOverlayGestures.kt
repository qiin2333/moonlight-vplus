package com.limelight.perfhud

import android.content.Context
import androidx.preference.PreferenceManager

/** Keeps legacy combinations intact until the user chooses one of the two modes. */
object PerfOverlayGestures {
    const val KEY = "perf_overlay_gesture_mode"
    const val FULL = "full"
    const val LONG_ONLY = "long_only"
    const val CUSTOM = "custom"
    private const val SINGLE = "hud_single_tap_style_enabled"
    private const val DOUBLE = "hud_double_tap_layout_enabled"
    private const val LONG = "hud_long_press_info_enabled"

    data class State(val mode: String, val single: Boolean, val double: Boolean, val long: Boolean)

    internal fun resolve(mode: String?, single: Boolean?, double: Boolean?, long: Boolean?): State {
        if (mode == FULL) return State(FULL, true, true, true)
        if (mode == LONG_ONLY) return State(LONG_ONLY, false, false, true)
        if (single == null && double == null && long == null) return State(FULL, true, true, true)
        val s = single ?: false
        val d = double ?: false
        val l = long ?: true
        val legacyMode = when {
            s && d && l -> FULL
            !s && !d && l -> LONG_ONLY
            else -> CUSTOM
        }
        return State(legacyMode, s, d, l)
    }

    fun read(context: Context): State {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val legacy = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        fun old(key: String): Boolean? = if (legacy.contains(key)) legacy.getBoolean(key, false) else null
        return resolve(preferences.getString(KEY, null), old(SINGLE), old(DOUBLE), old(LONG))
    }

    fun write(context: Context, mode: String) {
        require(mode == FULL || mode == LONG_ONLY)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, mode).apply()
    }
}
