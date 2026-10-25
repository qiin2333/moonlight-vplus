package com.limelight.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceManager
import com.limelight.R

/**
 * 外接显示器状态偏好设置
 */
class ExternalDisplayPreference : CheckBoxPreference {

    constructor(context: Context) : super(context) {
        updateSummary()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        updateSummary()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        updateSummary()
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        updateSummary()
    }

    private fun updateSummary() {
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.displays?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            if (display != null) {
                summary = context.getString(
                    R.string.summary_external_display_detected,
                    display.name,
                    display.displayId
                )
                isEnabled = true
            } else {
                summary = context.getString(R.string.summary_external_display_not_detected)
                isEnabled = false
                isChecked = false
            }
        } catch (e: Exception) {
            Log.w("ExternalDisplay", "Failed to detect external display", e)
            summary = context.getString(R.string.summary_external_display_detection_failed)
            isEnabled = false
            isChecked = false
        }
    }
}
