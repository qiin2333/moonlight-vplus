package com.limelight.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Suppress("OVERRIDE_DEPRECATION")
internal class MoonWheelDrawable(
    private val density: Float,
    graphiteColor: Int,
    accentColor: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wheelBounds = RectF()
    private var drawableAlpha = 255

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    var graphiteColor: Int = graphiteColor
        set(value) {
            field = value
            invalidateSelf()
        }

    var accentColor: Int = accentColor
        set(value) {
            field = value
            invalidateSelf()
        }

    init {
        setBounds(0, 0, (24f * density).roundToInt(), (24f * density).roundToInt())
    }

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val wheelAlpha = 1f - progress
        val wheelGraphite = multipliedAlpha(graphiteColor, wheelAlpha)
        val wheelAccent = multipliedAlpha(accentColor, wheelAlpha)

        val radius = 9f * density
        paint.color = wheelGraphite
        paint.strokeWidth = 1.8f * density
        wheelBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(wheelBounds, 112.5f, 315f, false, paint)

        val spokeLength = 6.5f * density
        val darkBottomLength = 4.2f * density
        for (index in 0 until 8) {
            val angle = Math.toRadians((-90f + index * 45f).toDouble())
            val xDirection = cos(angle).toFloat()
            val yDirection = sin(angle).toFloat()
            val length = if (index == 4) darkBottomLength else spokeLength
            val becomesBar = index == 2 || index == 6
            val animatedLength = if (becomesBar) {
                (6.5f + 0.5f * progress) * density
            } else {
                length
            }
            paint.color = if (becomesBar) graphiteColor else wheelGraphite
            paint.strokeWidth = (if (becomesBar) 1.55f + 0.45f * progress else 1.55f) * density
            canvas.drawLine(
                cx,
                cy,
                cx + xDirection * animatedLength,
                cy + yDirection * animatedLength,
                paint
            )

            if (index == 4) {
                paint.color = wheelAccent
                paint.strokeWidth = 1.7f * density
                canvas.drawLine(
                    cx + xDirection * darkBottomLength,
                    cy + yDirection * darkBottomLength,
                    cx + xDirection * (8.1f * density),
                    cy + yDirection * (8.1f * density),
                    paint
                )
            }
        }
    }

    private fun multipliedAlpha(color: Int, multiplier: Float): Int {
        val alpha = (Color.alpha(color) * multiplier * drawableAlpha / 255f)
            .roundToInt()
            .coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun getIntrinsicWidth(): Int = (24f * density).roundToInt()
    override fun getIntrinsicHeight(): Int = (24f * density).roundToInt()
    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
