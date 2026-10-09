package com.limelight.binding.input.virtual_controller

import android.view.MotionEvent
import android.os.Build
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.limelight.ui.FloatingButtonNormalizedPosition
import com.limelight.ui.FloatingButtonPlacement
import com.limelight.ui.FloatingButtonViewport
import kotlin.math.abs

internal class OscSettingsButtonPositionController(
    private val button: View,
    private val container: FrameLayout,
    private val store: OscSettingsButtonStore
) : View.OnTouchListener, View.OnLayoutChangeListener {
    private val touchSlop = ViewConfiguration.get(button.context).scaledTouchSlop
    private val applyPosition = Runnable(::restorePosition)
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var swallowUntilDown = false
    private var gestureViewport: FloatingButtonViewport? = null
    private var disposed = false

    init {
        button.setOnTouchListener(this)
        button.addOnLayoutChangeListener(this)
        container.addOnLayoutChangeListener(this)
    }

    fun setDragEnabled(enabled: Boolean) {
        cancelGesture()
        store.dragEnabled = enabled
    }

    fun resetPosition() {
        cancelGesture()
        store.resetPosition()
        restorePosition()
    }

    fun cancelGesture() {
        if (pointerId != -1) swallowUntilDown = true
        pointerId = -1
        dragging = false
        gestureViewport = null
        button.isPressed = false
        container.requestDisallowInterceptTouchEvent(false)
        restorePosition()
        requestPlacement()
    }

    fun requestPlacement() {
        button.removeCallbacks(applyPosition)
        if (!disposed) button.post(applyPosition)
    }

    fun dispose() {
        cancelGesture()
        disposed = true
        button.removeCallbacks(applyPosition)
        button.setOnTouchListener(null)
        button.removeOnLayoutChangeListener(this)
        container.removeOnLayoutChangeListener(this)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (disposed) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowUntilDown = false
            if (!store.dragEnabled) return false
            pointerId = event.getPointerId(0)
            downX = event.rawX
            downY = event.rawY
            startX = button.x
            startY = button.y
            gestureViewport = viewport()
            dragging = false
            button.isPressed = true
            container.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (swallowUntilDown) return true
        if (pointerId == -1) return false
        val trackedPointerLifted = event.actionMasked == MotionEvent.ACTION_UP ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == pointerId)
        if (trackedPointerLifted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            event.flags and MotionEvent.FLAG_CANCELED != 0) {
            cancelGesture()
            return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0 || gestureViewport != viewport()) {
            cancelGesture()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Indexed raw coordinates are API 29; this also works on API 22.
                val dx = event.rawX + event.getX(index) - event.x - downX
                val dy = event.rawY + event.getY(index) - event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    button.isPressed = false
                    val point = FloatingButtonPlacement.clampCustom(startX + dx, startY + dy, viewport())
                    button.x = point.x.toFloat()
                    button.y = point.y.toFloat()
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                if (wasDragging) {
                    val bounds = viewport()
                    store.save(FloatingButtonPlacement.normalize(
                        FloatingButtonPlacement.clampCustom(button.x, button.y, bounds), bounds))
                }
                cancelGesture()
                if (!wasDragging) view.performClick()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) cancelGesture()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        if (gestureViewport != null && gestureViewport != viewport()) cancelGesture()
        requestPlacement()
    }

    private fun restorePosition() {
        if (disposed || pointerId != -1 || button.parent !== container ||
            button.width <= 0 || button.height <= 0 || container.width <= 0 || container.height <= 0) return
        val saved = store.position()
        val point = FloatingButtonPlacement.resolve(
            if (saved == null) FloatingButtonPlacement.POSITION_TOP_LEFT else FloatingButtonPlacement.POSITION_CUSTOM,
            saved ?: FloatingButtonNormalizedPosition(0f, 0f), viewport())
        button.x = point.x.toFloat()
        button.y = point.y.toFloat()
    }

    private fun viewport() = FloatingButtonViewport(container.width, container.height,
        button.width, button.height,
        minOf((8 * button.resources.displayMetrics.density).toInt(), button.width / 4))
}
