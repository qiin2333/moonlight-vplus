/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.atan2

open class AnalogStick(controller: VirtualController, context: Context, elementId: Int) :
    VirtualControllerElement(controller, context, elementId) {
    interface AnalogStickListener {
        fun onMovement(x: Float, y: Float)
        fun onClick()
        fun onDoubleClick()
        fun onRevoke()
    }
    private val listeners = mutableListOf<AnalogStickListener>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surface = ControllerSurface()
    private val ringBounds = RectF()
    private val capBounds = RectF()
    private val directionBounds = RectF()
    private var directionGradient: SweepGradient? = null
    private var directionRgb = -1
    private var radius = 1f
    private var knob = 1f
    private var deadzone = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastClick = Long.MIN_VALUE
    private var doubleClick = false
    private var active = false

    fun addAnalogStickListener(listener: AnalogStickListener) { listeners.add(listener) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = (correctWidth / 2f - 2 * defaultStrokeWidth).coerceAtLeast(1f)
        knob = correctWidth * .1f
        deadzone = correctWidth * .15f
    }

    override fun onElementDraw(canvas: Canvas) {
        if (!classic) {
            drawModernStick(canvas)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = defaultStrokeWidth
        paint.color = if (isPressed && doubleClick) pressedColor else defaultColor
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        if (classic) {
            paint.color = defaultColor
            canvas.drawCircle(width / 2f, height / 2f, deadzone, paint)
        }
        paint.color = if (isPressed) pressedColor else defaultColor
        canvas.drawCircle(width / 2f + offsetX, height / 2f + offsetY, knob, paint)
    }

    private fun tint(color: Int, amount: Float): Int =
        ((Color.alpha(color) * amount).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    private fun drawModernStick(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = defaultStrokeWidth
        ringBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        paint.color = tint(defaultColor, .42f)
        canvas.drawOval(ringBounds, paint)
        paint.color = tint(defaultColor, .13f)
        canvas.drawCircle(cx, cy, (radius - 3 * stroke).coerceAtLeast(1f), paint)

        val travel = (radius - knob).coerceAtLeast(1f)
        val magnitude = hypot(offsetX, offsetY)
        val strength = (magnitude / travel).coerceIn(0f, 1f)
        if (isPressed && magnitude > stroke) {
            val angle = Math.toDegrees(atan2(offsetY, offsetX).toDouble()).toFloat()
            val rgb = pressedColor and 0xFFFFFF
            if (directionGradient == null || directionRgb != rgb) {
                directionRgb = rgb
                directionGradient = SweepGradient(0f, 0f,
                    intArrayOf(0xFF000000.toInt() or rgb, rgb, rgb, 0xFF000000.toInt() or rgb),
                    floatArrayOf(0f, .12f, .88f, 1f))
            }
            val directionSave = canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angle)
            directionBounds.set(-radius, -radius, radius, radius)
            paint.shader = directionGradient
            paint.strokeWidth = stroke * 4
            paint.color = tint(pressedColor, .12f * strength)
            canvas.drawArc(directionBounds, -44f, 88f, false, paint)
            paint.strokeWidth = stroke * 1.8f
            paint.color = tint(pressedColor, .3f + .7f * strength)
            canvas.drawArc(directionBounds, -44f, 88f, false, paint)
            paint.shader = null
            canvas.restoreToCount(directionSave)
            val ux = offsetX / magnitude
            val uy = offsetY / magnitude
            paint.color = pressedColor
            paint.strokeWidth = stroke * 1.5f
            canvas.drawLine(cx + ux * (radius - 7 * stroke), cy + uy * (radius - 7 * stroke),
                cx + ux * (radius - 3 * stroke), cy + uy * (radius - 3 * stroke), paint)
        }

        // Enlarge only the visible cap; input travel and deadzone remain unchanged.
        val capRadius = (correctWidth * .17f).coerceAtMost(radius * .45f)
        val visualTravel = (radius - capRadius - 2 * stroke).coerceAtLeast(0f)
        val visualX = offsetX * visualTravel / travel
        val visualY = offsetY * visualTravel / travel
        // Draw in cap-local coordinates, so moving the stick doesn't recreate shaders.
        val checkpoint = canvas.save()
        canvas.translate(cx + visualX, cy + visualY)
        capBounds.set(-capRadius, -capRadius, capRadius, capRadius)
        surface.draw(canvas, capBounds, false, if (isPressed) pressedColor else defaultColor, stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke * .8f
        paint.color = tint(if (isPressed) pressedColor else defaultColor, .25f)
        canvas.drawCircle(0f, 0f, (capRadius - 3 * stroke).coerceAtLeast(1f), paint)
        canvas.restoreToCount(checkpoint)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            isPressed = false
            active = false
            offsetX = 0f
            offsetY = 0f
            listeners.forEach { it.onRevoke(); it.onMovement(0f, 0f) }
            invalidate()
            return true
        }
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val distance = hypot(dx, dy)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (classic && distance > radius) return false
            doubleClick = !doubleClick && lastClick != Long.MIN_VALUE && event.eventTime - lastClick <= 350
            listeners.forEach { if (doubleClick) it.onDoubleClick() else it.onClick() }
            lastClick = event.eventTime
            isPressed = true
            active = false
        }
        if (!isPressed) return true
        val travel = (radius - knob).coerceAtLeast(1f)
        val scale = if (distance > travel) travel / distance else 1f
        offsetX = dx * scale
        offsetY = dy * scale
        active = active || event.eventTime - lastClick > 150 || distance > deadzone
        if (active) listeners.forEach { it.onMovement(offsetX / travel, -offsetY / travel) }
        invalidate()
        return true
    }
}
