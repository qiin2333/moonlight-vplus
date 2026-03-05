package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import android.preference.ListPreference
import android.preference.PreferenceManager
import android.util.AttributeSet

import com.limelight.R

class DynamicPerfOverlayPositionPreference : ListPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attrs, defStyleAttr, defStyleRes) { updateEntries() }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr) { updateEntries() }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { updateEntries() }

    constructor(context: Context) : super(context) { updateEntries() }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateEntries()
    }

    private fun updateEntries() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val orientation = prefs.getString("list_perf_overlay_orientation", "horizontal")

        if ("vertical" == orientation) {
            // 垂直方向：显示四个角选项
            setEntries(R.array.perf_overlay_position_vertical_names)
            setEntryValues(R.array.perf_overlay_position_vertical_values)

            // 如果当前值不在垂直选项中，重置为默认值
            val currentValue = value
            if (currentValue != null && !isValidVerticalValue(currentValue)) {
                value = "top_left"
            }
        } else {
            // 水平方向：显示顶部和底部选项
            setEntries(R.array.perf_overlay_position_horizontal_names)
            setEntryValues(R.array.perf_overlay_position_horizontal_values)

            // 如果当前值不在水平选项中，重置为默认值
            val currentValue = value
            if (currentValue != null && !isValidHorizontalValue(currentValue)) {
                value = "top"
            }
        }
    }

    private fun isValidVerticalValue(value: String): Boolean {
        return value == "top_left" || value == "top_right" ||
                value == "bottom_left" || value == "bottom_right"
    }

    private fun isValidHorizontalValue(value: String): Boolean {
        return value == "top" || value == "bottom"
    }

    fun refreshEntries() {
        updateEntries()
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult) {
            // 当位置改变时，清除自定义拖动位置
            clearCustomPosition()
        }
    }

    private fun clearCustomPosition() {
        val prefs = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
