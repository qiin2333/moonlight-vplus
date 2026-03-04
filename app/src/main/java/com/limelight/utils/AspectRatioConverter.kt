package com.limelight.utils

import kotlin.math.floor

object AspectRatioConverter {
    @JvmStatic
    fun getAspectRatio(width: Int, height: Int): String? {
        val ratio = width.toFloat() / height
        val truncatedValue = (floor((ratio * 100).toDouble()) / 100).toFloat()

        return when {
            truncatedValue == 1.25f -> "5:4"
            truncatedValue == 1.33f -> "4:3"
            truncatedValue == 1.50f -> "3:2"
            truncatedValue == 1.60f -> "16:10"
            truncatedValue == 1.77f -> "16:9"
            truncatedValue == 1.85f -> "1.85:1"
            truncatedValue == 2.22f -> "20:9"
            truncatedValue in 2.37f..2.44f -> "21:9"
            truncatedValue == 2.76f -> "2.76:1"
            truncatedValue == 3.20f -> "32:10"
            truncatedValue == 3.55f -> "32:9"
            else -> null
        }
    }
}
