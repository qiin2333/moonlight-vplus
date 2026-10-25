package com.limelight.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 设置页与游戏内显示卡片共用的候选列表来源。
 * 两处 UI 各自拼列表会逐渐漂移(重复的 Native 行、孤儿化的旧值都源于此),这里集中实现一次。
 */
internal object DisplayChoiceLists {

    /** 面板真实尺寸(横屏归一化)，仅用于 Native 的摘要和推荐码率。 */
    fun nativeSize(context: Context, display: Display?): Pair<Int, Int>? {
        val resolved = display
            ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        resolved.getRealMetrics(metrics)
        val width = metrics.widthPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = metrics.heightPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return null
        return max(width, height) to min(width, height)
    }

    /**
     * 固定尺寸与跟随显示器始终是不同选项。旧值和草稿值只补入候选，不写回商店。
     */
    fun resolutionValues(
        storeValues: List<String>,
        currentResolution: String,
        retainedValues: List<String> = emptyList()
    ): List<String> {
        val values = (storeValues + currentResolution + retainedValues)
            .filter(::isFixedResolution)
            .distinct()
            .sortedWith(compareBy(resolutionOrder) { ResolutionValidator.parseResolution(it)!! })
        return values + PreferenceConfiguration.RES_NATIVE
    }

    private fun isFixedResolution(value: String): Boolean {
        val resolution = ResolutionValidator.parseResolution(value) ?: return false
        return resolution.width > 0 && resolution.height > 0
    }

    /** 使用整数 FPS 表示设备刷新率，59.94Hz 与 60 FPS 合并。 */
    fun frameRateValues(
        baseValues: List<String>,
        maxRefreshRate: Float,
        retainedValues: List<String> = emptyList()
    ): List<String> {
        val displayRate = maxRefreshRate.takeIf { it.isFinite() && it > 0f }
            ?.roundToInt()?.toString()
        return (baseValues + retainedValues + listOfNotNull(displayRate))
            .mapNotNull { it.toIntOrNull()?.takeIf { fps -> fps > 0 } }
            .distinct()
            .sorted()
            .map(Int::toString)
    }

    /** 自动推荐码率以最终输出面积计算；不会改动运行中的 ABR 策略。 */
    fun recommendedBitrate(
        resolution: String,
        frameRate: String,
        nativeSize: Pair<Int, Int>?
    ): Int {
        val resolved = if (resolution == PreferenceConfiguration.RES_NATIVE) {
            nativeSize?.takeIf { it.first > 0 && it.second > 0 }
                ?.let { "${it.first}x${it.second}" }
                ?: PreferenceConfiguration.DEFAULT_RESOLUTION
        } else {
            resolution
        }
        return PreferenceConfiguration.getDefaultBitrate(resolved, frameRate)
    }

    /** 已保存的列表；空列表也是用户的显式选择。 */
    fun resolutionStoreValues(context: Context): List<String> =
        CustomResolutionsStore.load(context).map { it.toString() }

    fun labelLookup(res: android.content.res.Resources, namesRes: Int, valuesRes: Int): Map<String, String> {
        val names = res.getStringArray(namesRes)
        val values = res.getStringArray(valuesRes)
        return values.indices.associate { index ->
            values[index] to names.getOrElse(index) { values[index] }
        }
    }
}
