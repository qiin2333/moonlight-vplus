package com.limelight

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 找出可放伴生面板的第二块屏：必须是 presentation 屏、有效，且物理面积小于主屏
 * （掌机内屏）。电视/显示器等大屏返回 null，沿用原有的“串流到外接屏”行为。
 */
object CompanionDisplays {
    /** 当前有伴生面板（浏览态或串流态）正显示的副屏；弹窗据此改弹到副屏，不盖住主屏。 */
    @Volatile
    var active: Display? = null

    /** 把弹窗的 Context 换成副屏的显示 Context（无伴生面板时原样返回）。 */
    fun redirect(context: Context): Context {
        val display = active?.takeIf { it.isValid } ?: return context
        if (context.display?.displayId == display.displayId) return context
        return context.createDisplayContext(display)
    }

    /** 弹窗显示前调用：若它属于副屏，则把窗口类型设为 TYPE_PRESENTATION(2037)。 */
    fun place(dialog: android.app.Dialog) {
        val display = active ?: return
        if (dialog.context.display?.displayId == display.displayId) {
            dialog.window?.setType(2037 /* TYPE_PRESENTATION，SDK 里未公开 */)
        }
    }

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
