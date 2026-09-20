package com.limelight.preferences

import android.content.Context
import androidx.preference.PreferenceManager

/** Shared by standalone settings, the in-stream card and the USB worker.
 * Legacy preferences are read only for migration; subsequent changes to the old
 * experimental gate cannot enable or disable this independently owned backend.
 */
internal object SensaStrengthPreferences {
    const val KEY = "sensa_haptics_strength"
    const val FREQUENCY_KEY = "sensa_haptics_frequency"
    const val MODE_KEY = "sensa_haptics_mode"
    const val ONLY_HAPTIC = "only_haptic"
    const val HAPTIC_OR_RUMBLE = "haptic_or_rumble"
    const val RUMBLE_ONLY = "rumble_only"
    const val ENABLED_KEY = "checkbox_sensa_haptics"
    fun enabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Preserve the previous choice once, then keep the two switches independent.
        if (!prefs.contains(ENABLED_KEY)) prefs.edit().putBoolean(ENABLED_KEY,
            prefs.getBoolean("checkbox_experimental_haptic_protocols", false)).apply()
        return prefs.getBoolean(ENABLED_KEY, false)
    }
    fun mode(context: Context): String {
        // Preserve conversion-off installations as Haptic only. New installations
        // use authored effects with rumble fallback; malformed values use that default.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return when (val saved = prefs.getString(MODE_KEY, null)) {
            ONLY_HAPTIC, HAPTIC_OR_RUMBLE, RUMBLE_ONLY -> saved
            null -> if (prefs.getBoolean("sensa_rumble_conversion", true)) HAPTIC_OR_RUMBLE else ONLY_HAPTIC
            else -> HAPTIC_OR_RUMBLE
        }
    }
    fun conversionEnabled(context: Context): Boolean = mode(context) != ONLY_HAPTIC
    fun pcmEnabled(context: Context): Boolean = mode(context) != RUMBLE_ONLY
    fun frequency(context: Context): Double = PreferenceManager.getDefaultSharedPreferences(context)
        .getInt(FREQUENCY_KEY, 100).coerceIn(30, 400).toDouble()
    fun read(context: Context): Double = PreferenceManager.getDefaultSharedPreferences(context)
        .getInt(KEY, 100).coerceIn(0, 100) / 100.0
}
