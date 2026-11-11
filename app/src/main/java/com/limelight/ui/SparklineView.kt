package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** 副屏「监控」页的帧率历史折线图：保留最近 [capacity] 个采样，虚线标出目标帧率。 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val capacity = 90
    private val samples = FloatArray(capacity)
    private var count = 0
    private var target = 60f

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF66C0F4.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55A9C2D2; style = Paint.Style.STROKE; strokeWidth = 1f * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f)
    }
    private val path = Path()
    private val area = Path()

    fun setTarget(fps: Int) {
        if (fps > 0 && fps.toFloat() != target) { target = fps.toFloat(); invalidate() }
    }

    fun addSample(value: Float) {
        if (count == capacity) {
            System.arraycopy(samples, 1, samples, 0, capacity - 1)
            samples[capacity - 1] = value
        } else {
            samples[count++] = value
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = 4f * d; val bottom = h - 2f * d
        val maxV = maxOf(target * 1.1f, samples.take(count).maxOrNull() ?: 0f)
        fun y(v: Float) = bottom - (v.coerceIn(0f, maxV) / maxV) * (bottom - top)
        canvas.drawLine(0f, y(target), w, y(target), guide)
        if (count < 2) return
        val step = w / (capacity - 1)
        val x0 = w - (count - 1) * step
        path.reset(); area.reset()
        for (i in 0 until count) {
            val x = x0 + i * step; val yy = y(samples[i])
            if (i == 0) { path.moveTo(x, yy); area.moveTo(x, bottom); area.lineTo(x, yy) }
            else { path.lineTo(x, yy); area.lineTo(x, yy) }
        }
        area.lineTo(x0 + (count - 1) * step, bottom); area.close()
        fill.shader = LinearGradient(0f, top, 0f, bottom, 0x5566C0F4, 0x0066C0F4, Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
}
