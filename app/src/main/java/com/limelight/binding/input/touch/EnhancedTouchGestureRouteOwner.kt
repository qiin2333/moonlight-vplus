package com.limelight.binding.input.touch

internal class EnhancedTouchGestureRouteOwner {
    private var ownsGesture = false

    fun begin(routeStarted: Boolean): Boolean {
        ownsGesture = routeStarted
        return ownsGesture
    }

    fun ownsContinuation(): Boolean = ownsGesture

    fun finish() {
        ownsGesture = false
    }
}
