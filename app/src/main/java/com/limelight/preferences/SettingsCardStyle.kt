package com.limelight.preferences

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.limelight.R
import com.limelight.utils.UiHelper

/** Shared surfaces for both levels of settings navigation. */
internal object SettingsCardStyle {
    fun background(context: Context, top: Boolean, bottom: Boolean): StateListDrawable {
        val radius = context.resources.getDimension(R.dimen.corner_radius_medium)
        val radii = floatArrayOf(if (top) radius else 0f, if (top) radius else 0f,
            if (top) radius else 0f, if (top) radius else 0f,
            if (bottom) radius else 0f, if (bottom) radius else 0f,
            if (bottom) radius else 0f, if (bottom) radius else 0f)
        fun shape(color: Int, focused: Boolean = false) = GradientDrawable().apply {
            cornerRadii = radii
            setColor(color)
            if (focused) setStroke((2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                UiHelper.accentFocusColor(context))
        }
        val surface = ContextCompat.getColor(context, R.color.ui_shell_surface_elevated)
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(surface))
            addState(intArrayOf(android.R.attr.state_focused),
                shape(ColorUtils.compositeColors(UiHelper.accentSoftColor(context), surface), true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(ColorUtils.compositeColors(
                ContextCompat.getColor(context, R.color.ui_shell_surface_pressed), surface)))
            addState(intArrayOf(), shape(surface))
        }
    }
}
