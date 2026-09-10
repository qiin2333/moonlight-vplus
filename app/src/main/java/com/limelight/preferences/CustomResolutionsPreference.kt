package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference

/**
 * 自定义分辨率常量类
 */
object CustomResolutionsConsts {
    const val CUSTOM_RESOLUTIONS_FILE = "custom_resolutions"
    const val CUSTOM_RESOLUTIONS_KEY = "custom_resolutions"
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

    class Resolution(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x${height}"
    }
}

/**
 * 自定义分辨率偏好设置类
 *
 * 仅作为设置页入口；点击后由 StreamSettings 弹出 Compose 版
 * [CustomResolutionsDialog]，分辨率数据的读写由对话框直接完成。
 */
class CustomResolutionsPreference(
        context: Context,
        attrs: AttributeSet
) : DialogPreference(context, attrs) {

    override fun onSetInitialValue(defaultValue: Any?) {
        // No persisted value needed; resolutions are stored in a separate SharedPreferences file
    }
}
