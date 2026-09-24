package com.limelight.binding.input.touchpad

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/** Recognizes contact streams, including Android's one-contact representation of two-finger scroll. */
internal class CompatibilityTouchpadGesture(
    private val slop: Float,
    private val tapTimeoutMs: Long = 500,
) {
    data class Point(val id: Int, val x: Float, val y: Float)
    enum class Direction { LEFT, RIGHT, UP, DOWN }
    sealed interface Action {
        data class Position(val x: Float, val y: Float, val relativeX: Float = 0f, val relativeY: Float = 0f) : Action
        data class Button(val down: Boolean) : Action
        data class Click(val fingers: Int) : Action
        data class Move(val x: Short, val y: Short) : Action
        data class Scroll(val x: Short, val y: Short) : Action
        data class Zoom(val amount: Short) : Action
        data class Swipe(val direction: Direction) : Action
        data class EndSwitch(val cancelled: Boolean) : Action
    }
    private enum class Mode { IDLE, PENDING, SCROLL_HORIZONTAL, SCROLL_VERTICAL, PINCH, SWITCH, DRAIN }
    private var mode = Mode.IDLE
    private var start = emptyList<Point>()
    private var last = emptyList<Point>()
    private var startTime = 0L
    private var tapEligible = false
    private var remainderX = 0f
    private var remainderY = 0f
    private var zoom = 0.0
    private var singleContactScroll = true

    fun down(points: List<Point>, time: Long, singleContactScroll: Boolean = true, allowTap: Boolean = true) {
        cancel()
        startTime = time
        tapEligible = allowTap
        this.singleContactScroll = singleContactScroll
        rebase(points)
    }

    fun pointerDown(points: List<Point>): List<Action> {
        if (mode == Mode.IDLE || mode == Mode.DRAIN) return emptyList()
        if (mode == Mode.SWITCH) {
            mode = Mode.DRAIN
            return listOf(Action.EndSwitch(cancelled = true))
        }
        tapEligible = tapEligible && mode == Mode.PENDING
        rebase(points)
        return emptyList()
    }

    private fun rebase(points: List<Point>) {
        start = points
        last = points
        remainderX = 0f
        remainderY = 0f
        zoom = 0.0
        mode = if (points.size in 1..3) Mode.PENDING else Mode.DRAIN
    }

    fun move(points: List<Point>): List<Action> {
        if (mode == Mode.IDLE || mode == Mode.DRAIN ||
            points.map { it.id } != start.map { it.id }) return emptyList()
        if (points.indices.any { distance(points[it], start[it]) >= slop }) tapEligible = false
        val center = center(points)
        val origin = center(start)
        val travel = distance(center, origin)
        val movesPointer = points.size == 1 && !singleContactScroll
        if (points.size == 3) {
            if (mode == Mode.SWITCH) {
                val dx = center.x - center(last).x
                // Reversing should take one step, not one step plus the unfinished
                // distance accumulated in the previous direction.
                if (dx * remainderX < 0f) remainderX = 0f
                remainderX += dx
                last = points
                val steps = (remainderX / (slop * 3)).toInt().coerceIn(-16, 16)
                remainderX -= steps * slop * 3
                return List(abs(steps)) { Action.Swipe(if (steps < 0) Direction.LEFT else Direction.RIGHT) }
            }
            if (travel < slop * 3) return emptyList()
            val dx = center.x - origin.x
            val dy = center.y - origin.y
            last = points
            mode = if (abs(dx) >= abs(dy)) Mode.SWITCH else Mode.DRAIN
            return listOf(Action.Swipe(if (abs(dx) >= abs(dy)) {
                if (dx < 0) Direction.LEFT else Direction.RIGHT
            } else if (dy < 0) Direction.UP else Direction.DOWN))
        }
        if (mode == Mode.PENDING) {
            val spanChange = if (points.size == 2) abs(span(points) - span(start)) else 0f
            mode = when {
                spanChange >= slop && spanChange > travel -> Mode.PINCH
                travel >= slop -> if (abs(center.x - origin.x) > abs(center.y - origin.y))
                    Mode.SCROLL_HORIZONTAL else Mode.SCROLL_VERTICAL
                movesPointer -> Mode.PENDING
                else -> return emptyList()
            }
            if (mode != Mode.PENDING) tapEligible = false
        }
        val actions = if (mode == Mode.PINCH) {
            val previousSpan = span(last)
            val currentSpan = span(points)
            if (previousSpan > 0f && currentSpan > 0f) zoom += ln((currentSpan / previousSpan).toDouble())
            val steps = (zoom / ZOOM_STEP).toInt().coerceIn(-16, 16)
            zoom -= steps * ZOOM_STEP
            if (steps == 0) emptyList() else listOf(Action.Zoom((steps * 120).toShort()))
        } else {
            val previous = center(last)
            // Keep a scroll on its starting axis. Small off-axis drift must not
            // interleave wheel axes: some hosts reinterpret those as one axis.
            // Raw one-finger pointer movement still keeps both coordinates.
            if (movesPointer || mode == Mode.SCROLL_HORIZONTAL) remainderX += center.x - previous.x
            if (movesPointer || mode == Mode.SCROLL_VERTICAL) remainderY += center.y - previous.y
            val x = remainderX.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val y = remainderY.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            remainderX -= x
            remainderY -= y
            when {
                x == 0 && y == 0 -> emptyList()
                movesPointer -> listOf(Action.Move(x.toShort(), y.toShort()))
                else -> listOf(Action.Scroll(x.toShort(), y.toShort()))
            }
        }
        last = points
        return actions
    }

    /** The first lift ends recognition; drain the remaining contacts without starting another tap. */
    fun up(points: List<Point>, time: Long): List<Action> {
        val actions = move(points)
        val endSwitch = if (mode == Mode.SWITCH) listOf(Action.EndSwitch(cancelled = false)) else emptyList()
        val click = mode == Mode.PENDING && tapEligible && time - startTime in 0..tapTimeoutMs
        mode = Mode.DRAIN
        return if (click) listOf(Action.Click(start.size)) else actions + endSwitch
    }

    fun cancel(): List<Action> {
        val actions = if (mode == Mode.SWITCH) listOf(Action.EndSwitch(cancelled = true)) else emptyList()
        mode = Mode.IDLE
        start = emptyList()
        last = emptyList()
        tapEligible = false
        remainderX = 0f
        remainderY = 0f
        zoom = 0.0
        return actions
    }

    private fun center(points: List<Point>) =
        Point(0, points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            points.sumOf { it.y.toDouble() }.toFloat() / points.size)
    private fun span(points: List<Point>) = distance(points[0], points[1])
    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private companion object { const val ZOOM_STEP = 0.1 }
}
