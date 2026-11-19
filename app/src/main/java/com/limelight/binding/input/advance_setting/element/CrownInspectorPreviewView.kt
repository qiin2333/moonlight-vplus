package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * A small live snapshot of the element being edited. It reuses the element's
 * own renderer so the Inspector preview stays in sync with the stream canvas.
 * The host card draws the dark canvas; this view only places the element.
 */
class CrownInspectorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var element: Element? = null

    fun bind(value: Element?) {
        element = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val inset = 6f * density

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
