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
    private var artworkScaleX = 1f
    private var artworkScaleY = 1f
    private var horizontalSpring: SpringAnimation? = null
    private var verticalSpring: SpringAnimation? = null
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        horizontalSpring = spring { artworkScaleX = it }
        verticalSpring = spring { artworkScaleY = it }
    }

    private fun spring(update: (Float) -> Unit) = ExpressiveMotion.spring(1f) {
        update(it)
        invalidate()
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
        settle(1f, 1f)
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
        val xSpring = horizontalSpring ?: return
        val ySpring = verticalSpring ?: return
        val active = isEnabled && hasWindowFocus() && windowVisibility == View.VISIBLE
        val focused = active && isFocused && !isInTouchMode
        val base = if (focused) 1.08f else 1f
        val pressed = active && isPressed
        val targetX = base * if (pressed) 1.04f else 1f
        val targetY = base * if (pressed) 0.90f else 1f
        if (immediate || !isAttachedToWindow || !ExpressiveMotion.enabled(context)) {
            // Reduced motion keeps a small, static press cue and the focus ring.
            settle(if (pressed) base * 0.96f else base, if (pressed) base * 0.96f else base)
            return
        }
        val damping = if (pressed) ExpressiveMotion.PRESS_DAMPING else ExpressiveMotion.RELEASE_DAMPING
        xSpring.spring!!.dampingRatio = damping
        ySpring.spring!!.dampingRatio = damping
        // Retarget running springs without losing their velocity during rapid input.
        xSpring.animateToFinalPosition(targetX)
        ySpring.animateToFinalPosition(targetY)
    }

    private fun settle(x: Float, y: Float) {
        horizontalSpring?.cancel()
        verticalSpring?.cancel()
        horizontalSpring?.setStartValue(x)
        verticalSpring?.setStartValue(y)
        artworkScaleX = x
        artworkScaleY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val saved = canvas.save()
        canvas.scale(artworkScaleX, artworkScaleY, cx, cy)
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
