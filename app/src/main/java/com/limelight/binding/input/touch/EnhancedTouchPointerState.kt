package com.limelight.binding.input.touch

import kotlin.math.abs

internal class EnhancedTouchPointerState(
    private val initialX: Float,
    private val initialY: Float,
    screenWidth: Float,
    private val initialZonePixels: Float,
    enhancedTouchDirection: Int,
    enhancedTouchZoneDivider: Float,
    private val pointerVelocityFactor: Float
) {
    private var latestX = initialX
    private var latestY = initialY
    private var relativeX = initialX
    private var relativeY = initialY
    private var pointerLeftInitialZone = false

    private val usesEnhancedCoordinates =
        initialX / screenWidth * enhancedTouchDirection >
            enhancedTouchZoneDivider * enhancedTouchDirection

    fun update(rawX: Float, rawY: Float) {
        val deltaX = rawX - latestX
        val deltaY = rawY - latestY
        latestX = rawX
        latestY = rawY

        if (pointerVelocityFactor == 1.0f) {
            relativeX = rawX
            relativeY = rawY
        } else {
            relativeX += deltaX * pointerVelocityFactor
            relativeY += deltaY * pointerVelocityFactor
        }

        if (initialZonePixels > 0f) {
            flattenInitialJitter()
        }
    }

    fun selectedX(): Float = if (usesEnhancedCoordinates) relativeX else latestX

    fun selectedY(): Float = if (usesEnhancedCoordinates) relativeY else latestY

    fun initialX(): Float = initialX

    fun initialY(): Float = initialY

    fun latestX(): Float = latestX

    fun latestY(): Float = latestY

    fun relativeX(): Float = relativeX

    fun relativeY(): Float = relativeY

    private fun flattenInitialJitter() {
        if (!pointerLeftInitialZone &&
            (abs(latestX - initialX) > initialZonePixels ||
                abs(latestY - initialY) > initialZonePixels)
        ) {
            pointerLeftInitialZone = true
        }

        if (!pointerLeftInitialZone) {
            latestX = initialX
            latestY = initialY
            relativeX = initialX
            relativeY = initialY
        }
    }
}
