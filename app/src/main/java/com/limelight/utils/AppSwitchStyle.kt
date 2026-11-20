package com.limelight.utils

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
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
        val existingTrack = switch.trackDrawable as? SwitchTrackDrawable
        // Preference rows rebind after toggling; keep their in-flight animation.
        if (existingTrack?.hasPalette(active, soft, outline, density) == true) return
        switch.thumbTintList = null
        switch.trackTintList = null
        // The vector contains its own transparent inset. An InsetDrawable
        // exposes optical insets that SwitchCompat also subtracts from the track.
        fun thumb(alpha: Int) = requireNotNull(ContextCompat.getDrawable(palette, R.drawable.app_switch_thumb))
            .mutate().apply { this.alpha = alpha }
        switch.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), thumb(97))
            addState(intArrayOf(), thumb(255))
        }
        switch.trackDrawable?.jumpToCurrentState()
        switch.trackDrawable = SwitchTrackDrawable(switch, active, soft, outline)
        switch.showText = false
        switch.splitTrack = false
        switch.thumbTextPadding = 0
        switch.switchMinWidth = dp(AppSwitchSpec.WIDTH_DP)
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}

/** Animate the palette with the same timing as SwitchCompat's native thumb. */
private class SwitchTrackDrawable(
    private val switch: SwitchCompat,
    private val active: Int,
    private val soft: Int,
    private val outline: Int
) : GradientDrawable() {
    private val density = switch.resources.displayMetrics.density
    private var checked = switch.isChecked
    private var enabled = switch.isEnabled
    private var progress = if (checked) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        setSize(
            (AppSwitchSpec.TRACK_WIDTH_DP * density + 0.5f).toInt(),
            (AppSwitchSpec.TRACK_HEIGHT_DP * density + 0.5f).toInt()
        )
        cornerRadius = AppSwitchSpec.CORNER_RADIUS_DP * density
        updateColors()
    }

    override fun isStateful() = true

    fun hasPalette(active: Int, soft: Int, outline: Int, density: Float) =
        this.active == active && this.soft == soft && this.outline == outline && this.density == density

    override fun onStateChange(stateSet: IntArray): Boolean {
        val nextChecked = android.R.attr.state_checked in stateSet
        val nextEnabled = android.R.attr.state_enabled in stateSet
        if (checked == nextChecked && enabled == nextEnabled) return false
        enabled = nextEnabled
        if (checked != nextChecked) {
            checked = nextChecked
            animator?.cancel()
            val target = if (checked) 1f else 0f
            if (switch.windowToken != null && switch.isLaidOut && isVisible) {
                animator = ValueAnimator.ofFloat(progress, target).apply {
                    duration = AppSwitchMotion.DurationMillis.toLong()
                    interpolator = AppSwitchMotion.interpolator
                    addUpdateListener { progress = it.animatedValue as Float; updateColors() }
                    start()
                }
            } else {
                progress = target
            }
        }
        updateColors()
        return true
    }

    override fun jumpToCurrentState() {
        animator?.cancel()
        animator = null
        progress = if (checked) 1f else 0f
        updateColors()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) jumpToCurrentState()
        return super.setVisible(visible, restart)
    }

    private fun updateColors() {
        setColor(if (enabled) ColorUtils.blendARGB(soft, ColorUtils.setAlphaComponent(active, 209), progress)
            else ColorUtils.setAlphaComponent(soft, 26))
        val border = if (enabled) ColorUtils.blendARGB(outline,
            ColorUtils.setAlphaComponent(active, 117), progress) else outline
        setStroke((AppSwitchSpec.STROKE_DP * density).toInt().coerceAtLeast(1), border)
    }
}
