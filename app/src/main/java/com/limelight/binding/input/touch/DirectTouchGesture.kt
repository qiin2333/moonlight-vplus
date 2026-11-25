package com.limelight.binding.input.touch

import kotlin.math.abs
import kotlin.math.hypot

/** Owns one physical finger sequence until every contact has gone up. Coordinates are in the video parent's space. */
internal class DirectTouchGesture(private val slop: Float, private val output: Output) {
    interface Output {
        fun tap(x: Float, y: Float)
        fun move(x: Float, y: Float)
        fun press(x: Float, y: Float, secondary: Boolean)
        fun release()
        fun scroll(dy: Float)
        fun transform(fromX: Float, fromY: Float, toX: Float, toY: Float, ratio: Float)
        fun keyboard()
    }

    private enum class Route { IDLE, PENDING, DRAG, LONG_PRESS, CLASSIFY, SCROLL, VIEWPORT, THREE, SUPPRESSED }

    private var route = Route.IDLE
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var initialSpan = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var prevSpan = 0f
    private var startedZoomed = false
    private var threeFingerSince = 0L
    val ownsSequence get() = route != Route.IDLE

    fun down(x: Float, y: Float) {
        cancel()
        x0 = x
        y0 = y
        route = Route.PENDING
    }

    fun moveSingle(x: Float, y: Float) {
        when (route) {
            Route.PENDING -> if (hypot(x - x0, y - y0) >= slop) {
                route = Route.DRAG
                output.press(x0, y0, secondary = false)
                output.move(x, y)
            }
            Route.DRAG, Route.LONG_PRESS -> output.move(x, y)
            else -> Unit
        }
    }

    fun longPress() {
        if (route != Route.PENDING) return
        route = Route.LONG_PRESS
        output.press(x0, y0, secondary = true)
    }

    fun secondFinger(ax: Float, ay: Float, bx: Float, by: Float, zoomed: Boolean) {
        if (route != Route.PENDING && route != Route.DRAG && route != Route.LONG_PRESS) return
        releaseRemotePress()
        x0 = ax
        y0 = ay
        x1 = bx
        y1 = by
        prevX = (ax + bx) / 2f
        prevY = (ay + by) / 2f
        initialSpan = hypot(bx - ax, by - ay)
        prevSpan = initialSpan
        startedZoomed = zoomed
        route = Route.CLASSIFY
    }

    fun movePair(ax: Float, ay: Float, bx: Float, by: Float) {
        if (route != Route.CLASSIFY && route != Route.SCROLL && route != Route.VIEWPORT) return
        val x = (ax + bx) / 2f
        val y = (ay + by) / 2f
        val span = hypot(bx - ax, by - ay)
        if (route == Route.CLASSIFY) {
            val change = abs(span - initialSpan)
            val mx = x - prevX
            val my = y - prevY
            val centroid = hypot(mx, my)
            val dot = (ax - x0) * (bx - x1) + (ay - y0) * (by - y1)
            route = when {
                change >= slop && (dot <= 0f || change >= centroid) -> Route.VIEWPORT
                centroid >= slop && startedZoomed -> Route.VIEWPORT
                abs(my) >= slop && abs(my) >= abs(mx) && dot > 0f -> Route.SCROLL
                else -> return
            }
        }
        if (route == Route.VIEWPORT) {
            output.transform(prevX, prevY, x, y, if (prevSpan >= 1f && span >= 1f) span / prevSpan else 1f)
        } else {
            output.scroll(y - prevY)
        }
        prevX = x
        prevY = y
        prevSpan = span
    }

    fun extraFinger(count: Int, time: Long) {
        if (count == 3 && route == Route.CLASSIFY) {
            route = Route.THREE
            threeFingerSince = time
        } else {
            // Never swap the tracked pair or restart host input midway through a sequence.
            releaseRemotePress()
            route = Route.SUPPRESSED
        }
    }

    fun pointerUp(canceled: Boolean) {
        releaseRemotePress()
        if (route != Route.THREE || canceled) route = Route.SUPPRESSED
    }

    fun movedThreeFingers() {
        if (route == Route.THREE) route = Route.SUPPRESSED
    }

    fun up(time: Long, canceled: Boolean) {
        if (!canceled) {
            if (route == Route.PENDING) output.tap(x0, y0)
            if (route == Route.THREE && time - threeFingerSince < 300L) output.keyboard()
        }
        cancel()
    }

    fun cancel(suppressRemaining: Boolean = false) {
        releaseRemotePress()
        route = if (suppressRemaining && ownsSequence) Route.SUPPRESSED else Route.IDLE
    }

    private fun releaseRemotePress() {
        if (route == Route.DRAG || route == Route.LONG_PRESS) output.release()
    }
}
