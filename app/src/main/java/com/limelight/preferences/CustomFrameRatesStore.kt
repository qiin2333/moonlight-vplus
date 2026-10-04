package com.limelight.preferences

import android.content.Context

/** Stores the editable frame-rate list used by the in-game display page. */
object CustomFrameRatesStore {
    private const val PREFS_FILE = "custom_frame_rates"
    private const val PREFS_KEY = "custom_frame_rates"

    fun load(context: Context): List<Int> {
        val stored = prefs(context).getStringSet(PREFS_KEY, null)
            ?: return defaultFrameRates()
        return stored.mapNotNull { it.toIntOrNull()?.takeIf { fps -> fps > 0 } }
            .distinct()
            .sorted()
    }

    fun save(context: Context, frameRates: List<Int>) {
        prefs(context).edit()
            .putStringSet(PREFS_KEY, frameRates.filter { it > 0 }.map(Int::toString).toSet())
            .commit()
    }

    fun add(context: Context, frameRate: Int): Boolean {
        if (frameRate <= 0) return false
        val existing = load(context)
        if (frameRate in existing) return false
        save(context, existing + frameRate)
        return true
    }

    fun remove(context: Context, frameRate: Int) {
        save(context, load(context) - frameRate)
    }

    private fun defaultFrameRates(): List<Int> = listOf(30, 60, 120)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
