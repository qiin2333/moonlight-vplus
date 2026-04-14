package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket

class AbsoluteTouchContext(
    private val conn: NvConnection,
    private val actionIndex: Int,
    private var targetView: View
) : TouchContext {
    private var lastTouchDownX = 0
    private var lastTouchDownY = 0
    private var lastTouchDownTime: Long = 0
    private var lastTouchUpX = 0
    private var lastTouchUpY = 0
    private var lastTouchUpTime: Long = 0
    private var lastTouchLocationX = 0
    private var lastTouchLocationY = 0
    private var cancelled = false
    private var confirmedLongPress = false
    private var confirmedTap = false

    private val handler: Handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        // This timer should have already expired, but cancel it just in case
        cancelTapDownTimer()

        // Switch from a left click to a right click after a long press
        confirmedLongPress = true
        if (confirmedTap) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
    }

    private val tapDownRunnable = Runnable {
        // Start our tap
        tapConfirmed()
    }

    private val leftButtonUpRunnable = Runnable {
        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
    }

    fun setTargetView(view: View) {
        this.targetView = view
    }

    override fun getActionIndex(): Int = actionIndex

    override fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean {
        if (!isNewFinger) {
            return true
        }

        lastTouchLocationX = eventX
        lastTouchDownX = eventX
        lastTouchLocationY = eventY
        lastTouchDownY = eventY
        lastTouchDownTime = eventTime
        cancelled = false
        confirmedTap = false
        confirmedLongPress = false

        if (actionIndex == 0) {
            startTapDownTimer()
            startLongPressTimer()
        }

        return true
    }

    private fun distanceExceeds(deltaX: Int, deltaY: Int, limit: Double): Boolean {
        return Math.sqrt(Math.pow(deltaX.toDouble(), 2.0) + Math.pow(deltaY.toDouble(), 2.0)) > limit
    }

    private fun updatePosition(eventX: Int, eventY: Int) {
        val clampedX = eventX.coerceIn(0, targetView.width)
        val clampedY = eventY.coerceIn(0, targetView.height)

        conn.sendMousePosition(
            clampedX.toShort(), clampedY.toShort(),
            targetView.width.toShort(), targetView.height.toShort()
        )
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        if (actionIndex == 0) {
            cancelLongPressTimer()
            cancelTapDownTimer()

            if (confirmedLongPress) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            } else if (confirmedTap) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
            } else {
                tapConfirmed()

                handler.removeCallbacks(leftButtonUpRunnable)
                handler.postDelayed(leftButtonUpRunnable, 100)
            }
        }

        lastTouchLocationX = eventX
        lastTouchUpX = eventX
        lastTouchLocationY = eventY
        lastTouchUpY = eventY
        lastTouchUpTime = eventTime
    }

    private fun startLongPressTimer() {
        cancelLongPressTimer()
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD.toLong())
    }

    private fun cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun startTapDownTimer() {
        cancelTapDownTimer()
        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD.toLong())
    }

    private fun cancelTapDownTimer() {
        handler.removeCallbacks(tapDownRunnable)
    }

    private fun tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return
        }

        confirmedTap = true
        cancelTapDownTimer()

        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
            distanceExceeds(
                lastTouchDownX - lastTouchUpX,
                lastTouchDownY - lastTouchUpY,
                DOUBLE_TAP_DISTANCE_THRESHOLD.toDouble()
            )
        ) {
            updatePosition(lastTouchDownX, lastTouchDownY)
        }
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }
        if (actionIndex == 0) {
            if (distanceExceeds(
                    eventX - lastTouchDownX, eventY - lastTouchDownY,
                    LONG_PRESS_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                cancelLongPressTimer()
            }
            if (confirmedTap || distanceExceeds(
                    eventX - lastTouchDownX, eventY - lastTouchDownY,
                    TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                tapConfirmed()
                updatePosition(eventX, eventY)
            }
        } else if (actionIndex == 1) {
            conn.sendMouseHighResScroll(((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR).toShort())
        }

        lastTouchLocationX = eventX
        lastTouchLocationY = eventY

        return true
    }

    override fun cancelTouch() {
        cancelled = true

        cancelLongPressTimer()
        cancelTapDownTimer()

        if (confirmedLongPress) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
        } else if (confirmedTap) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun setPointerCount(pointerCount: Int) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch()
        }
    }

    companion object {
        private const val SCROLL_SPEED_FACTOR = 3

        private const val LONG_PRESS_TIME_THRESHOLD = 650
        private const val LONG_PRESS_DISTANCE_THRESHOLD = 30

        private const val DOUBLE_TAP_TIME_THRESHOLD = 250
        private const val DOUBLE_TAP_DISTANCE_THRESHOLD = 60

        private const val TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100
        private const val TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20
    }
}
