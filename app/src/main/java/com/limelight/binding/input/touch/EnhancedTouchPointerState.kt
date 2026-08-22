package com.limelight.binding.input.touch

import kotlin.math.abs

internal data class EnhancedTouchPointerConfig(
    val screenWidth: Float,
    val initialZonePixels: Float,
    val enhancedTouchDirection: Int,
    val enhancedTouchZoneDivider: Float,
    val pointerVelocityFactor: Float
)

internal class EnhancedTouchPointerState(
    private val initialX: Float,
    private val initialY: Float,
    private val config: EnhancedTouchPointerConfig
) {
    private var latestX = initialX
    private var latestY = initialY
    private var relativeX = initialX
    private var relativeY = initialY
    private var pointerLeftInitialZone = false

    private val usesEnhancedCoordinates =
        initialX / config.screenWidth * config.enhancedTouchDirection >
            config.enhancedTouchZoneDivider * config.enhancedTouchDirection

    fun update(rawX: Float, rawY: Float) {
        val deltaX = rawX - latestX
        val deltaY = rawY - latestY
        latestX = rawX
        latestY = rawY

        if (config.pointerVelocityFactor == 1.0f) {
            relativeX = rawX
            relativeY = rawY
        } else {
            relativeX += deltaX * config.pointerVelocityFactor
            relativeY += deltaY * config.pointerVelocityFactor
        }

        if (config.initialZonePixels > 0f) {
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
            (abs(latestX - initialX) > config.initialZonePixels ||
                abs(latestY - initialY) > config.initialZonePixels)
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
