package com.limelight.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/** Aligns the icon with the visible host-name glyphs at the current auto-sized text size. */
internal class ScreenCombinationModeSpan(
    private val icon: Drawable,
    private val hostName: String
) : ReplacementSpan() {
    private val textBounds = Rect()

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
        // Font metrics include unused ascender/descender space, which makes an
        // icon look too high beside names such as "moon". Match the visible name.
        paint.getTextBounds(hostName, 0, hostName.length, textBounds)
        val textCenter = if (textBounds.isEmpty) {
            (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        } else {
            (textBounds.top + textBounds.bottom) / 2f
        }
        val iconTop = y + textCenter - size / 2f
        val saved = canvas.save()
        canvas.translate(x, iconTop)
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
