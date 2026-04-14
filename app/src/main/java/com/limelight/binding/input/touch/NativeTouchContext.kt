package com.limelight.binding.input.touch

import android.view.MotionEvent
import android.util.Log
import android.content.res.Resources
import android.util.DisplayMetrics
import kotlin.math.abs

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

    class Pointer(event: MotionEvent) {
        val pointerId: Int
        private val initialCoords = MotionEvent.PointerCoords()
        private val latestCoords = MotionEvent.PointerCoords()
        private val previousCoords = MotionEvent.PointerCoords()
        private val latestRelativeCoords = MotionEvent.PointerCoords()
        private val previousRelativeCoords = MotionEvent.PointerCoords()

        private var velocityX: Float = 0f
        private var velocityY: Float = 0f
        private var pointerLeftInitialZone = false

        init {
            val pointerIndex = event.actionIndex
            pointerId = event.getPointerId(pointerIndex)
            event.getPointerCoords(pointerIndex, initialCoords)
            event.getPointerCoords(pointerIndex, latestCoords)
            latestRelativeCoords.x = latestCoords.x
            latestRelativeCoords.y = latestCoords.y
        }

        fun updatePointerCoords(event: MotionEvent, pointerIndex: Int) {
            previousCoords.x = latestCoords.x
            previousCoords.y = latestCoords.y
            event.getPointerCoords(pointerIndex, latestCoords)

            if (POINTER_VELOCITY_FACTOR == 1.0f) {
                latestRelativeCoords.x = latestCoords.x
                latestRelativeCoords.y = latestCoords.y
            } else {
                updateRelativeCoords()
            }

            if (INTIAL_ZONE_PIXELS > 0f) flattenLongPressJitter()
        }

        private fun updateRelativeCoords() {
            velocityX = latestCoords.x - previousCoords.x
            velocityY = latestCoords.y - previousCoords.y
            previousRelativeCoords.x = latestRelativeCoords.x
            previousRelativeCoords.y = latestRelativeCoords.y
            latestRelativeCoords.x = previousRelativeCoords.x + velocityX * POINTER_VELOCITY_FACTOR
            latestRelativeCoords.y = previousRelativeCoords.y + velocityY * POINTER_VELOCITY_FACTOR
        }

        private fun checkIfPointerLeaveInitialZone() {
            if (!pointerLeftInitialZone) {
                if (abs(latestCoords.x - initialCoords.x) > INTIAL_ZONE_PIXELS ||
                    abs(latestCoords.y - initialCoords.y) > INTIAL_ZONE_PIXELS
                ) {
                    pointerLeftInitialZone = true
                }
            }
        }

        private fun flattenLongPressJitter() {
            checkIfPointerLeaveInitialZone()
            if (!pointerLeftInitialZone) {
                latestCoords.x = initialCoords.x
                latestCoords.y = initialCoords.y
                latestRelativeCoords.x = initialCoords.x
                latestRelativeCoords.y = initialCoords.y
            }
        }

        private fun withinEnhancedTouchZone(): Boolean {
            val normalizedX = initialCoords.x / ScreenUtils.getScreenWidth()
            return normalizedX * ENHANCED_TOUCH_ON_RIGHT > ENHANCED_TOUCH_ZONE_DIVIDER * ENHANCED_TOUCH_ON_RIGHT
        }

        fun xyCoordSelector(): FloatArray {
            return if (withinEnhancedTouchZone()) {
                floatArrayOf(latestRelativeCoords.x, latestRelativeCoords.y)
            } else {
                floatArrayOf(latestCoords.x, latestCoords.y)
            }
        }

        fun getInitialX(): Float = initialCoords.x

        fun getPointerNormalizedInitialX(): Float = initialCoords.x / ScreenUtils.getScreenWidth()

        fun getInitialY(): Float = initialCoords.y

        fun getPointerNormalizedInitialY(): Float = initialCoords.y / ScreenUtils.getScreenHeight()

        fun getLatestX(): Float = latestCoords.x

        fun getLatestY(): Float = latestCoords.y

        fun getLatestRelativeX(): Float = latestRelativeCoords.x

        fun getLatestRelativeY(): Float = latestRelativeCoords.y

        fun getPointerNormalizedLatestX(): Float = latestCoords.x / ScreenUtils.getScreenWidth()

        fun getPointerNormalizedLatestY(): Float = latestCoords.y / ScreenUtils.getScreenHeight()

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
