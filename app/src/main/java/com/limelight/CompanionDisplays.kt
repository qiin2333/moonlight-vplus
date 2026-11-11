package com.limelight

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 找出可放伴生面板的第二块屏：必须是 presentation 屏、有效，且物理面积小于主屏
 * （掌机内屏）。电视/显示器等大屏返回 null，沿用原有的“串流到外接屏”行为。
 */
object CompanionDisplays {
    fun find(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val defaultArea = defaultDisplay.mode.physicalWidth.toLong() *
                defaultDisplay.mode.physicalHeight
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                        display.isValid &&
                        display.mode.physicalWidth.toLong() * display.mode.physicalHeight < defaultArea
            }
    }
}
