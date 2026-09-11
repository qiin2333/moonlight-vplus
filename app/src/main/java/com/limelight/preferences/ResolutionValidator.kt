package com.limelight.preferences

/** 一条分辨率值,存储格式为 "WxH"。 */
data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x${height}"
}

/**
 * 分辨率验证工具类
 */
object ResolutionValidator {
    private const val MIN_WIDTH = 320
    private const val MAX_WIDTH = 7680
    private const val MIN_HEIGHT = 240
    private const val MAX_HEIGHT = 4320

    fun isValidWidth(width: Int): Boolean = width in MIN_WIDTH..MAX_WIDTH

    fun isValidHeight(height: Int): Boolean = height in MIN_HEIGHT..MAX_HEIGHT

    fun isEven(value: Int): Boolean = value % 2 == 0

    fun parseResolution(resolution: String): Resolution? {
        return try {
            val parts = resolution.split("x")
            if (parts.size != 2) return null
            Resolution(parts[0].toInt(), parts[1].toInt())
        } catch (e: NumberFormatException) {
            null
        }
    }
}
