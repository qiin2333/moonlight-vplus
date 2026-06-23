package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet

import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceManager

import com.limelight.R
import com.limelight.utils.UiHelper

class PerfOverlayDisplayItemsPreference : MultiSelectListPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes) { initialize() }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr) { initialize() }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { initialize() }

    constructor(context: Context) : super(context) { initialize() }

    private fun initialize() {
        val nameEntries = context.resources.getTextArray(R.array.perf_overlay_display_items_names)
        val valueEntries = context.resources.getTextArray(R.array.perf_overlay_display_items_values)

        if (UiHelper.hasDisplayableBattery(context)) {
            entries = nameEntries
            entryValues = valueEntries
        } else {
            val filteredNames = ArrayList<CharSequence>()
            val filteredValues = ArrayList<CharSequence>()

            valueEntries.forEachIndexed { index, value ->
                if (value.toString() != BATTERY_ITEM) {
                    filteredNames.add(nameEntries[index])
                    filteredValues.add(value)
                }
            }

            entries = filteredNames.toTypedArray()
            entryValues = filteredValues.toTypedArray()
        }

        setDefaultValue(getDefaultDisplayItems(context))
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(key)) {
            values = getDefaultDisplayItems(context)
        } else if (!UiHelper.hasDisplayableBattery(context)) {
            val selectedItems = prefs.getStringSet(key, emptySet()) ?: emptySet()
            if (selectedItems.contains(BATTERY_ITEM)) {
                val filteredItems = selectedItems.filterNot { it == BATTERY_ITEM }.toSet()
                prefs.edit().putStringSet(key, filteredItems).apply()
                values = filteredItems
            }
        }
    }

    companion object {
        private const val BATTERY_ITEM = "battery"
        private const val DEFAULT_ITEMS = "resolution,decoder,render_fps,network_latency,decode_latency,host_latency,packet_loss,battery"

        fun getDefaultDisplayItems(context: Context): Set<String> {
            val defaultItems = DEFAULT_ITEMS.split(",").toMutableSet()
            if (!UiHelper.hasDisplayableBattery(context)) {
                defaultItems.remove(BATTERY_ITEM)
            }
            return defaultItems
        }

        fun isItemEnabled(context: Context, itemKey: String): Boolean {
            if (itemKey == BATTERY_ITEM && !UiHelper.hasDisplayableBattery(context)) {
                return false
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val selectedItems = prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems(context))
            return selectedItems?.contains(itemKey) == true
        }

        fun getSelectedItems(context: Context): Set<String>? {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems(context))
        }

        fun setDisplayItems(context: Context, items: Set<String>) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putStringSet("perf_overlay_display_items", items).apply()
        }
    }
}
