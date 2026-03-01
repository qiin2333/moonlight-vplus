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

            // Color mode info
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean isWideColorGamut = display.isWideColorGamut();
                report.append("  广色域: ").append(isWideColorGamut ? "✅ 支持" : "❌ 不支持").append("\n");
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ has isHdr()
                try {
                    boolean isHdr = display.isHdr();
                    report.append("  HDR 显示: ").append(isHdr ? "✅ 当前处于 HDR 模式" : "⬜ 非 HDR 模式").append("\n");
                } catch (NoSuchMethodError e) {
                    // isHdr() may not exist on all API 34 devices
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

        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) {
                report.append("  无法获取 Display\n\n");
                return;
            }

            Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
            if (hdrCaps == null) {
                report.append("  设备未报告 HDR 能力\n\n");
                return;
            }

            int[] types = hdrCaps.getSupportedHdrTypes();
            if (types.length == 0) {
                report.append("  ❌ 设备不支持任何 HDR 类型\n");
            } else {
                report.append("  支持的 HDR 类型:\n");
                boolean hasHlg = false, hasPq = false, hasHdr10Plus = false, hasDv = false;
                for (int type : types) {
                    switch (type) {
                        case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                            report.append("    ✅ Dolby Vision (type=1)\n");
                            hasDv = true;
                            break;
                        case Display.HdrCapabilities.HDR_TYPE_HDR10:
                            report.append("    ✅ HDR10 / PQ (type=2)\n");
                            hasPq = true;
                            break;
                        case Display.HdrCapabilities.HDR_TYPE_HLG:
                            report.append("    ✅ HLG (type=3)\n");
                            hasHlg = true;
                            break;
                        case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:
                            report.append("    ✅ HDR10+ (type=4)\n");
                            hasHdr10Plus = true;
                            break;
                        default:
                            report.append("    ❓ Unknown (type=").append(type).append(")\n");
                            break;
                    }
                }
                report.append("\n  串流兼容性:\n");
                report.append("    HLG 直通: ").append(hasHlg ? "✅ 设备支持" : "⚠️ 设备未声明支持，可能需要 tone-mapping").append("\n");
                report.append("    HDR10/PQ 直通: ").append(hasPq ? "✅ 设备支持" : "⚠️ 设备未声明支持").append("\n");
                report.append("    HDR10+ 动态元数据: ").append(hasHdr10Plus ? "✅ 设备支持" : "⬜ 不支持").append("\n");
            }

            float maxLum = hdrCaps.getDesiredMaxLuminance();
            float minLum = hdrCaps.getDesiredMinLuminance();
            float maxAvgLum = hdrCaps.getDesiredMaxAverageLuminance();
            report.append("\n  亮度范围:\n");
            report.append("    最大亮度: ").append(String.format("%.1f nits", maxLum)).append("\n");
            report.append("    最小亮度: ").append(String.format("%.4f nits", minLum)).append("\n");
            report.append("    最大平均亮度: ").append(String.format("%.1f nits", maxAvgLum)).append("\n");

            // Check if display reports reasonable HDR luminance
            if (maxLum > 0 && maxLum < 400) {
                report.append("    ⚠️ 最大亮度较低 (<400 nits)，HDR 效果可能有限\n");
            } else if (maxLum >= 1000) {
                report.append("    ✅ 高亮度面板 (≥1000 nits)，HDR 效果优秀\n");
            } else if (maxLum >= 600) {
                report.append("    ✅ 中等 HDR 面板 (600-1000 nits)\n");
            }
        } catch (Exception e) {
            report.append("  获取 HDR 能力失败: ").append(e.getMessage()).append("\n");
        }
        report.append("\n");
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
