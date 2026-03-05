package com.limelight.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.preference.CheckBoxPreference
import android.util.AttributeSet
import android.view.Display

import com.limelight.ExternalDisplayManager

/**
 * 外接显示器状态偏好设置
 */
class ExternalDisplayPreference : CheckBoxPreference {

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        updateSummary()
    }

    override fun onAttachedToActivity() {
        super.onAttachedToActivity()
        updateSummary()
    }

    private fun updateSummary() {
        try {
            if (ExternalDisplayManager.hasExternalDisplay(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    if (displayManager != null) {
                        val displays = displayManager.displays
                        for (display in displays) {
                            if (display.displayId != Display.DEFAULT_DISPLAY) {
                                summary = "检测到外接显示器: ${display.name} (ID: ${display.displayId})"
                                isEnabled = true
                                return
                            }
                        }
                    }
                }
            } else {
                summary = "未检测到外接显示器"
                isEnabled = false
                isChecked = false
            }
        } catch (e: Exception) {
            summary = "检测外接显示器失败: $e"
            isEnabled = false
            isChecked = false
        }
    }
}
