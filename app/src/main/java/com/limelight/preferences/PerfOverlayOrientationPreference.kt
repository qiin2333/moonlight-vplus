package com.limelight.preferences

import android.content.Context
import androidx.preference.ListPreference
import androidx.preference.Preference
import android.util.AttributeSet

class PerfOverlayOrientationPreference : ListPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) { init() }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) { init() }

    constructor(context: Context, attrs: AttributeSet?) :
        super(context, attrs) { init() }

    constructor(context: Context) :
        super(context) { init() }

    private fun init() {
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            updatePositionPreference()
            true
        }
    }

    private fun updatePositionPreference() {
        val preferenceManager = preferenceManager ?: return
        val positionPref = preferenceManager.findPreference("list_perf_overlay_position")
                as? DynamicPerfOverlayPositionPreference
        positionPref?.refreshEntries()
    }
}
