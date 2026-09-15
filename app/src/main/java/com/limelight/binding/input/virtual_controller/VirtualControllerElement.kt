/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

abstract class VirtualControllerElement(
    protected val virtualController: VirtualController,
    context: Context,
    val elementId: Int,
) : View(context) {
    init {
        // Game overlays supply their own contrast; automatic darkening can invert their highlights.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
    }

    companion object {
        const val EID_DPAD = 1
        const val EID_LT = 2
        const val EID_RT = 3
        const val EID_LB = 4
        const val EID_RB = 5
        const val EID_A = 6
        const val EID_B = 7
        const val EID_X = 8
        const val EID_Y = 9
        const val EID_BACK = 10
        const val EID_START = 11
        const val EID_LS = 12
        const val EID_RS = 13
        const val EID_LSB = 14
        const val EID_RSB = 15
        const val EID_GDB = 16
    }

    protected val classic get() = virtualController.layoutStyle == VirtualControllerLayout.CLASSIC
    private var normalColor = 0xF0888888.toInt()
    protected var pressedColor = 0xF00000FF.toInt()
    protected val defaultColor get() = when (virtualController.controllerMode) {
        VirtualController.ControllerMode.MoveButtons -> 0xF0FF5555.toInt()
        VirtualController.ControllerMode.ResizeButtons -> 0xF0FF55FF.toInt()
        else -> normalColor
    }
    protected val defaultStrokeWidth get() = if (classic) {
        (resources.displayMetrics.heightPixels * 0.004f).coerceAtLeast(1f)
    } else (resources.displayMetrics.density * 1.25f).coerceAtLeast(1f)
    protected val correctWidth get() = minOf(width, height)
    protected fun getPercent(value: Int, percent: Int) = value * percent / 100f
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var startWidth = 0
    private var startHeight = 0

    override fun onDraw(canvas: Canvas) {
        onElementDraw(canvas)
        if (pointerId != MotionEvent.INVALID_POINTER_ID &&
            virtualController.controllerMode != VirtualController.ControllerMode.Active) {
            editPaint.color = 0xF000FF00.toInt()
            editPaint.style = Paint.Style.STROKE
            editPaint.strokeWidth = defaultStrokeWidth
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, editPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pointerId = event.getPointerId(0)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            releaseTouch()
            return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return true
        // Track the owning pointer, even when Android reorders pointer indices.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return true
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex != index) return true
        val action = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) MotionEvent.ACTION_UP else event.actionMasked
        val single = MotionEvent.obtain(event.downTime, event.eventTime, action,
            event.getX(index), event.getY(index), event.metaState)
        val handled = if (virtualController.controllerMode == VirtualController.ControllerMode.Active) {
            onElementTouchEvent(single)
        } else {
            edit(single)
            true
        }
        single.recycle()
        if (!handled || action == MotionEvent.ACTION_UP) pointerId = MotionEvent.INVALID_POINTER_ID
        return handled
    }

    private fun edit(event: MotionEvent) {
        val params = layoutParams as FrameLayout.LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startWidth = width
                startHeight = height
            }
            MotionEvent.ACTION_MOVE -> {
                val parentView = parent as? View ?: return
                if (virtualController.controllerMode == VirtualController.ControllerMode.MoveButtons) {
                    params.leftMargin = (x + event.x - startX).toInt().coerceIn(0, maxOf(0, parentView.width - width))
                    params.topMargin = (y + event.y - startY).toInt().coerceIn(0, maxOf(0, parentView.height - height))
                } else {
                    val minimum = if (classic) 20 else (48 * resources.displayMetrics.density).toInt()
                    params.width = (startWidth + event.x - startX).toInt().coerceAtLeast(minOf(minimum, startWidth))
                        .coerceAtMost(maxOf(1, parentView.width - params.leftMargin))
                    params.height = (startHeight + event.y - startY).toInt().coerceAtLeast(minOf(minimum, startHeight))
                        .coerceAtMost(maxOf(1, parentView.height - params.topMargin))
                }
                requestLayout()
            }
        }
        invalidate()
    }

    internal fun releaseTouch() {
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        onElementTouchEvent(cancel)
        cancel.recycle()
        pointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    protected abstract fun onElementDraw(canvas: Canvas)
    abstract fun onElementTouchEvent(event: MotionEvent): Boolean

    fun setColors(normalColor: Int, pressedColor: Int) {
        this.normalColor = normalColor
        this.pressedColor = pressedColor
        invalidate()
    }

    fun setOpacity(opacity: Int) {
        val alpha = opacity.coerceIn(0, 100) * 255 / 100
        normalColor = (alpha shl 24) or (normalColor and 0xFFFFFF)
        pressedColor = (alpha shl 24) or (pressedColor and 0xFFFFFF)
        invalidate()
    }

    val configuration: JSONObject get() {
        val params = layoutParams as FrameLayout.LayoutParams
        return JSONObject().put("LEFT", params.leftMargin).put("TOP", params.topMargin)
            .put("WIDTH", params.width).put("HEIGHT", params.height)
    }

    fun loadConfiguration(configuration: JSONObject) {
        val params = layoutParams as FrameLayout.LayoutParams
        params.leftMargin = configuration.getInt("LEFT")
        params.topMargin = configuration.getInt("TOP")
        params.width = configuration.getInt("WIDTH")
        params.height = configuration.getInt("HEIGHT")
        requestLayout()
    }
}
