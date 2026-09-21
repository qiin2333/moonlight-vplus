package com.limelight.binding.audio

import android.content.Context

/** Stores the last successful microphone state separately for each paired host. */
internal class MicrophoneInitialStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        STORAGE_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(hostUuid: String?): Boolean? {
        val key = storageKey(hostUuid) ?: return null
        if (!preferences.contains(key)) return null
        return preferences.getBoolean(key, false)
    }

    fun save(hostUuid: String?, active: Boolean) {
        storageKey(hostUuid)?.let { key ->
            preferences.edit().putBoolean(key, active).apply()
        }
    }

    private fun storageKey(hostUuid: String?): String? {
        val normalized = hostUuid?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }?.let { "$KEY_PREFIX$it" }
    }

    private companion object {
        const val STORAGE_NAME = "microphone_initial_state"
        const val KEY_PREFIX = "host_"
    }
}
