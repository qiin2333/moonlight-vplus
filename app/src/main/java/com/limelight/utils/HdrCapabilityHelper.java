package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import com.limelight.LimeLog;

/**
 * 统一的 HDR 与屏幕能力检测工具类
 * 用于向服务端报告亮度信息以及在诊断页面展示
 */
public class HdrCapabilityHelper {

    /**
     * 屏幕亮度信息（单位：nits）
     */
    public static class BrightnessInfo {
        public float maxLuminance;       // 最大亮度
        public float minLuminance;       // 最小亮度
        public float maxAvgLuminance;    // 最大平均亮度
        public boolean isFromHdrCaps;    // 是否从 HdrCapabilities 获取（真实 EDID 数据）
        public boolean isDefault;        // 是否为 fallback 默认值

        // HDR/SDR 比率信息（Android 14+ / API 34+）
        // 等价于鸿蒙 display.getBrightnessInfo(0) 的 currentHeadroom / maxHeadroom
        public float hdrSdrRatio;               // 当前 HDR/SDR 比率 (= targetHdrPeakNits / targetSdrWhiteNits)
        public float highestHdrSdrRatio;        // 最高 HDR/SDR 比率（最大余量）
        public boolean isHdrSdrRatioAvailable;  // 设备是否支持 HDR/SDR ratio 查询

        // 通过 HDR/SDR ratio 计算的峰值亮度
        // 计算方式同鸿蒙：peakBrightness = sdrNits * maxHeadroom
        public float computedPeakBrightness;      // 通过 ratio 计算的峰值亮度（-1 表示不可用）
        public boolean isComputedFromRatio;        // 是否由 HDR/SDR ratio 计算得出

        // 默认值常量
        public static final float DEFAULT_MAX = 500f;
        public static final float DEFAULT_MIN = 2f;
        public static final float DEFAULT_AVG = 200f;
    }

    /**
     * HDR 类型支持信息
     */
    public static class HdrTypeSupport {
        public boolean hasHlg;
        public boolean hasHdr10;
        public boolean hasHdr10Plus;
        public boolean hasDolbyVision;
        public int[] rawTypes;  // 原始 HDR 类型数组
    }

    /**
     * 综合 HDR 能力信息
     */
    public static class HdrCapabilityInfo {
        public BrightnessInfo brightness;
        public HdrTypeSupport typeSupport;
        public boolean isScreenHdr;       // Configuration.isScreenHdr()
        public boolean isWideColorGamut;  // Display.isWideColorGamut()
        public boolean displayReportsHdr; // Display.isHdr() (API 34+)
    }

    /**
     * 获取屏幕亮度范围，统一逻辑
     * 同时被 NvConnection（上报服务端）和诊断页面使用
     */
    @SuppressLint("NewApi")
    public static BrightnessInfo getBrightnessInfo(Context context) {
        BrightnessInfo info = new BrightnessInfo();
        info.maxLuminance = BrightnessInfo.DEFAULT_MAX;
        info.minLuminance = BrightnessInfo.DEFAULT_MIN;
        info.maxAvgLuminance = BrightnessInfo.DEFAULT_AVG;
        info.isFromHdrCaps = false;
        info.isDefault = true;
        info.hdrSdrRatio = 1.0f;
        info.highestHdrSdrRatio = 1.0f;
        info.isHdrSdrRatioAvailable = false;
        info.computedPeakBrightness = -1f;
        info.isComputedFromRatio = false;

        if (context == null) {
            return info;
        }

        Display display = getDefaultDisplay(context);
        if (display == null) {
            return info;
        }

        // 第一步：从 EDID (HdrCapabilities) 获取基础亮度值 (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
            if (hdrCaps != null) {
                float maxLum = hdrCaps.getDesiredMaxLuminance();
                float minLum = hdrCaps.getDesiredMinLuminance();
                float maxAvgLum = hdrCaps.getDesiredMaxAverageLuminance();

                LimeLog.info("HdrCapabilities raw: max=" + maxLum + ", min=" + minLum + ", avg=" + maxAvgLum);

                if (Float.isFinite(maxLum) && maxLum > 0.1f) {
                    info.maxLuminance = maxLum;
                    info.isFromHdrCaps = true;
                    info.isDefault = false;
                }
                if (Float.isFinite(maxAvgLum) && maxAvgLum > 0.1f) {
                    info.maxAvgLuminance = maxAvgLum;
                    info.isDefault = false;
                }
                if (Float.isFinite(minLum) && minLum >= 0f) {
                    info.minLuminance = Math.max(0.001f, minLum);
                    info.isDefault = false;
                }
            }
        }

        // 第二步：使用 HDR/SDR ratio 获取更精确的峰值亮度 (Android 14+ / API 34+)
        // 等价于鸿蒙的 display.getBrightnessInfo(0)
        //   hdrSdrRatio     ≈ currentHeadroom (当前 HDR/SDR 比率)
        //   highestHdrSdrRatio ≈ maxHeadroom (最大 HDR/SDR 比率)
        //   peakBrightness   = sdrNits * maxHeadroom
        if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            try {
                info.isHdrSdrRatioAvailable = display.isHdrSdrRatioAvailable();
                if (info.isHdrSdrRatioAvailable) {
                    info.hdrSdrRatio = display.getHdrSdrRatio();
                    LimeLog.info("HDR/SDR ratio: current=" + info.hdrSdrRatio
                            + ", available=" + info.isHdrSdrRatioAvailable);

                    // getHighestHdrSdrRatio() requires API 36 (Build.VERSION_CODES.BAKLAVA)
                    if (Build.VERSION.SDK_INT >= 36) {
                        try {
                            info.highestHdrSdrRatio = display.getHighestHdrSdrRatio();
                            LimeLog.info("Highest HDR/SDR ratio: " + info.highestHdrSdrRatio);
                        } catch (NoSuchMethodError e) {
                            // API 36 方法不存在，忽略
                            info.highestHdrSdrRatio = info.hdrSdrRatio;
                        }
                    } else {
                        // API 34-35: 没有 getHighestHdrSdrRatio()，用当前值作参考
                        info.highestHdrSdrRatio = info.hdrSdrRatio;
                    }

                    // 利用 EDID maxLuminance 和 ratio 计算更准确的峰值亮度
                    // 原理：maxLuminance(EDID) / highestRatio ≈ SDR 白点亮度
                    //       峰值亮度 = SDR白点 * highestRatio
                    // 但如果 EDID maxLuminance 就是峰值，ratio 可以帮助验证
                    if (info.hdrSdrRatio > 1.0f && info.isFromHdrCaps) {
                        // 有 EDID 数据 + 有 ratio: 取 max(EDID, 计算值) 作为更可靠的估算
                        // 估算 SDR 白点 = EDID峰值 / 最高ratio
                        float estimatedSdrNits = info.maxLuminance / Math.max(info.highestHdrSdrRatio, info.hdrSdrRatio);
                        float computedPeak = estimatedSdrNits * Math.max(info.highestHdrSdrRatio, info.hdrSdrRatio);
                        info.computedPeakBrightness = computedPeak;
                        info.isComputedFromRatio = true;
                        LimeLog.info("Computed peak brightness: " + computedPeak + " nits"
                                + " (estimatedSdr=" + estimatedSdrNits + ")");
                    } else if (info.hdrSdrRatio > 1.0f) {
                        // 无 EDID 但有 ratio: 使用默认 SDR 亮度 (约200-350 nits) * ratio 估算
                        float assumedSdrNits = 300f; // 典型移动设备 SDR 亮度
                        float computedPeak = assumedSdrNits * Math.max(info.highestHdrSdrRatio, info.hdrSdrRatio);
                        info.computedPeakBrightness = computedPeak;
                        info.isComputedFromRatio = true;

                        // 如果计算出的峰值比默认值大，使用计算值
                        if (computedPeak > info.maxLuminance) {
                            info.maxLuminance = computedPeak;
                            info.isDefault = false;
                        }
                        LimeLog.info("Computed peak brightness (no EDID): " + computedPeak + " nits"
                                + " (assumedSdr=" + assumedSdrNits + ", ratio=" + info.hdrSdrRatio + ")");
                    }
                }
            } catch (Exception e) {
                LimeLog.warning("Failed to get HDR/SDR ratio: " + e.getMessage());
            }
        }

        return info;
    }

    /**
     * 获取 HDR 类型支持信息
     */
    @SuppressLint("NewApi")
    public static HdrTypeSupport getHdrTypeSupport(Context context) {
        HdrTypeSupport support = new HdrTypeSupport();
        support.rawTypes = new int[0];

        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return support;
        }

        Display display = getDefaultDisplay(context);
        if (display == null) {
            return support;
        }

        Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
        if (hdrCaps == null) {
            return support;
        }

        int[] types = hdrCaps.getSupportedHdrTypes();
        support.rawTypes = types;

        for (int type : types) {
            switch (type) {
                case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                    support.hasDolbyVision = true;
                    break;
                case Display.HdrCapabilities.HDR_TYPE_HDR10:
                    support.hasHdr10 = true;
                    break;
                case Display.HdrCapabilities.HDR_TYPE_HLG:
                    support.hasHlg = true;
                    break;
                case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:
                    support.hasHdr10Plus = true;
                    break;
            }
        }

        return support;
    }

    /**
     * 获取综合 HDR 能力信息（用于诊断页面）
     */
    @SuppressLint("NewApi")
    public static HdrCapabilityInfo getFullCapabilityInfo(Context context) {
        HdrCapabilityInfo capInfo = new HdrCapabilityInfo();
        capInfo.brightness = getBrightnessInfo(context);
        capInfo.typeSupport = getHdrTypeSupport(context);

        if (context != null) {
            // Configuration.isScreenHdr() (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Configuration config = context.getResources().getConfiguration();
                    capInfo.isScreenHdr = config.isScreenHdr();
                } catch (Exception e) {
                    // ignore
                }
            }

            // Display.isWideColorGamut() (API 26+)
            Display display = getDefaultDisplay(context);
            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    capInfo.isWideColorGamut = display.isWideColorGamut();
                } catch (Exception e) {
                    // ignore
                }
            }

            // Display.isHdr() (API 34+)
            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    capInfo.displayReportsHdr = display.isHdr();
                } catch (NoSuchMethodError e) {
                    // ignore
                }
            }
        }

        return capInfo;
    }

    /**
     * 获取 int[] 格式的亮度范围，兼容 NvConnection 使用
     * 优先使用 HDR/SDR ratio 计算的峰值亮度（更准确）
     * @return [minBrightness, maxBrightness, maxAverageBrightness] 单位 nits，整型
     */
    public static int[] getBrightnessRangeAsInts(Context context) {
        BrightnessInfo info = getBrightnessInfo(context);
        int min = Math.max(1, (int) Math.floor(info.minLuminance));

        // 优先使用 HDR/SDR ratio 计算的峰值亮度（类似鸿蒙 sdrNits * maxHeadroom）
        float effectiveMax = info.maxLuminance;
        if (info.isComputedFromRatio && info.computedPeakBrightness > effectiveMax) {
            effectiveMax = info.computedPeakBrightness;
        }

        int max = Math.max(min + 1, (int) Math.ceil(effectiveMax));
        int avg = Math.max(min, (int) Math.ceil(info.maxAvgLuminance));
        return new int[]{min, max, avg};
    }

    /**
     * 获取系统亮度信息（0-255 范围的系统亮度等级）
     */
    public static int getSystemBrightness(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取是否启用了自动亮度
     */
    public static boolean isAutoBrightnessEnabled(Context context) {
        try {
            int mode = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取默认 Display 对象
     */
    @SuppressLint("NewApi")
    private static Display getDefaultDisplay(Context context) {
        try {
            // 优先使用 DisplayManager（支持更多场景）
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                return dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            // Fallback 到 WindowManager
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                return wm.getDefaultDisplay();
            }
        } catch (Exception e) {
            LimeLog.warning("Failed to get default display: " + e.getMessage());
        }
        return null;
    }
}
