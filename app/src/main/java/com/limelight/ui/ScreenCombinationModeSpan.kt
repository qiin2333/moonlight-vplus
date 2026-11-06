package com.limelight.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Aligns visible icon artwork with the host font's cap/body height, excluding descenders. */
internal class ScreenCombinationModeSpan(
    private val icon: Drawable,
    hostName: String
) : ReplacementSpan() {
    private val referenceGlyph = referenceGlyphFor(hostName)
    private val textBounds = Rect()
    private val artworkBounds by lazy { measureArtworkBounds() }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val geometry = geometry(paint)
        fm?.apply {
            val metrics = paint.fontMetricsInt
            ascent = min(metrics.ascent, geometry.top)
            descent = max(metrics.descent, geometry.bottom)
            top = min(metrics.top, ascent)
            bottom = max(metrics.bottom, descent)
            leading = metrics.leading
        }
        return geometry.width
    }

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
        val geometry = geometry(paint)
        icon.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        icon.setTint(paint.color)
        val saved = canvas.save()
        canvas.translate(x, (y + geometry.top).toFloat())
        canvas.scale(geometry.scale, geometry.scale)
        canvas.translate(-artworkBounds.left.toFloat(), -artworkBounds.top.toFloat())
        icon.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun geometry(paint: Paint): Geometry {
        paint.getTextBounds(referenceGlyph, 0, referenceGlyph.length, textBounds)
        val metrics = paint.fontMetricsInt
        val top = if (textBounds.isEmpty) metrics.ascent else textBounds.top
        val bottom = if (textBounds.isEmpty) metrics.descent else textBounds.bottom
        val scale = (bottom - top).coerceAtLeast(1).toFloat() / artworkBounds.height()
        return Geometry(top, bottom, scale, ceil(artworkBounds.width() * scale).toInt())
    }

    private fun measureArtworkBounds(): Rect {
        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val oldBounds = Rect(icon.bounds)
        return try {
            icon.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            val visible = Rect(SAMPLE_SIZE, SAMPLE_SIZE, 0, 0)
            for (y in 0 until SAMPLE_SIZE) {
                for (x in 0 until SAMPLE_SIZE) {
                    if (Color.alpha(pixels[y * SAMPLE_SIZE + x]) == 0) continue
                    visible.left = min(visible.left, x)
                    visible.top = min(visible.top, y)
                    visible.right = max(visible.right, x + 1)
                    visible.bottom = max(visible.bottom, y + 1)
                }
            }
            if (visible.isEmpty) Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE) else visible
        } catch (_: RuntimeException) {
            Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        } finally {
            icon.bounds = oldBounds
            bitmap.recycle()
        }
    }

    private data class Geometry(val top: Int, val bottom: Int, val scale: Float, val width: Int)

    private companion object {
        const val SAMPLE_SIZE = 96

        fun referenceGlyphFor(name: String): String {
            var offset = 0
            while (offset < name.length) {
                val codePoint = name.codePointAt(offset)
                if (Character.isLetterOrDigit(codePoint)) {
                    return String(Character.toChars(Character.toUpperCase(codePoint)))
                }
                offset += Character.charCount(codePoint)
            }
            return "H"
        }
    }
}
