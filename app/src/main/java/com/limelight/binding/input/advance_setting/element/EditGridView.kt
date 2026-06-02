package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R

class EditGridView(context: Context) : View(context) {

    private val paint = Paint().apply {
        color = 0xFF00F5FF.toInt()
        strokeWidth = 2f
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crown_alignment_guide)
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private var editGridWidth = 1
    private var verticalGuide = NO_GUIDE
    private var horizontalGuide = NO_GUIDE

    init {
        alpha = 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (editGridWidth >= MIN_DISPLAY_WIDTH) {
            drawGrid(canvas)
        }
        drawAlignmentGuides(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height, paint)
            x += editGridWidth
        }

        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width, y, paint)
            y += editGridWidth
        }
    }

    fun setEditGridWidth(editGridWidth: Int) {
        this.editGridWidth = editGridWidth
        invalidate()
    }

    fun setAlignmentGuides(verticalGuide: Float, horizontalGuide: Float) {
        this.verticalGuide = verticalGuide
        this.horizontalGuide = horizontalGuide
        invalidate()
    }

    fun clearAlignmentGuides() {
        setAlignmentGuides(NO_GUIDE, NO_GUIDE)
    }

    private fun drawAlignmentGuides(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (!verticalGuide.isNaN()) {
            canvas.drawLine(verticalGuide, 0f, verticalGuide, height, guidePaint)
        }
        if (!horizontalGuide.isNaN()) {
            canvas.drawLine(0f, horizontalGuide, width, horizontalGuide, guidePaint)
        }
    }

    companion object {
        const val NO_GUIDE = Float.NaN
        private const val MIN_DISPLAY_WIDTH = 3
    }
}
