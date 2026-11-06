package com.limelight.preferences

import android.annotation.SuppressLint
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter

/** Stops an outgoing adapter from queuing more hierarchy work during a page transition. */
@SuppressLint("RestrictedApi")
internal open class SettingsPreferenceAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {
    private var stopped = false

    fun stopObservingChanges() { stopped = true }

    override fun onPreferenceChange(preference: Preference) {
        if (!stopped) super.onPreferenceChange(preference)
    }

    override fun onPreferenceHierarchyChange(preference: Preference) {
        if (!stopped) super.onPreferenceHierarchyChange(preference)
    }

    override fun onPreferenceVisibilityChange(preference: Preference) {
        if (!stopped) super.onPreferenceVisibilityChange(preference)
    }
}
