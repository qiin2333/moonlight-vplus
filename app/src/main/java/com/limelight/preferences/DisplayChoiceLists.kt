package com.limelight.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min

/**
 * 设置页与游戏内显示页共用的候选列表来源。
 * 两处 UI 各自拼列表会逐渐漂移(重复的 Native 行、孤儿化的旧值都源于此),这里集中实现一次。
 */
internal object DisplayChoiceLists {

    /** 面板真实尺寸(横屏归一化),用于把与原生同尺寸的条目与 Native 伪选项去重。 */
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
     * 纯决策:候选值 = 低分辨率预设(按策略显隐) + 商店列表 + Native。
     * 与面板真实尺寸同值的商店行会被 Native 档取代(同一输出只留一行,且跟随面板的语义更完整)。
     */
    fun resolutionValues(
        storeValues: List<String>,
        native: Pair<Int, Int>?,
        currentResolution: String,
        showLowResolutionPresets: Boolean
    ): List<String> {
        val nativeValue = native?.let { "${it.first}x${it.second}" }
        val values = storeValues.filterNot { it.equals(nativeValue, ignoreCase = true) }.toMutableList()
        val lowPresets = listOf(PreferenceConfiguration.RES_360P, PreferenceConfiguration.RES_480P)
            .filter { preset ->
                preset !in values &&
                    PreferenceConfiguration.includeLowResolutionPreset(
                        preset,
                        currentResolution,
                        showLowResolutionPresets
                    )
            }
        return lowPresets + values + PreferenceConfiguration.RES_NATIVE
    }

    /** 与面板同尺寸的已存值在选择展示上归一为 Native(两者输出一致,Native 语义更完整)。 */
    fun normalizeSelection(selected: String, native: Pair<Int, Int>?): String {
        val nativeValue = native?.let { "${it.first}x${it.second}" } ?: return selected
        return if (selected.equals(nativeValue, ignoreCase = true)) {
            PreferenceConfiguration.RES_NATIVE
        } else {
            selected
        }
    }

    /** 商店列表;清空视为未显式配置,网格回退出厂基线档(编辑器内仍显示真实空列表)。 */
    fun resolutionStoreValues(context: Context): List<String> =
        CustomResolutionsStore.load(context).map { it.toString() }
            .ifEmpty { CustomResolutionsStore.DEFAULT_RESOLUTIONS.map { it.toString() } }

    fun labelLookup(res: android.content.res.Resources, namesRes: Int, valuesRes: Int): Map<String, String> {
        val names = res.getStringArray(namesRes)
        val values = res.getStringArray(valuesRes)
        return values.indices.associate { index ->
            values[index] to names.getOrElse(index) { values[index] }
        }
    }
}
