package com.limelight.binding.input.touch

import android.content.res.Resources
import android.util.Log
import android.view.MotionEvent

internal object ScreenUtils {
    @JvmStatic
    fun getScreenWidth(): Float {
        val displayMetrics = Resources.getSystem().displayMetrics
        return displayMetrics.widthPixels.toFloat()
    }

    @JvmStatic
    fun getScreenHeight(): Float {
        val displayMetrics = Resources.getSystem().displayMetrics
        return displayMetrics.heightPixels.toFloat()
    }
}

class NativeTouchContext {

    class Pointer internal constructor(
        event: MotionEvent,
        config: EnhancedTouchPointerConfig
    ) {
        constructor(event: MotionEvent) : this(event, capturePointerConfig())

        val pointerId: Int
        private val eventCoords = MotionEvent.PointerCoords()
        private val state: EnhancedTouchPointerState

        init {
            val pointerIndex = event.actionIndex
            pointerId = event.getPointerId(pointerIndex)
            event.getPointerCoords(pointerIndex, eventCoords)
            state = EnhancedTouchPointerState(
                initialX = eventCoords.x,
                initialY = eventCoords.y,
                config = config
            )
        }

        fun updatePointerCoords(event: MotionEvent, pointerIndex: Int) {
            event.getPointerCoords(pointerIndex, eventCoords)
            state.update(eventCoords.x, eventCoords.y)
        }

        fun getSelectedX(): Float = state.selectedX()

        fun getSelectedY(): Float = state.selectedY()

        fun getInitialX(): Float = state.initialX()

        fun getPointerNormalizedInitialX(): Float = state.initialX() / ScreenUtils.getScreenWidth()

        fun getInitialY(): Float = state.initialY()

        fun getPointerNormalizedInitialY(): Float = state.initialY() / ScreenUtils.getScreenHeight()

        fun getLatestX(): Float = state.latestX()

        fun getLatestY(): Float = state.latestY()

        fun getLatestRelativeX(): Float = state.relativeX()

        fun getLatestRelativeY(): Float = state.relativeY()

        fun getPointerNormalizedLatestX(): Float = state.latestX() / ScreenUtils.getScreenWidth()

        fun getPointerNormalizedLatestY(): Float = state.latestY() / ScreenUtils.getScreenHeight()

        fun printPointerInitialCoords() {
            Log.d("Initial Coords", "Pointer $pointerId Coords: X ${getInitialX()} Y ${getInitialY()}")
        }

        fun printPointerLatestCoords() {
            Log.d("Latest Coords", "Pointer $pointerId Coords: X ${getLatestX()} Y ${getLatestY()}")
        }

        fun printPointerCoordSnapshot() {
            Log.d("Pointer $pointerId", " InitialCoords:[${getInitialX()}, ${getInitialY()}] LatestCoords:[${getLatestX()}, ${getLatestY()}]")
        }
    }

    companion object {
        internal fun capturePointerConfig() = EnhancedTouchPointerConfig(
            screenWidth = ScreenUtils.getScreenWidth(),
            initialZonePixels = INTIAL_ZONE_PIXELS,
            enhancedTouchDirection = ENHANCED_TOUCH_ON_RIGHT,
            enhancedTouchZoneDivider = ENHANCED_TOUCH_ZONE_DIVIDER,
            pointerVelocityFactor = POINTER_VELOCITY_FACTOR
        )

        @JvmField
        var INTIAL_ZONE_PIXELS = 0f

        @JvmField
        var ENABLE_ENHANCED_TOUCH = true

        @JvmField
        var ENHANCED_TOUCH_ON_RIGHT = 1

        @JvmField
        var ENHANCED_TOUCH_ZONE_DIVIDER = 0.5f

        @JvmField
        var POINTER_VELOCITY_FACTOR = 1.0f
    }
}
