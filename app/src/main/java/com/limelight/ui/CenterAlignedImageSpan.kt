package com.limelight.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan

/** Aligns inline icons to the font's visual center, including CJK host names. */
internal class CenterAlignedImageSpan(drawable: Drawable) : ImageSpan(drawable, ALIGN_BASELINE) {
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val icon = drawable
        val metrics = paint.fontMetricsInt
        val centerY = y + (metrics.ascent + metrics.descent) / 2f
        val saved = canvas.save()
        canvas.translate(x, centerY - icon.bounds.height() / 2f)
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
