package com.limelight.preferences

import android.content.Context
import android.preference.ListPreference
import android.util.AttributeSet

class PerfOverlayOrientationPreference : ListPreference {
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) :
        super(context, attrs)

    constructor(context: Context) :
        super(context)

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult) {
            // 当方向改变时，通知位置Preference更新选项
            updatePositionPreference()
        }
    }

    private fun updatePositionPreference() {
        val preferenceManager = preferenceManager ?: return
        val positionPref = preferenceManager.findPreference("list_perf_overlay_position")
                as? DynamicPerfOverlayPositionPreference
        positionPref?.refreshEntries()
    }
}
