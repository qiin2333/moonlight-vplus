package com.limelight.utils

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.limelight.R

/** Game-menu switch appearance for View screens; callers retain ownership of input. */
object AppSwitchStyle {
    @JvmStatic
    fun apply(switch: SwitchCompat) {
        val palette = AppTheme.paletteContext(switch.context)
        val density = switch.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val active = UiHelper.accentColor(palette)
        val soft = UiHelper.accentSoftColor(palette)
        val outline = ContextCompat.getColor(palette, R.color.game_menu_list_item_border)
        fun track(color: Int, border: Int) = GradientDrawable().apply {
            setSize(dp(36), dp(18))
            cornerRadius = dp(9).toFloat()
            setColor(color)
            setStroke((0.75f * density).toInt().coerceAtLeast(1), border)
        }.let { InsetDrawable(it, 0, dp(4), 0, dp(4)) }
        switch.thumbTintList = null
        switch.trackTintList = null
        fun thumb(alpha: Int) = requireNotNull(ContextCompat.getDrawable(palette, R.drawable.app_switch_thumb))
            .mutate().apply { this.alpha = alpha }
        switch.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), thumb(97))
            addState(intArrayOf(), thumb(255))
        }
        switch.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), track(ColorUtils.setAlphaComponent(soft, 25), outline))
            addState(intArrayOf(android.R.attr.state_checked), track(ColorUtils.setAlphaComponent(active, 209),
                ColorUtils.setAlphaComponent(active, 117)))
            addState(intArrayOf(), track(soft, outline))
        }
        switch.showText = false
        switch.splitTrack = false
        switch.thumbTextPadding = 0
        switch.switchMinWidth = dp(36)
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
