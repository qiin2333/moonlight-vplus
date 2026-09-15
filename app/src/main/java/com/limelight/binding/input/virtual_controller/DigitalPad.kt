/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent

class DigitalPad(controller: VirtualController, context: Context) : VirtualControllerElement(controller, context, EID_DPAD) {
    companion object {
        const val DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0
        const val DIGITAL_PAD_DIRECTION_LEFT = 1
        const val DIGITAL_PAD_DIRECTION_UP = 2
        const val DIGITAL_PAD_DIRECTION_RIGHT = 4
        const val DIGITAL_PAD_DIRECTION_DOWN = 8
        private const val DPAD_MARGIN = 5
    }
    fun interface DigitalPadListener { fun onDirectionChange(direction: Int) }
    private var direction = 0
    private val listeners = mutableListOf<DigitalPadListener>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cross = Path()
    private val segments = arrayOf(
        floatArrayOf(.04f, .36f, .32f, .64f), floatArrayOf(.36f, .04f, .64f, .32f),
        floatArrayOf(.68f, .36f, .96f, .64f), floatArrayOf(.36f, .68f, .64f, .96f))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cross.reset()
        cross.moveTo(w * .36f, h * .04f)
        cross.lineTo(w * .64f, h * .04f)
        cross.lineTo(w * .64f, h * .36f)
        cross.lineTo(w * .96f, h * .36f)
        cross.lineTo(w * .96f, h * .64f)
        cross.lineTo(w * .64f, h * .64f)
        cross.lineTo(w * .64f, h * .96f)
        cross.lineTo(w * .36f, h * .96f)
        cross.lineTo(w * .36f, h * .64f)
        cross.lineTo(w * .04f, h * .64f)
        cross.lineTo(w * .04f, h * .36f)
        cross.lineTo(w * .36f, h * .36f)
        cross.close()
    }
    fun addDigitalPadListener(listener: DigitalPadListener) { listeners.add(listener) }

    override fun onElementDraw(canvas: Canvas) {
        paint.strokeWidth = defaultStrokeWidth
        paint.style = Paint.Style.STROKE
        if (classic) { drawClassic(canvas); return }
        // Four open segments retain a single hit surface, including diagonal corners.
        val xbox = virtualController.layoutStyle == VirtualControllerLayout.XBOX
        if (xbox) {
            paint.color = defaultColor
            canvas.drawPath(cross, paint)
        }
        segments.forEachIndexed { index, bounds ->
            val active = direction and (1 shl index) != 0
            if (xbox && !active) return@forEachIndexed
            paint.color = if (active) pressedColor else defaultColor
            paint.style = Paint.Style.STROKE
            rect.set(width * bounds[0], height * bounds[1], width * bounds[2], height * bounds[3])
            drawSegment(canvas)
            if (active) {
                paint.style = Paint.Style.FILL
                paint.alpha = paint.alpha / 5
                drawSegment(canvas)
            }
        }
    }

    private fun drawSegment(canvas: Canvas) {
        if (virtualController.layoutStyle == VirtualControllerLayout.NS) canvas.drawOval(rect, paint)
        else canvas.drawRoundRect(rect, correctWidth * .045f, correctWidth * .045f, paint)
    }

    private fun drawClassic(canvas: Canvas) {
        val inset = paint.strokeWidth + DPAD_MARGIN
        val x33 = getPercent(width, 33)
        val x66 = getPercent(width, 66)
        val y33 = getPercent(height, 33)
        val y66 = getPercent(height, 66)
        paint.style = Paint.Style.STROKE
        fun colorFor(mask: Int) {
            paint.color = if (direction and mask == mask) pressedColor else defaultColor
        }
        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            paint.color = defaultColor
            canvas.drawRect(getPercent(width, 36), getPercent(height, 36),
                getPercent(width, 63), getPercent(height, 63), paint)
        }
        colorFor(DIGITAL_PAD_DIRECTION_LEFT)
        canvas.drawRect(inset, y33, x33, y66, paint)
        colorFor(DIGITAL_PAD_DIRECTION_UP)
        canvas.drawRect(x33, inset, x66, y33, paint)
        colorFor(DIGITAL_PAD_DIRECTION_RIGHT)
        canvas.drawRect(x66, y33, width - inset, y66, paint)
        colorFor(DIGITAL_PAD_DIRECTION_DOWN)
        canvas.drawRect(x33, y66, x66, height - inset, paint)

        colorFor(DIGITAL_PAD_DIRECTION_LEFT or DIGITAL_PAD_DIRECTION_UP)
        canvas.drawLine(inset, y33, x33, inset, paint)
        colorFor(DIGITAL_PAD_DIRECTION_UP or DIGITAL_PAD_DIRECTION_RIGHT)
        canvas.drawLine(x66, inset, width - inset, y33, paint)
        colorFor(DIGITAL_PAD_DIRECTION_RIGHT or DIGITAL_PAD_DIRECTION_DOWN)
        canvas.drawLine(width - paint.strokeWidth, y66, x66, height - inset, paint)
        colorFor(DIGITAL_PAD_DIRECTION_DOWN or DIGITAL_PAD_DIRECTION_LEFT)
        canvas.drawLine(x33, height - inset, inset, y66, paint)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        direction = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var result = 0
                if (event.x < width * .33f) result = result or DIGITAL_PAD_DIRECTION_LEFT
                if (event.x > width * .66f) result = result or DIGITAL_PAD_DIRECTION_RIGHT
                if (event.y < height * .33f) result = result or DIGITAL_PAD_DIRECTION_UP
                if (event.y > height * .66f) result = result or DIGITAL_PAD_DIRECTION_DOWN
                result
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            else -> return true
        }
        listeners.forEach { it.onDirectionChange(direction) }
        invalidate()
        return true
    }
}
