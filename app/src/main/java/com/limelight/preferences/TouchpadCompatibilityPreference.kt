package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.MultiSelectListPreference
import com.limelight.binding.input.touchpad.TouchpadCompatibilityDevices

/** Refresh the picker when opened, including selected devices that are currently disconnected. */
class TouchpadCompatibilityPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MultiSelectListPreference(context, attrs) {
    private var deviceNames = emptyMap<String, String>()

    init {
        isPersistent = false
        values = TouchpadCompatibilityDevices.selected(context)
        setOnPreferenceChangeListener { _, value ->
            @Suppress("UNCHECKED_CAST")
            TouchpadCompatibilityDevices.save(context, value as Set<String>, deviceNames)
            true
        }
        refreshDevices()
    }

    override fun onClick() {
        refreshDevices()
        super.onClick()
    }

    private fun refreshDevices() {
        deviceNames = TouchpadCompatibilityDevices.names(context).toList()
            .sortedBy { it.second.lowercase() }.toMap()
        entries = deviceNames.values.toTypedArray()
        entryValues = deviceNames.keys.toTypedArray()
        values = TouchpadCompatibilityDevices.selected(context)
    }
}
