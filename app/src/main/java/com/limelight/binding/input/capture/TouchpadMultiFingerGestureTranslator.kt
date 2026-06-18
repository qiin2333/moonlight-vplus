package com.limelight.binding.input.capture

import kotlin.math.abs
import kotlin.math.hypot

class TouchpadMultiFingerGestureTranslator {
    data class Point(val x: Float, val y: Float)

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    sealed interface Action {
        data class Scroll(val horizontal: Float, val vertical: Float) : Action
        data class Pinch(val scale: Float) : Action
        data object TwoFingerTap : Action
        data class ThreeFingerSwipe(val direction: Direction) : Action
        data object ThreeFingerTap : Action
    }

    private enum class Mode { IDLE, TWO_PENDING, SCROLL, PINCH, THREE_PENDING, THREE_TRIGGERED }

    private var mode = Mode.IDLE
    private var startTime = 0L
    private var startCentroid = Point(0f, 0f)
    private var lastCentroid = Point(0f, 0f)
    private var startSpan = 0f
    private var lastSpan = 0f

    val isActive: Boolean
        get() = mode != Mode.IDLE

    fun pointerDown(points: List<Point>, eventTime: Long): List<Action> {
        when (points.size) {
            2 -> beginTwoFinger(points, eventTime)
            3 -> beginThreeFinger(points, eventTime)
        }
        return emptyList()
    }

    fun move(points: List<Point>): List<Action> {
        return when {
            points.size >= 3 && (mode == Mode.THREE_PENDING || mode == Mode.THREE_TRIGGERED) ->
                moveThreeFinger(points.take(3))
            points.size == 2 && mode in setOf(Mode.TWO_PENDING, Mode.SCROLL, Mode.PINCH) ->
                moveTwoFinger(points)
            else -> emptyList()
        }
    }

    fun pointerUp(points: List<Point>, eventTime: Long): List<Action> {
        val actions = if (points.size == 2 && mode == Mode.TWO_PENDING &&
            eventTime - startTime <= TWO_FINGER_TAP_TIMEOUT_MS
        ) {
            listOf(Action.TwoFingerTap)
        } else if (points.size >= 3 && mode == Mode.THREE_PENDING) {
            val centroid = centroid(points.take(3))
            val distance = distance(startCentroid, centroid)
            if (eventTime - startTime <= THREE_FINGER_TAP_TIMEOUT_MS && distance < THREE_FINGER_TAP_SLOP_PX) {
                listOf(Action.ThreeFingerTap)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        mode = Mode.IDLE
        return actions
    }

    fun cancel() {
        mode = Mode.IDLE
    }

    private fun beginTwoFinger(points: List<Point>, eventTime: Long) {
        mode = Mode.TWO_PENDING
        startTime = eventTime
        startCentroid = centroid(points)
        lastCentroid = startCentroid
        startSpan = span(points)
        lastSpan = startSpan
    }

    private fun beginThreeFinger(points: List<Point>, eventTime: Long) {
        mode = Mode.THREE_PENDING
        startTime = eventTime
        startCentroid = centroid(points.take(3))
        lastCentroid = startCentroid
    }

    private fun moveTwoFinger(points: List<Point>): List<Action> {
        val currentCentroid = centroid(points)
        val currentSpan = span(points)

        if (mode == Mode.TWO_PENDING) {
            val centroidTravel = distance(startCentroid, currentCentroid)
            val spanChange = abs(currentSpan - startSpan)
            mode = when {
                spanChange >= TWO_FINGER_START_SLOP_PX && spanChange > centroidTravel -> Mode.PINCH
                centroidTravel >= TWO_FINGER_START_SLOP_PX -> Mode.SCROLL
                else -> return emptyList()
            }
        }

        val action = when (mode) {
            Mode.SCROLL -> Action.Scroll(
                horizontal = currentCentroid.x - lastCentroid.x,
                vertical = currentCentroid.y - lastCentroid.y
            )
            Mode.PINCH -> Action.Pinch(
                scale = if (lastSpan > 0f) currentSpan / lastSpan else 1f
            )
            else -> return emptyList()
        }
        lastCentroid = currentCentroid
        lastSpan = currentSpan
        return listOf(action)
    }

    private fun moveThreeFinger(points: List<Point>): List<Action> {
        val current = centroid(points)
        val reference = if (mode == Mode.THREE_PENDING) startCentroid else lastCentroid
        val dx = current.x - reference.x
        val dy = current.y - reference.y
        if (hypot(dx.toDouble(), dy.toDouble()) < THREE_FINGER_SWIPE_THRESHOLD_PX) return emptyList()

        mode = Mode.THREE_TRIGGERED
        lastCentroid = current
        val direction = if (abs(dx) >= abs(dy)) {
            if (dx < 0) Direction.LEFT else Direction.RIGHT
        } else {
            if (dy < 0) Direction.UP else Direction.DOWN
        }
        return listOf(Action.ThreeFingerSwipe(direction))
    }

    private fun centroid(points: List<Point>): Point {
        return Point(points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            points.sumOf { it.y.toDouble() }.toFloat() / points.size)
    }

    private fun span(points: List<Point>): Float = distance(points[0], points[1])

    private fun distance(a: Point, b: Point): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    companion object {
        private const val TWO_FINGER_START_SLOP_PX = 12f
        private const val TWO_FINGER_TAP_TIMEOUT_MS = 500L
        private const val THREE_FINGER_SWIPE_THRESHOLD_PX = 48.0
        private const val THREE_FINGER_TAP_TIMEOUT_MS = 500L
        private const val THREE_FINGER_TAP_SLOP_PX = 24f
    }
}
