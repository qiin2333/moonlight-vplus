package com.limelight.binding.input.virtual_controller

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Layered glass finish; explicit strokes stay readable under OEM shader recoloring. */
internal class ControllerSurface {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val cachedBounds = RectF()
    private val innerBounds = RectF()
    private var cachedPill = false
    private var ready = false

    fun draw(canvas: Canvas, bounds: RectF, pill: Boolean, color: Int, stroke: Float) {
        if (!ready || cachedBounds != bounds || cachedPill != pill) {
            ready = true
            cachedBounds.set(bounds)
            cachedPill = pill
            clip.reset()
            if (pill) clip.addRoundRect(bounds, bounds.height() / 2, bounds.height() / 2, Path.Direction.CW)
            else clip.addOval(bounds, Path.Direction.CW)
        }
        val opacity = Color.alpha(color)
        val checkpoint = canvas.save()
        canvas.clipPath(clip)
        // Low-alpha bands approximate a highlight/shade gradient without an OEM-rewritten shader.
        val bandHeight = bounds.height() / 32
        for (band in 0 until 32) {
            val position = (band + .5f) / 32
            val strength = when {
                position < .4f -> .18f * (1 - position / .4f)
                position > .6f -> .18f * (position - .6f) / .4f
                else -> 0f
            }
            val rgb = if (position < .4f) 0xFFFFFF else 0x0B121B
            fill.color = ((opacity * strength).toInt() shl 24) or rgb
            canvas.drawRect(bounds.left, bounds.top + band * bandHeight,
                bounds.right, bounds.top + (band + 1) * bandHeight, fill)
        }
        canvas.restoreToCount(checkpoint)
        rim.strokeWidth = stroke
        rim.color = ((opacity * .55f).toInt() shl 24) or (color and 0xFFFFFF)
        canvas.drawPath(clip, rim)
        rim.color = ((opacity * .85f).toInt() shl 24) or 0xFFFFFF
        if (pill) {
            canvas.drawLine(bounds.left + bounds.height() / 2, bounds.top,
                bounds.right - bounds.height() / 2, bounds.top, rim)
        }
        innerBounds.set(bounds)
        innerBounds.inset(stroke * 2, stroke * 2)
        rim.strokeWidth = stroke * .65f
        rim.color = ((opacity * .2f).toInt() shl 24) or 0xFFFFFF
        if (!pill && innerBounds.width() > 0 && innerBounds.height() > 0) {
            canvas.drawOval(innerBounds, rim)
        }
    }
}
