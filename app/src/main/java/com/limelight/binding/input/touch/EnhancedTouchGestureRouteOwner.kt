package com.limelight.binding.input.touch

internal class EnhancedTouchGestureRouteOwner {
    private var pointerConfig: EnhancedTouchPointerConfig? = null

    fun begin(config: EnhancedTouchPointerConfig) {
        pointerConfig = config
    }

    fun ownsContinuation(): Boolean = pointerConfig != null

    fun currentPointerConfig(): EnhancedTouchPointerConfig? = pointerConfig

    fun finish() {
        pointerConfig = null
    }
}
