package com.limelight.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/** Keeps the title icon centered and sized to the text, including TextView auto-sizing. */
internal class ScreenCombinationModeSpan(private val icon: Drawable) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = paint.textSize.toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val size = paint.textSize.toInt()
        icon.setBounds(0, 0, size, size)
        icon.setTint(paint.color)
        val iconTop = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent - size) / 2f
        val saved = canvas.save()
        canvas.translate(x, iconTop)
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
