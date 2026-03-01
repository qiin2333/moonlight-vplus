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

        if (context == null) {
            return info;
        }

        Display display = getDefaultDisplay(context);
        if (display == null) {
            return info;
        }

        // Android 7.0+ 从 Display.HdrCapabilities 获取（EDID 数据）
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
     * @return [minBrightness, maxBrightness, maxAverageBrightness] 单位 nits，整型
     */
    public static int[] getBrightnessRangeAsInts(Context context) {
        BrightnessInfo info = getBrightnessInfo(context);
        int min = Math.max(1, (int) Math.floor(info.minLuminance));
        int max = Math.max(min + 1, (int) Math.ceil(info.maxLuminance));
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
