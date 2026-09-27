package com.limelight.utils

import android.content.Context
import com.limelight.R
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

    enum class MoonPhaseType(val nameRes: Int, private val minPhase: Double, private val maxPhase: Double) {
        NEW_MOON(R.string.moon_new_moon, 0.0, 0.0625),
        WAXING_CRESCENT(R.string.moon_waxing_crescent, 0.0625, 0.1875),
        FIRST_QUARTER(R.string.moon_first_quarter, 0.1875, 0.3125),
        WAXING_GIBBOUS(R.string.moon_waxing_gibbous, 0.3125, 0.4375),
        FULL_MOON(R.string.moon_full_moon, 0.4375, 0.5625),
        WANING_GIBBOUS(R.string.moon_waning_gibbous, 0.5625, 0.6875),
        LAST_QUARTER(R.string.moon_last_quarter, 0.6875, 0.8125),
        WANING_CRESCENT(R.string.moon_waning_crescent, 0.8125, 0.9375);

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

    fun getMoonPhasePoeticTitle(context: Context, phase: Double): String = context.getString(when (getMoonPhaseType(phase)) {
        MoonPhaseType.NEW_MOON -> R.string.moon_new_moon_title
        MoonPhaseType.WAXING_CRESCENT -> R.string.moon_waxing_crescent_title
        MoonPhaseType.FIRST_QUARTER -> R.string.moon_first_quarter_title
        MoonPhaseType.WAXING_GIBBOUS -> R.string.moon_waxing_gibbous_title
        MoonPhaseType.FULL_MOON -> R.string.moon_full_moon_title
        MoonPhaseType.WANING_GIBBOUS -> R.string.moon_waning_gibbous_title
        MoonPhaseType.LAST_QUARTER -> R.string.moon_last_quarter_title
        MoonPhaseType.WANING_CRESCENT -> R.string.moon_waning_crescent_title
    })

    fun getMoonPhaseDescription(context: Context, phase: Double): String = context.getString(when (getMoonPhaseType(phase)) {
        MoonPhaseType.NEW_MOON -> R.string.moon_new_moon_description
        MoonPhaseType.WAXING_CRESCENT -> R.string.moon_waxing_crescent_description
        MoonPhaseType.FIRST_QUARTER -> R.string.moon_first_quarter_description
        MoonPhaseType.WAXING_GIBBOUS -> R.string.moon_waxing_gibbous_description
        MoonPhaseType.FULL_MOON -> R.string.moon_full_moon_description
        MoonPhaseType.WANING_GIBBOUS -> R.string.moon_waning_gibbous_description
        MoonPhaseType.LAST_QUARTER -> R.string.moon_last_quarter_description
        MoonPhaseType.WANING_CRESCENT -> R.string.moon_waning_crescent_description
    })

    fun getMoonPhaseInfo(context: Context, phase: Double): MoonPhaseInfo = MoonPhaseInfo(
        poeticTitle = getMoonPhasePoeticTitle(context, phase),
        name = context.getString(getMoonPhaseType(phase).nameRes),
        description = getMoonPhaseDescription(context, phase),
        icon = getMoonPhaseIcon(phase)
    )

    fun getCurrentMoonPhaseInfo(context: Context): MoonPhaseInfo = getMoonPhaseInfo(context, getCurrentMoonPhase())

    fun getMoonPhasePercentage(phase: Double): Double = phase * 100

    fun getDaysInMoonCycle(phase: Double): Int = (phase * 29.530588853).toInt()

    fun isFullMoon(phase: Double, tolerance: Double): Boolean =
        Math.abs(phase - 0.5) < tolerance

    fun isNewMoon(phase: Double, tolerance: Double): Boolean =
        phase < tolerance || phase > (1.0 - tolerance)
}
