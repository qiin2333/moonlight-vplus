package com.limelight.utils

import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.limelight.R

/** Shared View switch colors; callers keep ownership of row or widget input. */
object AppSwitchStyle {
    /** Applies accent states, with an optional inactive color for permanently dark editor panels. */
    @JvmStatic
    @JvmOverloads
    fun apply(switch: SwitchCompat, inactiveColor: Int? = null) {
        val palette = AppTheme.paletteContext(switch.context)
        val active = UiHelper.accentColor(palette)
        val inactive = inactiveColor ?: ContextCompat.getColor(palette, R.color.ui_shell_text_secondary)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf())
        switch.thumbTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 97), active, inactive))
        switch.trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(inactive, 38),
            ColorUtils.setAlphaComponent(active, 89), ColorUtils.setAlphaComponent(inactive, 51)))
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
