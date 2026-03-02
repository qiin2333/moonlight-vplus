package com.limelight.preferences;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.HdrCapabilityHelper;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码与屏幕能力检测页面
 * 显示设备的视频解码器能力、HDR 支持信息、屏幕参数等
 */
public class CapabilityDiagnosticActivity extends Activity {

    private TextView reportTextView;
    private StringBuilder report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capability_diagnostic);

        UiHelper.setLocale(this);
        UiHelper.notifyNewRootView(this);

        reportTextView = findViewById(R.id.diagnostic_report);
        View copyButton = findViewById(R.id.btn_copy_report);
        View backButton = findViewById(R.id.btn_back);

        report = new StringBuilder();
        generateReport();
        reportTextView.setText(report.toString());

        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Capability Report", report.toString()));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(v -> finish());
    }

    private void generateReport() {
        report.append("═══════════════════════════════════\n");
        report.append("  设备能力检测报告\n");
        report.append("═══════════════════════════════════\n\n");

        appendDeviceInfo();
        appendDisplayInfo();
        appendHdrCapabilities();
        appendVideoDecoderInfo();

        report.append("\n═══════════════════════════════════\n");
        report.append("  报告生成完毕\n");
        report.append("═══════════════════════════════════\n");
    }

    private void appendDeviceInfo() {
        report.append("【设备信息】\n");
        report.append("  品牌: ").append(Build.BRAND).append("\n");
        report.append("  型号: ").append(Build.MODEL).append("\n");
        report.append("  设备: ").append(Build.DEVICE).append("\n");
        report.append("  芯片: ").append(Build.HARDWARE).append("\n");
        report.append("  Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            report.append("  SOC 厂商: ").append(Build.SOC_MANUFACTURER).append("\n");
            report.append("  SOC 型号: ").append(Build.SOC_MODEL).append("\n");
        }
        report.append("\n");
    }

    @SuppressLint("NewApi")
    private void appendDisplayInfo() {
        report.append("【屏幕信息】\n");
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) {
                report.append("  无法获取 Display 信息\n\n");
                return;
            }

            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            report.append("  分辨率: ").append(metrics.widthPixels).append(" × ").append(metrics.heightPixels).append("\n");
            report.append("  密度: ").append(metrics.densityDpi).append(" dpi\n");

            float refreshRate = display.getRefreshRate();
            report.append("  刷新率: ").append(String.format("%.1f Hz", refreshRate)).append("\n");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode[] modes = display.getSupportedModes();
                if (modes.length > 1) {
                    report.append("  支持的显示模式:\n");
                    for (Display.Mode mode : modes) {
                        report.append("    ").append(mode.getPhysicalWidth()).append("×")
                                .append(mode.getPhysicalHeight()).append(" @ ")
                                .append(String.format("%.1f Hz", mode.getRefreshRate())).append("\n");
                    }
                }
            }

        } catch (Exception e) {
            report.append("  获取屏幕信息失败: ").append(e.getMessage()).append("\n");
        }
        report.append("\n");
    }

    @SuppressLint("NewApi")
    private void appendHdrCapabilities() {
        report.append("【HDR 能力】\n");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            report.append("  需要 Android 7.0+ (API 24) 支持\n\n");
            return;
        }

        // 使用统一的 HdrCapabilityHelper
        HdrCapabilityHelper.HdrCapabilityInfo capInfo = HdrCapabilityHelper.getFullCapabilityInfo(this);
        HdrCapabilityHelper.BrightnessInfo brightness = capInfo.brightness;
        HdrCapabilityHelper.HdrTypeSupport typeSupport = capInfo.typeSupport;

        // Configuration.isScreenHdr()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            report.append("  Configuration.isScreenHdr(): ").append(capInfo.isScreenHdr ? "✅ true" : "❌ false").append("\n");
        }

        // Window color mode (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int colorMode = getWindow().getColorMode();
            String modeName;
            switch (colorMode) {
                case 1: modeName = "WIDE_COLOR_GAMUT"; break;
                case 2: modeName = "HDR"; break;
                default: modeName = "DEFAULT"; break;
            }
            report.append("  Window.colorMode: ").append(modeName).append(" (").append(colorMode).append(")\n");
        }

        // 广色域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            report.append("  广色域: ").append(capInfo.isWideColorGamut ? "✅ 支持" : "❌ 不支持").append("\n");
        }

        // HDR 类型
        if (typeSupport.rawTypes.length == 0) {
            report.append("\n  ❌ 设备不支持任何 HDR 类型\n");
        } else {
            report.append("\n  支持的 HDR 类型:\n");
            if (typeSupport.hasDolbyVision) report.append("    ✅ Dolby Vision\n");
            if (typeSupport.hasHdr10) report.append("    ✅ HDR10 / PQ\n");
            if (typeSupport.hasHlg) report.append("    ✅ HLG\n");
            if (typeSupport.hasHdr10Plus) report.append("    ✅ HDR10+\n");

            report.append("\n  串流兼容性:\n");
            report.append("    HLG 直通: ").append(typeSupport.hasHlg ? "✅ 设备支持" : "⚠️ 设备未声明支持，可能需要 tone-mapping").append("\n");
            report.append("    HDR10/PQ 直通: ").append(typeSupport.hasHdr10 ? "✅ 设备支持" : "⚠️ 设备未声明支持").append("\n");
            report.append("    HDR10+ 动态元数据: ").append(typeSupport.hasHdr10Plus ? "✅ 设备支持" : "⬜ 不支持").append("\n");
        }

        // 亮度范围（统一方法获取）
        report.append("\n  亮度范围 (HdrCapabilityHelper):\n");
        report.append("    最大亮度: ").append(String.format("%.1f nits", brightness.maxLuminance));
        if (brightness.isDefault) {
            report.append(" ⚠️ 使用默认值");
        } else if (brightness.isFromHdrCaps) {
            report.append(" (来自 EDID)");
        }
        report.append("\n");
        report.append("    最小亮度: ").append(String.format("%.4f nits", brightness.minLuminance)).append("\n");
        report.append("    最大平均亮度: ").append(String.format("%.1f nits", brightness.maxAvgLuminance)).append("\n");

        // HDR/SDR Ratio 信息（Android 14+ / API 34+）— 等价于鸿蒙 getBrightnessInfo()
        report.append("\n  HDR/SDR 动态比率 (API 34+):\n");
        if (Build.VERSION.SDK_INT < 34) {
            report.append("    ⬜ 需要 Android 14+ (API 34)\n");
        } else if (!brightness.isHdrSdrRatioAvailable) {
            report.append("    ❌ 设备不支持 HDR/SDR ratio 查询\n");
        } else {
            report.append("    当前 HDR/SDR 比率: ").append(String.format("%.2f", brightness.hdrSdrRatio));
            report.append(" (≈鸿蒙 currentHeadroom)\n");
            report.append("    最高 HDR/SDR 比率: ").append(String.format("%.2f", brightness.highestHdrSdrRatio));
            if (Build.VERSION.SDK_INT >= 36) {
                report.append(" (≈鸿蒙 maxHeadroom)\n");
            } else {
                report.append(" (≈当前值, API 36+ 可获取真实 maxHeadroom)\n");
            }

            if (brightness.isComputedFromRatio && brightness.computedPeakBrightness > 0) {
                report.append("    🔬 Ratio 计算峰值: ").append(String.format("%.0f nits", brightness.computedPeakBrightness));
                if (brightness.isFromHdrCaps) {
                    report.append(" (EDID+Ratio 交叉验证)\n");
                } else {
                    report.append(" (假设SDR=300nits × ratio)\n");
                }
            }

            report.append("    ℹ️ Android 不公开 sdrNits，无法像鸿蒙一样精确计算\n");
        }

        // 亮度评估
        if (brightness.isDefault) {
            report.append("    ⚠️ 设备驱动未报告 EDID 亮度，使用默认值 (max=500, min=2, avg=200)\n");
            report.append("    💡 这不代表设备不支持 HDR，仅说明亮度信息不可用\n");
        } else if (brightness.maxLuminance < 400) {
            report.append("    ⚠️ 最大亮度较低 (<400 nits)，HDR 效果可能有限\n");
        } else if (brightness.maxLuminance >= 1000) {
            report.append("    ✅ 高亮度面板 (≥1000 nits)，HDR 效果优秀\n");
        } else if (brightness.maxLuminance >= 600) {
            report.append("    ✅ 中等 HDR 面板 (600-1000 nits)\n");
        }

        // 上报给服务端的亮度值
        int[] serverValues = HdrCapabilityHelper.getBrightnessRangeAsInts(this);
        report.append("\n  上报服务端的亮度值:\n");
        report.append("    minBrightness: ").append(serverValues[0]).append(" nits\n");
        report.append("    maxBrightness: ").append(serverValues[1]).append(" nits\n");
        report.append("    maxAvgBrightness: ").append(serverValues[2]).append(" nits\n");

        // 系统亮度参考
        appendSystemBrightnessInfo();

        // Display.isHdr() (API 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            report.append("\n  Display.isHdr(): ").append(capInfo.displayReportsHdr ? "✅ 当前 HDR 模式" : "⬜ 非 HDR 模式").append("\n");
        }

        report.append("\n");
    }

    /**
     * 获取系统亮度信息作为参考
     */
    private void appendSystemBrightnessInfo() {
        report.append("\n  系统亮度参考信息:\n");
        try {
            int brightness = HdrCapabilityHelper.getSystemBrightness(this);
            if (brightness >= 0) {
                float pct = brightness / 255f * 100;
                report.append("    当前系统亮度: ").append(brightness).append("/255")
                        .append(" (").append(String.format("%.0f%%", pct)).append(")\n");
            }

            boolean autoBrightness = HdrCapabilityHelper.isAutoBrightnessEnabled(this);
            report.append("    自动亮度: ").append(autoBrightness ? "✅ 开启" : "⬜ 关闭").append("\n");

            float windowBrightness = getWindow().getAttributes().screenBrightness;
            if (windowBrightness >= 0) {
                report.append("    当前窗口亮度: ").append(String.format("%.0f%%", windowBrightness * 100)).append("\n");
            } else {
                report.append("    当前窗口亮度: 跟随系统\n");
            }
        } catch (Exception e) {
            report.append("    获取系统亮度失败: ").append(e.getMessage()).append("\n");
        }
    }

    @SuppressLint("NewApi")
    private void appendVideoDecoderInfo() {
        report.append("【视频解码器】\n");

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        List<MediaCodecInfo> hevcDecoders = new ArrayList<>();
        List<MediaCodecInfo> avcDecoders = new ArrayList<>();
        List<MediaCodecInfo> av1Decoders = new ArrayList<>();

        for (MediaCodecInfo info : codecInfos) {
            if (info.isEncoder()) continue;
            String[] types = info.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase("video/hevc")) {
                    hevcDecoders.add(info);
                } else if (type.equalsIgnoreCase("video/avc")) {
                    avcDecoders.add(info);
                } else if (type.equalsIgnoreCase("video/av01")) {
                    av1Decoders.add(info);
                }
            }
        }

        appendCodecSection("HEVC (H.265)", hevcDecoders, "video/hevc");
        appendCodecSection("AVC (H.264)", avcDecoders, "video/avc");
        appendCodecSection("AV1", av1Decoders, "video/av01");
    }

    @SuppressLint("NewApi")
    private void appendCodecSection(String codecName, List<MediaCodecInfo> decoders, String mimeType) {
        report.append("\n  ").append(codecName).append(" 解码器 (").append(decoders.size()).append("个):\n");
        if (decoders.isEmpty()) {
            report.append("    ❌ 无可用解码器\n");
            return;
        }

        for (MediaCodecInfo info : decoders) {
            boolean isHardware = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isHardware = info.isHardwareAccelerated();
            } else {
                String name = info.getName().toLowerCase();
                isHardware = !name.contains("omx.google") && !name.contains("c2.android");
            }

            report.append("    ─────────────────────\n");
            report.append("    名称: ").append(info.getName()).append("\n");
            report.append("    类型: ").append(isHardware ? "🔧 硬件" : "💻 软件").append("\n");

            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mimeType);

                // Profile/Level support
                report.append("    配置文件:\n");
                boolean supportsMain10 = false;
                boolean supportsMain10Hdr10 = false;
                boolean supportsMain10Hdr10Plus = false;

                for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                    String profileName = getProfileName(mimeType, pl.profile);
                    if (profileName != null) {
                        // Only show interesting profiles
                        if (isInterestingProfile(mimeType, pl.profile)) {
                            report.append("      ").append(profileName).append("\n");
                        }
                        if (mimeType.equals("video/hevc")) {
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                                supportsMain10 = true;
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
                                supportsMain10Hdr10 = true;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                                supportsMain10Hdr10Plus = true;
                        }
                    }
                }

                // HDR decoding support summary
                if (mimeType.equals("video/hevc")) {
                    report.append("    HDR 解码:\n");
                    report.append("      10-bit (Main10): ").append(supportsMain10 ? "✅" : "❌").append("\n");
                    report.append("      HDR10 (PQ): ").append(supportsMain10Hdr10 ? "✅" : "❌").append("\n");
                    report.append("      HDR10+: ").append(supportsMain10Hdr10Plus ? "✅" : "❌").append("\n");
                    // HLG uses Main10 profile - no separate HLG profile in Android
                    report.append("      HLG: ").append(supportsMain10 ? "✅ (通过 Main10)" : "❌").append("\n");
                }

                // Color format support
                boolean supportsP010 = false;
                for (int colorFormat : caps.colorFormats) {
                    // COLOR_FormatYUVP010 = 54 (or vendor-specific)
                    if (colorFormat == 54) supportsP010 = true;
                }
                if (mimeType.equals("video/hevc") || mimeType.equals("video/av01")) {
                    report.append("    10-bit 输出 (P010): ").append(supportsP010 ? "✅" : "⚠️ 未检测到").append("\n");
                }

                // Max resolution
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps != null) {
                        int maxW = videoCaps.getSupportedWidths().getUpper();
                        int maxH = videoCaps.getSupportedHeights().getUpper();
                        report.append("    最大分辨率: ").append(maxW).append(" × ").append(maxH).append("\n");

                        // Check 4K support
                        try {
                            boolean supports4K = videoCaps.isSizeSupported(3840, 2160);
                            report.append("    4K (3840×2160): ").append(supports4K ? "✅" : "❌").append("\n");
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }

                // KEY_COLOR_TRANSFER_REQUEST support check (API 31+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mimeType.equals("video/hevc") && isHardware) {
                    appendColorTransferRequestCheck(info, mimeType, supportsMain10);
                }

            } catch (Exception e) {
                report.append("    获取能力信息失败: ").append(e.getMessage()).append("\n");
            }
        }
    }

    @SuppressLint("NewApi")
    private void appendColorTransferRequestCheck(MediaCodecInfo info, String mimeType, boolean supportsMain10) {
        if (!supportsMain10) return;
        report.append("    传递函数请求 (API 31+):\n");

        try {
            android.media.MediaCodec codec = android.media.MediaCodec.createByCodecName(info.getName());
            try {
                // Test HLG
                MediaFormat testFormat = MediaFormat.createVideoFormat(mimeType, 1920, 1080);
                testFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
                testFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                testFormat.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_HLG);

                codec.configure(testFormat, null, null, 0);
                MediaFormat inputFormat = codec.getInputFormat();
                int hlgResult = inputFormat.getInteger("color-transfer-request", 0);
                report.append("      HLG: ").append(hlgResult == MediaFormat.COLOR_TRANSFER_HLG ? "✅ 支持" : "❌ 不支持 (返回 " + hlgResult + ")").append("\n");
                codec.stop();
                codec.reset();

                // Test PQ
                MediaFormat testFormat2 = MediaFormat.createVideoFormat(mimeType, 1920, 1080);
                testFormat2.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
                testFormat2.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                testFormat2.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_ST2084);

                codec.configure(testFormat2, null, null, 0);
                MediaFormat inputFormat2 = codec.getInputFormat();
                int pqResult = inputFormat2.getInteger("color-transfer-request", 0);
                report.append("      PQ/ST2084: ").append(pqResult == MediaFormat.COLOR_TRANSFER_ST2084 ? "✅ 支持" : "❌ 不支持 (返回 " + pqResult + ")").append("\n");
                codec.stop();
            } finally {
                codec.release();
            }
        } catch (Exception e) {
            report.append("      检测失败: ").append(e.getMessage()).append("\n");
        }
    }

    private String getProfileName(String mimeType, int profile) {
        if (mimeType.equals("video/hevc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10: return "Main 10";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill: return "Main Still";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10: return "Main 10 HDR10";
                default:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                            return "Main 10 HDR10+";
                    }
                    return "Profile(" + profile + ")";
            }
        } else if (mimeType.equals("video/avc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline: return "Baseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh: return "High";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10: return "High 10";
                default: return "Profile(" + profile + ")";
            }
        } else if (mimeType.equals("video/av01")) {
            switch (profile) {
                case 1: return "Main";
                case 2: return "High";
                case 4: return "Professional";
                default: return "Profile(" + profile + ")";
            }
        }
        return "Profile(" + profile + ")";
    }

    private boolean isInterestingProfile(String mimeType, int profile) {
        if (mimeType.equals("video/hevc")) {
            return profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus);
        } else if (mimeType.equals("video/avc")) {
            return profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;
        }
        return true;
    }
}
