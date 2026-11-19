package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R

/**
 * A small live snapshot of the element being edited. It reuses the element's
 * own renderer so the Inspector preview stays in sync with the stream canvas.
 */
class CrownInspectorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private var element: Element? = null

    fun bind(value: Element?) {
        element = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val inset = 6f * density
        val radius = 10f * density

        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.crown_input_background)
        paint.alpha = 255
        frame.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(frame, radius, radius, paint)

        val value = element ?: return
        val params = value.layoutParams
        val sourceWidth = (params?.width ?: value.width).coerceAtLeast(1)
        val sourceHeight = (params?.height ?: value.height).coerceAtLeast(1)
        val targetWidth = (width - inset * 2f).coerceAtLeast(1f)
        val targetHeight = (height - inset * 2f).coerceAtLeast(1f)
        val scale = minOf(targetWidth / sourceWidth, targetHeight / sourceHeight)
        val left = (width - sourceWidth * scale) / 2f
        val top = (height - sourceHeight * scale) / 2f

        canvas.save()
        canvas.clipRect(inset, inset, width - inset, height - inset)
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        value.draw(canvas)
        canvas.restore()
    }
}
