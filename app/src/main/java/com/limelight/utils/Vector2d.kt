package com.limelight.utils

import kotlin.math.pow
import kotlin.math.sqrt

class Vector2d {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var magnitude: Double = 0.0
        private set

    fun initialize(x: Float, y: Float) {
        this.x = x
        this.y = y
        this.magnitude = sqrt(x.toDouble().pow(2) + y.toDouble().pow(2))
    }

    fun getNormalized(vector: Vector2d) {
        vector.initialize((x / magnitude).toFloat(), (y / magnitude).toFloat())
    }

    fun scalarMultiply(factor: Double) {
        initialize((x * factor).toFloat(), (y * factor).toFloat())
    }

    fun setX(x: Float) {
        initialize(x, this.y)
    }

    fun setY(y: Float) {
        initialize(this.x, y)
    }

    companion object {
        val ZERO = Vector2d()
    }
}
