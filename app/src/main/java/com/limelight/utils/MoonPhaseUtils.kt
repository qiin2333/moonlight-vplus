package com.limelight.utils

import java.util.Calendar
import java.util.TimeZone

/**
 * 月相工具类
 * 提供月相计算、图标获取、信息查询等功能
 */
object MoonPhaseUtils {

    class MoonPhaseInfo(
        val poeticTitle: String,
        val name: String,
        val description: String,
        val icon: String
    )

    enum class MoonPhaseType(val displayName: String, private val minPhase: Double, private val maxPhase: Double) {
        NEW_MOON("新月", 0.0, 0.0625),
        WAXING_CRESCENT("娥眉月", 0.0625, 0.1875),
        FIRST_QUARTER("上弦月", 0.1875, 0.3125),
        WAXING_GIBBOUS("盈凸月", 0.3125, 0.4375),
        FULL_MOON("满月", 0.4375, 0.5625),
        WANING_GIBBOUS("亏凸月", 0.5625, 0.6875),
        LAST_QUARTER("下弦月", 0.6875, 0.8125),
        WANING_CRESCENT("残月", 0.8125, 0.9375);

        fun isInRange(phase: Double): Boolean = phase in minPhase..<maxPhase
    }

    /**
     * 计算月相（0-1，0为新月，0.5为满月）
     * 使用简化的天文算法
     */
    fun calculateMoonPhase(date: Calendar): Double {
        val baseDate = Calendar.getInstance().apply {
            set(2000, Calendar.JANUARY, 6, 18, 14, 0)
        }
        val timeDiff = date.timeInMillis - baseDate.timeInMillis
        val daysDiff = timeDiff / (24.0 * 60.0 * 60.0 * 1000.0)
        val moonCycle = 29.530588853
        var phase = (daysDiff % moonCycle) / moonCycle
        if (phase < 0) phase += 1.0
        return phase
    }

    fun getCurrentMoonPhase(): Double =
        calculateMoonPhase(Calendar.getInstance(TimeZone.getDefault()))

    fun getMoonPhaseType(phase: Double): MoonPhaseType =
        MoonPhaseType.entries.firstOrNull { it.isInRange(phase) } ?: MoonPhaseType.NEW_MOON

    fun getMoonPhaseIcon(phase: Double): String = when (getMoonPhaseType(phase)) {
        MoonPhaseType.NEW_MOON -> "🌑"
        MoonPhaseType.WAXING_CRESCENT -> "🌒"
        MoonPhaseType.FIRST_QUARTER -> "🌓"
        MoonPhaseType.WAXING_GIBBOUS -> "🌔"
        MoonPhaseType.FULL_MOON -> "🌕"
        MoonPhaseType.WANING_GIBBOUS -> "🌖"
        MoonPhaseType.LAST_QUARTER -> "🌗"
        MoonPhaseType.WANING_CRESCENT -> "🌘"
    }

    fun getMoonPhasePoeticTitle(phase: Double): String = when (getMoonPhaseType(phase)) {
        MoonPhaseType.NEW_MOON -> "🌑 新月如钩 · 万象更新"
        MoonPhaseType.WAXING_CRESCENT -> "🌒 娥眉初现 · 希望萌芽"
        MoonPhaseType.FIRST_QUARTER -> "🌓 上弦月明 · 平衡之道"
        MoonPhaseType.WAXING_GIBBOUS -> "🌔 盈凸月满 · 收获在望"
        MoonPhaseType.FULL_MOON -> "🌕 满月当空 · 圆满时刻"
        MoonPhaseType.WANING_GIBBOUS -> "🌖 亏凸月暗 · 感恩释放"
        MoonPhaseType.LAST_QUARTER -> "🌗 下弦月残 · 反思内省"
        MoonPhaseType.WANING_CRESCENT -> "🌘 残月如钩 · 循环往复"
    }

    fun getMoonPhaseDescription(phase: Double): String = when (getMoonPhaseType(phase)) {
        MoonPhaseType.NEW_MOON -> "月亮与太阳同方向，不可见。\n象征新的开始和重生。"
        MoonPhaseType.WAXING_CRESCENT -> "月亮的右侧开始发光。\n象征成长和希望的萌芽。"
        MoonPhaseType.FIRST_QUARTER -> "月亮的一半被照亮。\n象征平衡和决策的时刻。"
        MoonPhaseType.WAXING_GIBBOUS -> "月亮大部分被照亮。\n象征接近圆满和收获。"
        MoonPhaseType.FULL_MOON -> "月亮完全被照亮。\n象征圆满、成就和庆祝。"
        MoonPhaseType.WANING_GIBBOUS -> "月亮开始变暗。\n象征释放和感恩。"
        MoonPhaseType.LAST_QUARTER -> "月亮的一半变暗。\n象征反思和内省。"
        MoonPhaseType.WANING_CRESCENT -> "月亮几乎不可见。\n象征结束和准备新的循环。"
    }

    fun getMoonPhaseInfo(phase: Double): MoonPhaseInfo = MoonPhaseInfo(
        poeticTitle = getMoonPhasePoeticTitle(phase),
        name = getMoonPhaseType(phase).displayName,
        description = getMoonPhaseDescription(phase),
        icon = getMoonPhaseIcon(phase)
    )

    fun getCurrentMoonPhaseInfo(): MoonPhaseInfo = getMoonPhaseInfo(getCurrentMoonPhase())

    fun getMoonPhasePercentage(phase: Double): Double = phase * 100

    fun getDaysInMoonCycle(phase: Double): Int = (phase * 29.530588853).toInt()

    fun isFullMoon(phase: Double, tolerance: Double): Boolean =
        Math.abs(phase - 0.5) < tolerance

    fun isNewMoon(phase: Double, tolerance: Double): Boolean =
        phase < tolerance || phase > (1.0 - tolerance)
}
