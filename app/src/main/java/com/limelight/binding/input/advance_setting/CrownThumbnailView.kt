package com.limelight.binding.input.advance_setting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager

/** Reusable, data-driven Crown layout preview for profile surfaces. */
class CrownThumbnailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private var preview: CrownProfileShareManager.ProfilePreview? = null

    fun bind(value: CrownProfileShareManager.ProfilePreview?) {
        preview = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 9f * density
        // Same deliberately dark preview canvas as the inspector and the store:
        // the thumbnail must read as "this layout over the game picture".
        val canvasTop = ContextCompat.getColor(context, R.color.crown_preview_bg_top)
        val canvasBottom = ContextCompat.getColor(context, R.color.crown_preview_bg_bottom)
        val previewInk = ContextCompat.getColor(context, R.color.crown_preview_ink)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(canvasTop, canvasBottom),
            null,
            Shader.TileMode.CLAMP
        )
        frame.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.shader = null

        // Keep the thumbnail readable at every card size without turning it into
        // a miniature editor: a couple of quiet guide lines establish the canvas
        // while the controls stay the visual focus.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (0.7f * density).coerceAtLeast(1f)
        paint.color = previewInk
        paint.alpha = 18
        canvas.drawLine(width * 0.33f, 0f, width * 0.33f, height.toFloat(), paint)
        canvas.drawLine(width * 0.66f, 0f, width * 0.66f, height.toFloat(), paint)
        canvas.drawLine(0f, height * 0.5f, width.toFloat(), height * 0.5f, paint)

        val value = preview?.takeUnless { it.isEmpty }
        if (value == null) {
            drawFallback(canvas, density)
            return
        }

        val scale = minOf(
            width / value.widthPx.toFloat().coerceAtLeast(1f),
            height / value.heightPx.toFloat().coerceAtLeast(1f)
        )
        val canvasWidth = value.widthPx * scale
        val canvasHeight = value.heightPx * scale
        val offsetX = (width - canvasWidth) / 2f
        val offsetY = (height - canvasHeight) / 2f
        val outline = previewInk

        value.elements
            .sortedBy { if (it.kind == CrownProfileShareManager.PreviewElementKind.GROUP) 0 else 1 }
            .forEach { element ->
                val elementWidth = (element.width * scale).coerceAtLeast(2f)
                val elementHeight = (element.height * scale).coerceAtLeast(2f)
                val centerX = offsetX + element.centerX * scale
                val centerY = offsetY + element.centerY * scale
                val left = centerX - elementWidth / 2f
                val top = centerY - elementHeight / 2f
                val alpha = (element.alpha * 190f).toInt().coerceIn(18, 190)

                paint.color = element.fillColor
                paint.alpha = when (element.kind) {
                    CrownProfileShareManager.PreviewElementKind.GROUP -> 0
                    CrownProfileShareManager.PreviewElementKind.STICK -> 36
                    CrownProfileShareManager.PreviewElementKind.WHEEL -> 28
                    CrownProfileShareManager.PreviewElementKind.PAD -> 56
                    CrownProfileShareManager.PreviewElementKind.BUTTON -> 76
                }.coerceAtMost(alpha)
                paint.style = Paint.Style.FILL
                when (element.kind) {
                    CrownProfileShareManager.PreviewElementKind.STICK -> {
                        canvas.drawCircle(centerX, centerY, minOf(elementWidth, elementHeight) / 2f, paint)
                    }
                    CrownProfileShareManager.PreviewElementKind.WHEEL -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (1.2f * density).coerceAtLeast(1f)
                        canvas.drawCircle(centerX, centerY, minOf(elementWidth, elementHeight) / 2f, paint)
                    }
                    CrownProfileShareManager.PreviewElementKind.GROUP -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (1f * density).coerceAtLeast(1f)
                        paint.color = outline
                        paint.alpha = 120
                        canvas.drawRoundRect(
                            left, top, left + elementWidth, top + elementHeight,
                            5f * density, 5f * density, paint
                        )
                    }
                    CrownProfileShareManager.PreviewElementKind.PAD,
                    CrownProfileShareManager.PreviewElementKind.BUTTON -> {
                        canvas.drawRoundRect(
                            left, top, left + elementWidth, top + elementHeight,
                            minOf(elementWidth, elementHeight) * 0.18f,
                            minOf(elementWidth, elementHeight) * 0.18f,
                            paint
                        )
                    }
                }
                if (element.kind != CrownProfileShareManager.PreviewElementKind.GROUP) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (0.8f * density).coerceAtLeast(1f)
                    paint.color = element.fillColor
                    paint.alpha = (alpha * 0.88f).toInt().coerceIn(42, 190)
                    if (element.kind == CrownProfileShareManager.PreviewElementKind.STICK ||
                        element.kind == CrownProfileShareManager.PreviewElementKind.WHEEL) {
                        canvas.drawCircle(centerX, centerY, minOf(elementWidth, elementHeight) / 2f, paint)
                    } else {
                        canvas.drawRoundRect(
                            left, top, left + elementWidth, top + elementHeight,
                            minOf(elementWidth, elementHeight) * 0.18f,
                            minOf(elementWidth, elementHeight) * 0.18f,
                            paint
                        )
                    }
                }
            }
        paint.alpha = 255
        paint.shader = null
    }

    private fun drawFallback(canvas: Canvas, density: Float) {
        val muted = ContextCompat.getColor(context, R.color.crown_text_secondary)
        val accent = ContextCompat.getColor(context, R.color.ui_shell_accent)
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.alpha = 120
        canvas.drawRoundRect(
            8f * density,
            8f * density,
            width * 0.55f,
            13f * density,
            3f * density,
            3f * density,
            paint
        )
        paint.color = accent
        paint.alpha = 170
        canvas.drawCircle(width * 0.72f, height * 0.55f, minOf(width, height) * 0.18f, paint)
        paint.alpha = 110
        canvas.drawRoundRect(
            width * 0.12f,
            height * 0.62f,
            width * 0.4f,
            height * 0.82f,
            4f * density,
            4f * density,
            paint
        )
        paint.alpha = 255
    }
}
