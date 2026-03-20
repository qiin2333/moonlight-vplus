package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceManager
import android.util.AttributeSet

import com.limelight.R

class PerfOverlayDisplayItemsPreference : MultiSelectListPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes) { initialize() }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr) { initialize() }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { initialize() }

    constructor(context: Context) : super(context) { initialize() }

    private fun initialize() {
        setEntries(R.array.perf_overlay_display_items_names)
        setEntryValues(R.array.perf_overlay_display_items_values)

        // 设置默认值（所有项目都默认选中）
        val defaultValues = DEFAULT_ITEMS.split(",")
        val defaultSet = HashSet(defaultValues)
        setDefaultValue(defaultSet)
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)

        // 如果没有保存的值，设置默认值
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.contains(key)) {
            val defaultValues = DEFAULT_ITEMS.split(",")
            val defaultSet = HashSet(defaultValues)
            values = defaultSet
        }
    }

    companion object {
        private const val DEFAULT_ITEMS = "resolution,decoder,render_fps,network_latency,decode_latency,host_latency,packet_loss,battery"

        /**
         * 获取默认的显示项目
         */
        @JvmStatic
        fun getDefaultDisplayItems(): Set<String> {
            return HashSet(DEFAULT_ITEMS.split(","))
        }

        /**
         * 检查特定项目是否被选中显示
         */
        @JvmStatic
        fun isItemEnabled(context: Context, itemKey: String): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val selectedItems = prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems())
            return selectedItems?.contains(itemKey) == true
        }

        /**
         * 测试用：获取当前选中的所有显示项目
         */
        @JvmStatic
        fun getSelectedItems(context: Context): Set<String>? {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems())
        }

        /**
         * 测试用：手动设置显示项目
         */
        @JvmStatic
        fun setDisplayItems(context: Context, items: Set<String>) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putStringSet("perf_overlay_display_items", items).apply()
        }
    }
}
