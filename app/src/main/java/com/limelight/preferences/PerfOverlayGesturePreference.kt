package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import com.limelight.R
import com.limelight.perfhud.PerfOverlayGestures

class PerfOverlayGesturePreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    init {
        summaryProvider = SimpleSummaryProvider.getInstance()
        val state = PerfOverlayGestures.read(context)
        entries = arrayOf(context.getString(R.string.game_menu_perf_gestures_full),
            context.getString(R.string.game_menu_perf_gestures_long_only))
        entryValues = arrayOf(PerfOverlayGestures.FULL, PerfOverlayGestures.LONG_ONLY)
        if (state.mode == PerfOverlayGestures.CUSTOM) {
            entries = entries + context.getString(R.string.game_menu_perf_gestures_custom)
            entryValues = entryValues + PerfOverlayGestures.CUSTOM
        }
        setOnPreferenceChangeListener { _, value ->
            if (value == PerfOverlayGestures.CUSTOM) false else {
                entries = entries.take(2).toTypedArray()
                entryValues = entryValues.take(2).toTypedArray()
                true
            }
        }
        preferenceDataStore = object : PreferenceDataStore() {
            override fun getString(key: String, defValue: String?): String = PerfOverlayGestures.read(context).mode
            override fun putString(key: String, value: String?) {
                if (value == PerfOverlayGestures.FULL || value == PerfOverlayGestures.LONG_ONLY) {
                    PerfOverlayGestures.write(context, value)
                }
            }
        }
    }
}
