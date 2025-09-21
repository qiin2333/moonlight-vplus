package com.limelight.utils;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 月相工具类
 * 提供月相计算、图标获取、信息查询等功能
 */
public class MoonPhaseUtils {

    /**
     * 月相信息类
     */
    public static class MoonPhaseInfo {
        public final String poeticTitle;
        public final String name;
        public final String description;
        public final String icon;
        
        public MoonPhaseInfo(String poeticTitle, String name, String description, String icon) {
            this.poeticTitle = poeticTitle;
            this.name = name;
            this.description = description;
            this.icon = icon;
        }
    }

    /**
     * 月相类型枚举
     */
    public enum MoonPhaseType {
        NEW_MOON("新月", 0.0, 0.0625),
        WAXING_CRESCENT("娥眉月", 0.0625, 0.1875),
        FIRST_QUARTER("上弦月", 0.1875, 0.3125),
        WAXING_GIBBOUS("盈凸月", 0.3125, 0.4375),
        FULL_MOON("满月", 0.4375, 0.5625),
        WANING_GIBBOUS("亏凸月", 0.5625, 0.6875),
        LAST_QUARTER("下弦月", 0.6875, 0.8125),
        WANING_CRESCENT("残月", 0.8125, 0.9375);

        private final String name;
        private final double minPhase;
        private final double maxPhase;

        MoonPhaseType(String name, double minPhase, double maxPhase) {
            this.name = name;
            this.minPhase = minPhase;
            this.maxPhase = maxPhase;
        }

        public String getName() {
            return name;
        }

        public boolean isInRange(double phase) {
            return phase >= minPhase && phase < maxPhase;
        }
    }

    /**
     * 计算月相（0-1，0为新月，0.5为满月）
     * 使用简化的天文算法
     */
    public static double calculateMoonPhase(Calendar date) {
        // 基准日期：2000年1月6日为新月
        Calendar baseDate = Calendar.getInstance();
        baseDate.set(2000, Calendar.JANUARY, 6, 18, 14, 0);
        
        // 计算距离基准日期的天数
        long timeDiff = date.getTimeInMillis() - baseDate.getTimeInMillis();
        double daysDiff = timeDiff / (24.0 * 60.0 * 60.0 * 1000.0);
        
        // 月相周期约为29.53天
        double moonCycle = 29.530588853;
        
        // 计算当前月相位置（0-1）
        double phase = (daysDiff % moonCycle) / moonCycle;
        if (phase < 0) phase += 1.0;
        
        return phase;
    }

    /**
     * 获取当前月相
     */
    public static double getCurrentMoonPhase() {
        return calculateMoonPhase(Calendar.getInstance(TimeZone.getDefault()));
    }

    /**
     * 根据月相值获取月相类型
     */
    public static MoonPhaseType getMoonPhaseType(double phase) {
        for (MoonPhaseType type : MoonPhaseType.values()) {
            if (type.isInRange(phase)) {
                return type;
            }
        }
        return MoonPhaseType.NEW_MOON; // 默认返回新月
    }

    /**
     * 根据月相值获取对应的Unicode图标
     */
    public static String getMoonPhaseIcon(double phase) {
        MoonPhaseType type = getMoonPhaseType(phase);
        switch (type) {
            case NEW_MOON:
                return "🌑";
            case WAXING_CRESCENT:
                return "🌒";
            case FIRST_QUARTER:
                return "🌓";
            case WAXING_GIBBOUS:
                return "🌔";
            case FULL_MOON:
                return "🌕";
            case WANING_GIBBOUS:
                return "🌖";
            case LAST_QUARTER:
                return "🌗";
            case WANING_CRESCENT:
                return "🌘";
            default:
                return "🌙";
        }
    }

    /**
     * 获取诗意化的月相标题
     */
    public static String getMoonPhasePoeticTitle(double phase) {
        MoonPhaseType type = getMoonPhaseType(phase);
        switch (type) {
            case NEW_MOON:
                return "🌑 新月如钩 · 万象更新";
            case WAXING_CRESCENT:
                return "🌒 娥眉初现 · 希望萌芽";
            case FIRST_QUARTER:
                return "🌓 上弦月明 · 平衡之道";
            case WAXING_GIBBOUS:
                return "🌔 盈凸月满 · 收获在望";
            case FULL_MOON:
                return "🌕 满月当空 · 圆满时刻";
            case WANING_GIBBOUS:
                return "🌖 亏凸月暗 · 感恩释放";
            case LAST_QUARTER:
                return "🌗 下弦月残 · 反思内省";
            case WANING_CRESCENT:
                return "🌘 残月如钩 · 循环往复";
            default:
                return "🌑 新月如钩 · 万象更新";
        }
    }

    /**
     * 获取月相描述
     */
    public static String getMoonPhaseDescription(double phase) {
        MoonPhaseType type = getMoonPhaseType(phase);
        switch (type) {
            case NEW_MOON:
                return "月亮与太阳同方向，不可见。\n象征新的开始和重生。";
            case WAXING_CRESCENT:
                return "月亮的右侧开始发光。\n象征成长和希望的萌芽。";
            case FIRST_QUARTER:
                return "月亮的一半被照亮。\n象征平衡和决策的时刻。";
            case WAXING_GIBBOUS:
                return "月亮大部分被照亮。\n象征接近圆满和收获。";
            case FULL_MOON:
                return "月亮完全被照亮。\n象征圆满、成就和庆祝。";
            case WANING_GIBBOUS:
                return "月亮开始变暗。\n象征释放和感恩。";
            case LAST_QUARTER:
                return "月亮的一半变暗。\n象征反思和内省。";
            case WANING_CRESCENT:
                return "月亮几乎不可见。\n象征结束和准备新的循环。";
            default:
                return "月亮与太阳同方向，不可见。\n象征新的开始和重生。";
        }
    }

    /**
     * 获取完整的月相信息
     */
    public static MoonPhaseInfo getMoonPhaseInfo(double phase) {
        return new MoonPhaseInfo(
            getMoonPhasePoeticTitle(phase),
            getMoonPhaseType(phase).getName(),
            getMoonPhaseDescription(phase),
            getMoonPhaseIcon(phase)
        );
    }

    /**
     * 获取当前月相的完整信息
     */
    public static MoonPhaseInfo getCurrentMoonPhaseInfo() {
        return getMoonPhaseInfo(getCurrentMoonPhase());
    }

    /**
     * 计算月相百分比
     */
    public static double getMoonPhasePercentage(double phase) {
        return phase * 100;
    }

    /**
     * 计算月相周期中的天数
     */
    public static int getDaysInMoonCycle(double phase) {
        return (int) (phase * 29.530588853);
    }

    /**
     * 判断是否为满月（允许一定误差）
     */
    public static boolean isFullMoon(double phase, double tolerance) {
        return Math.abs(phase - 0.5) < tolerance;
    }

    /**
     * 判断是否为新月（允许一定误差）
     */
    public static boolean isNewMoon(double phase, double tolerance) {
        return phase < tolerance || phase > (1.0 - tolerance);
    }
}
