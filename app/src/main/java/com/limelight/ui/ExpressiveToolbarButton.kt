package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import androidx.dynamicanimation.animation.SpringAnimation
import com.limelight.utils.UiHelper

/** Springs the artwork while keeping the toolbar layout and touch target stationary. */
class ExpressiveToolbarButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    private var artworkScale = 1f
    private var artworkSpring: SpringAnimation? = null
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        artworkSpring = ExpressiveMotion.spring(1f) {
            artworkScale = it
            invalidate()
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateMotion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion(immediate = true)
    }

    override fun onDetachedFromWindow() {
        settle(1f)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateMotion(immediate = !hasWindowFocus)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion(immediate = visibility != View.VISIBLE)
    }

    private fun updateMotion(immediate: Boolean = false) {
        val motion = artworkSpring ?: return
        val active = isEnabled && hasWindowFocus() && windowVisibility == View.VISIBLE
        val focused = active && isFocused && !isInTouchMode
        val base = if (focused) 1.08f else 1f
        val pressed = active && isPressed
        val target = base * if (pressed) ExpressiveMotion.PRESS_SCALE else 1f
        if (immediate || !isAttachedToWindow || !ExpressiveMotion.enabled(context)) {
            // Reduced motion keeps a small, static press cue and the focus ring.
            settle(target)
            return
        }
        motion.spring!!.dampingRatio = if (pressed) {
            ExpressiveMotion.PRESS_DAMPING
        } else {
            ExpressiveMotion.BUTTON_RELEASE_DAMPING
        }
        // One spring keeps both axes identical, including during rapid input.
        motion.animateToFinalPosition(target)
    }

    private fun settle(scale: Float) {
        artworkSpring?.cancel()
        artworkSpring?.setStartValue(scale)
        artworkScale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val saved = canvas.save()
        canvas.scale(artworkScale, artworkScale, cx, cy)
        if (isEnabled && isFocused && !isInTouchMode && hasWindowFocus()) {
            focusPaint.color = UiHelper.accentColor(context)
            val radius = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) * 0.47f
            canvas.drawCircle(cx, cy, radius, focusPaint)
        }
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && isEnabled) {
            isPressed = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && isEnabled) {
            val click = isPressed && !event.isCanceled
            isPressed = false
            if (click) performClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
