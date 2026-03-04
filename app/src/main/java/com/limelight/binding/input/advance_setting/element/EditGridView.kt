package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class EditGridView(context: Context) : View(context) {

    private val paint = Paint().apply {
        color = 0xFF00F5FF.toInt()
        strokeWidth = 2f // 设置网格线宽为2像素
    }
    private var editGridWidth = 1

    init {
        alpha = 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (editGridWidth < MIN_DISPLAY_WIDTH) return
        drawGrid(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        // 绘制垂直线
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height, paint)
            x += editGridWidth
        }

        // 绘制水平线
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

    companion object {
        private const val MIN_DISPLAY_WIDTH = 3
    }
}
