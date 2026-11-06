package com.limelight.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.SpringAnimation
import com.limelight.R
import com.limelight.utils.UiHelper
import kotlin.math.roundToInt

/** Owns the top-panel handle's rendering and transition state. */
internal class TopPanelHandleController(
    private val toggle: TextView
) {
    private val context = toggle.context
    private val density = toggle.resources.displayMetrics.density
    private val background = GradientDrawable()
    private val backgroundColor = color(R.color.settings_drawer_background)
    private val primaryColor = color(R.color.ui_shell_text_primary)
    private val secondaryColor = withAlpha(color(R.color.ui_shell_text_secondary), 0.78f)
    private var accentColor = UiHelper.accentColor(context)
    private val outlineColor = color(R.color.ui_shell_outline)
    private var focusColor = UiHelper.accentColor(context)
    private val cornerRadii = FloatArray(8)
    private val moonWheel = MoonWheelDrawable(
        density = density,
        graphiteColor = primaryColor,
        accentColor = accentColor
    )

    private var progress = 0f
    private var animator: SpringAnimation? = null
    private var pendingReveal: (() -> Unit)? = null
    private val useStaticFallback = Build.VERSION.SDK_INT < Build.VERSION_CODES.M

    init {
        toggle.text = null
        if (useStaticFallback) {
            toggle.text = "—"
            toggle.gravity = Gravity.CENTER
            toggle.setTextColor(primaryColor)
            toggle.textSize = 24f
        } else {
            toggle.foreground = moonWheel
            toggle.foregroundGravity = Gravity.CENTER
        }
        background.shape = GradientDrawable.RECTANGLE
        toggle.background = background
        toggle.setOnFocusChangeListener { _, _ -> updateAppearance(progress) }
        updateAppearance(0f)
    }

    fun animate(expanded: Boolean, onPanelReveal: (() -> Unit)? = null) {
        val target = if (expanded) 1f else 0f
        pendingReveal = if (expanded) onPanelReveal else null
        if (useStaticFallback || !ExpressiveMotion.enabled(context)) {
            animator?.cancel()
            animator = null
            updateAppearance(target)
            pendingReveal = null
            if (expanded) onPanelReveal?.invoke()
            return
        }
        toggle.animate().cancel()
        toggle.alpha = 1f
        val motion = animator ?: ExpressiveMotion.spring(progress) { value ->
            updateAppearance(value)
            if (value >= 0.38f) {
                val reveal = pendingReveal
                pendingReveal = null
                reveal?.invoke()
            }
        }.also { animator = it }
        motion.animateToFinalPosition(target)
    }

    fun release() {
        animator?.cancel()
        animator = null
        pendingReveal = null
        toggle.animate().cancel()
        toggle.onFocusChangeListener = null
        if (!useStaticFallback) toggle.foreground = null
    }

    fun refreshAccentColor() {
        accentColor = UiHelper.accentColor(context)
        focusColor = accentColor
        updateAppearance(progress)
    }

    private fun updateAppearance(value: Float) {
        val fraction = value.coerceIn(0f, 1f)
        progress = value

        // Render-thread scaling avoids remeasuring the parent layout each frame.
        val handleWidth = toggle.width.takeIf { it > 0 }
            ?: toggle.layoutParams.width.takeIf { it > 0 }
            ?: (84f * density).roundToInt()
        val showFocusRing = toggle.hasFocus() && fraction < 0.5f
        val focusScale = if (showFocusRing) 1.08f else 1f
        toggle.scaleX = (1f - ((16f * density) / handleWidth) * value.coerceIn(-0.08f, 1.08f)) * focusScale
        toggle.scaleY = focusScale

        val topRadius = (18f - 4f * fraction) * density
        val bottomRadius = (18f - 16f * fraction) * density
        val chromeAlpha = ((1f - fraction) / 0.6f).coerceIn(0f, 1f)
        background.setColor(
            if (showFocusRing) {
                ColorUtils.compositeColors(withAlpha(focusColor, 0.24f), backgroundColor)
            } else {
                withAlpha(backgroundColor, chromeAlpha)
            }
        )
        cornerRadii[0] = topRadius
        cornerRadii[1] = topRadius
        cornerRadii[2] = topRadius
        cornerRadii[3] = topRadius
        cornerRadii[4] = bottomRadius
        cornerRadii[5] = bottomRadius
        cornerRadii[6] = bottomRadius
        cornerRadii[7] = bottomRadius
        background.cornerRadii = cornerRadii

        background.setStroke(
            ((if (showFocusRing) 3f else 1f) * density).roundToInt().coerceAtLeast(1),
            if (showFocusRing) {
                focusColor
            } else {
                withAlpha(outlineColor, chromeAlpha)
            }
        )

        moonWheel.progress = fraction
        moonWheel.graphiteColor = if (showFocusRing) {
            focusColor
        } else {
            ColorUtils.blendARGB(primaryColor, secondaryColor, fraction)
        }
        moonWheel.accentColor = accentColor
    }

    private fun color(resourceId: Int): Int = ContextCompat.getColor(context, resourceId)

    private fun withAlpha(color: Int, multiplier: Float): Int = Color.argb(
        (Color.alpha(color) * multiplier.coerceIn(0f, 1f)).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
