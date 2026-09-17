package com.limelight.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.edit

/**
 * 首页背景取色（"强调色跟随首页背景"模式）。
 *
 * 从首页背景位图提取主导色相，量化为 12 个 30° 色相桶并持久化；
 * UiHelper.applyAccentOverlay 据此叠加对应的 YouAccentOverlayBg* 主题，
 * 让所有 ?attr/appAccent* 消费端换上背景同族强调色。
 *
 * 刻意不用系统 Monet/WallpaperColors：背景图是我们自己的资源，
 * 自提取在任意 ROM、任意 Android 版本上行为一致。
 */
object BgAccent {

    private const val KEY = "you_bg_accent_bucket"
    const val NO_BUCKET = -1

    /** 背景加载成功后调用：提取主导色相桶并持久化，返回桶位（无主导色相时 [NO_BUCKET]）。 */
    fun updateFromBitmap(context: Context, bitmap: Bitmap): Int {
        val bucket = extractHueBucket(bitmap)
        context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
            .edit { putInt(KEY, bucket) }
        return bucket
    }

    /** 背景被移除（None）或加载失败时调用：回到品牌粉。 */
    fun clear(context: Context) {
        context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
            .edit { putInt(KEY, NO_BUCKET) }
    }

    fun bucket(context: Context): Int =
        context.getSharedPreferences("AppTheme", Context.MODE_PRIVATE)
            .getInt(KEY, NO_BUCKET)

    /**
     * 主导色相提取：降采样遍历像素，HSV 里丢弃近灰/过暗/过亮像素，
     * 按饱和度加权投票到 12 个色相桶，取权重最高者。
     */
    private fun extractHueBucket(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return NO_BUCKET
        val step = maxOf(1, minOf(w, h) / 64)
        val weights = FloatArray(12)
        val hsv = FloatArray(3)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val s = hsv[1]
                val v = hsv[2]
                // 排除近灰与极端明暗，避免中性背景/纯白边缘污染投票
                if (s >= 0.10f && v in 0.06f..0.98f) {
                    weights[((hsv[0] / 30f).toInt()) % 12] += s
                }
                x += step
            }
            y += step
        }
        var best = -1
        var bestWeight = 0f
        for (i in 0 until 12) {
            if (weights[i] > bestWeight) {
                bestWeight = weights[i]
                best = i
            }
        }
        return if (bestWeight > 0f) best else NO_BUCKET
    }
}
