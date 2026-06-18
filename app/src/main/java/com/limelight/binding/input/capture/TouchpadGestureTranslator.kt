package com.limelight.binding.input.capture

class TouchpadGestureTranslator(
    private val tapTimeoutMs: Int = DEFAULT_TAP_TIMEOUT_MS,
    movementThresholdPx: Float = DEFAULT_MOVEMENT_THRESHOLD_PX
) {
    data class ScrollDelta(val horizontal: Short, val vertical: Short)
    enum class EndResult { TAP, NONE }

    private var active = false
    private var scrolling = false
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private val movementThresholdSquared = movementThresholdPx * movementThresholdPx

    fun begin(x: Float, y: Float, eventTime: Long = 0L) {
        active = true
        scrolling = false
        startX = x
        startY = y
        startTime = eventTime
        lastX = x
        lastY = y
    }

    fun move(x: Float, y: Float): ScrollDelta? {
        if (!active) return null

        if (!scrolling) {
            val distanceX = x - startX
            val distanceY = y - startY
            if (distanceX * distanceX + distanceY * distanceY < movementThresholdSquared) {
                return null
            }
            scrolling = true
        }

        val delta = ScrollDelta(
            horizontal = (x - lastX).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
            vertical = (y - lastY).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        )
        lastX = x
        lastY = y
        return delta
    }

    fun end(x: Float = lastX, y: Float = lastY, eventTime: Long = startTime): EndResult {
        if (!active) return EndResult.NONE

        val duration = eventTime - startTime
        val distanceX = x - startX
        val distanceY = y - startY
        val isTap = !scrolling &&
            duration in 0..tapTimeoutMs.toLong() &&
            distanceX * distanceX + distanceY * distanceY < movementThresholdSquared
        active = false
        scrolling = false
        return if (isTap) EndResult.TAP else EndResult.NONE
    }

    fun cancel() {
        active = false
        scrolling = false
    }

    companion object {
        const val DEFAULT_TAP_TIMEOUT_MS = 500
        const val DEFAULT_MOVEMENT_THRESHOLD_PX = 24f
    }
}
