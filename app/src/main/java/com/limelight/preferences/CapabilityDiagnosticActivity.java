package com.limelight.preferences;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.HdrCapabilityHelper;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码与屏幕能力检测页面
 * 使用卡片式 UI 展示设备的视频解码器能力、HDR 支持信息、屏幕参数等
 */
public class CapabilityDiagnosticActivity extends Activity {

    private LinearLayout container;
    private StringBuilder plainTextReport; // 用于剪贴板复制

    // 颜色常量
    private static final int COLOR_TEXT_PRIMARY = 0xFFEEEEEE;
    private static final int COLOR_TEXT_SECONDARY = 0xAAFFFFFF;
    private static final int COLOR_TEXT_MUTED = 0x66FFFFFF;
    private static final int COLOR_ACCENT = 0xFFFF6B9D;
    private static final int COLOR_SUCCESS = 0xFF4CAF50;
    private static final int COLOR_WARNING = 0xFFFF9800;
    private static final int COLOR_ERROR = 0xFFE53935;
    private static final int COLOR_INFO = 0xFF42A5F5;
    private static final int COLOR_DIVIDER = 0x1AFFFFFF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置状态栏颜色与背景一致
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(0xFF16162A);
            window.setNavigationBarColor(0xFF16162A);
        }

        setContentView(R.layout.activity_capability_diagnostic);

        UiHelper.setLocale(this);
        UiHelper.notifyNewRootView(this);

        container = findViewById(R.id.report_container);
        View copyButton = findViewById(R.id.btn_copy_report);
        View backButton = findViewById(R.id.btn_back);

        plainTextReport = new StringBuilder();
        generateReport();

        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Capability Report", plainTextReport.toString()));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(v -> finish());
    }

    private void generateReport() {
        plainTextReport.append("═══════════════════════════════════\n");
        plainTextReport.append("  设备能力检测报告\n");
        plainTextReport.append("═══════════════════════════════════\n\n");

        buildDeviceCard();
        buildDisplayCard();
        buildHdrCard();
        buildDecoderCards();

        plainTextReport.append("\n═══════════════════════════════════\n");
        plainTextReport.append("  报告生成完毕\n");
        plainTextReport.append("═══════════════════════════════════\n");
    }

    // ========================== 卡片构建 ==========================

    private void buildDeviceCard() {
        LinearLayout card = createCard("📱", "设备信息");
        plainTextReport.append("【设备信息】\n");

        addKeyValue(card, "品牌", Build.BRAND);
        addKeyValue(card, "型号", Build.MODEL);
        addKeyValue(card, "芯片", Build.HARDWARE);
        addKeyValue(card, "Android",
                Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        plainTextReport.append("  品牌: ").append(Build.BRAND).append("\n");
        plainTextReport.append("  型号: ").append(Build.MODEL).append("\n");
        plainTextReport.append("  芯片: ").append(Build.HARDWARE).append("\n");
        plainTextReport.append("  Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addKeyValue(card, "SOC", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
            plainTextReport.append("  SOC: ").append(Build.SOC_MANUFACTURER)
                    .append(" ").append(Build.SOC_MODEL).append("\n");
        }

        plainTextReport.append("\n");
        container.addView(card);
    }

    @SuppressLint("NewApi")
    private void buildDisplayCard() {
        LinearLayout card = createCard("🖥", "屏幕信息");
        plainTextReport.append("【屏幕信息】\n");

        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) {
                addBadge(card, "无法获取 Display 信息", COLOR_ERROR);
                plainTextReport.append("  无法获取\n\n");
                container.addView(card);
                return;
            }

            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            String res = metrics.widthPixels + " × " + metrics.heightPixels;
            addKeyValue(card, "分辨率", res);
            addKeyValue(card, "密度", metrics.densityDpi + " dpi");
            plainTextReport.append("  分辨率: ").append(res).append("\n");
            plainTextReport.append("  密度: ").append(metrics.densityDpi).append(" dpi\n");

            float rr = display.getRefreshRate();
            addKeyValue(card, "刷新率", String.format("%.1f Hz", rr));
            plainTextReport.append("  刷新率: ").append(String.format("%.1f Hz", rr)).append("\n");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode[] modes = display.getSupportedModes();
                if (modes.length > 1) {
                    addDivider(card);
                    addSectionLabel(card, "显示模式");
                    ViewGroup flow = createTagFlow();
                    for (Display.Mode mode : modes) {
                        String m = mode.getPhysicalWidth() + "×" + mode.getPhysicalHeight()
                                + " " + String.format("%.0fHz", mode.getRefreshRate());
                        addTagToFlow(flow, m);
                    }
                    card.addView(flow);
                }
            }
        } catch (Exception e) {
            addBadge(card, "获取失败: " + e.getMessage(), COLOR_ERROR);
        }

        plainTextReport.append("\n");
        container.addView(card);
    }

    @SuppressLint("NewApi")
    private void buildHdrCard() {
        LinearLayout card = createCard("🌈", "HDR 能力");
        plainTextReport.append("【HDR 能力】\n");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            addBadge(card, "需要 Android 7.0+", COLOR_WARNING);
            plainTextReport.append("  需要 Android 7.0+\n\n");
            container.addView(card);
            return;
        }

        HdrCapabilityHelper.HdrCapabilityInfo capInfo =
                HdrCapabilityHelper.getFullCapabilityInfo(this);
        HdrCapabilityHelper.BrightnessInfo brightness = capInfo.brightness;
        HdrCapabilityHelper.HdrTypeSupport ts = capInfo.typeSupport;

        // 系统检测
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addStatusRow(card, "屏幕 HDR", capInfo.isScreenHdr, "isScreenHdr()");
            addStatusRow(card, "广色域", capInfo.isWideColorGamut, null);
            plainTextReport.append("  isScreenHdr: ").append(capInfo.isScreenHdr).append("\n");
            plainTextReport.append("  广色域: ").append(capInfo.isWideColorGamut).append("\n");
        }

        // HDR 类型
        addDivider(card);
        addSectionLabel(card, "HDR 类型");

        if (ts.rawTypes.length == 0) {
            addBadge(card, "设备不支持任何 HDR 类型", COLOR_WARNING);
            plainTextReport.append("  ❌ 无 HDR 类型\n");
        } else {
            addHdrTypeBadge(card, "Dolby Vision", ts.hasDolbyVision);
            addHdrTypeBadge(card, "HDR10 / PQ", ts.hasHdr10);
            addHdrTypeBadge(card, "HLG", ts.hasHlg);
            addHdrTypeBadge(card, "HDR10+", ts.hasHdr10Plus);

            plainTextReport.append("  DV: ").append(ts.hasDolbyVision ? "✅" : "❌")
                    .append("  HDR10: ").append(ts.hasHdr10 ? "✅" : "❌")
                    .append("  HLG: ").append(ts.hasHlg ? "✅" : "❌")
                    .append("  HDR10+: ").append(ts.hasHdr10Plus ? "✅" : "❌").append("\n");

            // 串流兼容性
            addDivider(card);
            addSectionLabel(card, "串流兼容性");
            addCompatRow(card, "HLG 直通", ts.hasHlg, "设备支持", "设备未声明");
            addCompatRow(card, "HDR10/PQ 直通", ts.hasHdr10, "设备支持", "设备未声明");
        }

        // 亮度
        addDivider(card);
        addSectionLabel(card, "屏幕亮度");

        String maxDesc = String.format("%.0f nits", brightness.maxLuminance);
        if (brightness.isDefault) maxDesc += " (默认)";
        else if (brightness.isFromHdrCaps) maxDesc += " (EDID)";
        addKeyValue(card, "峰值亮度", maxDesc);
        addKeyValue(card, "最小亮度", String.format("%.4f nits", brightness.minLuminance));
        addKeyValue(card, "平均亮度", String.format("%.0f nits", brightness.maxAvgLuminance));

        plainTextReport.append("  峰值: ").append(maxDesc).append("\n");
        plainTextReport.append("  最小: ").append(String.format("%.4f", brightness.minLuminance)).append("\n");
        plainTextReport.append("  平均: ").append(String.format("%.0f", brightness.maxAvgLuminance)).append("\n");

        // 亮度评级
        if (brightness.isDefault) {
            addBadge(card, "⚠ 驱动未报告 EDID 亮度", COLOR_WARNING);
        } else if (brightness.maxLuminance >= 1000) {
            addBadge(card, "✦ 高阶 HDR 面板 ≥1000 nits", COLOR_SUCCESS);
        } else if (brightness.maxLuminance >= 600) {
            addBadge(card, "✦ 中等 HDR 面板", COLOR_SUCCESS);
        } else if (brightness.maxLuminance < 400) {
            addBadge(card, "⚠ 峰值亮度偏低 <400 nits", COLOR_WARNING);
        }

        // HDR/SDR Ratio
        addDivider(card);
        addSectionLabel(card, "HDR/SDR 动态比率");

        if (Build.VERSION.SDK_INT < 34) {
            addBadge(card, "需要 Android 14+ (API 34)", COLOR_TEXT_MUTED);
            plainTextReport.append("  HDR/SDR Ratio: 需要 API 34+\n");
        } else if (!brightness.isHdrSdrRatioAvailable) {
            addBadge(card, "设备不支持 ratio 查询", COLOR_WARNING);
            plainTextReport.append("  HDR/SDR Ratio: 不支持\n");
        } else {
            addKeyValue(card, "当前比率", String.format("%.2f×", brightness.hdrSdrRatio));
            addKeyValue(card, "最高比率",
                    String.format("%.2f×", brightness.highestHdrSdrRatio)
                            + (Build.VERSION.SDK_INT >= 36 ? "" : " (≈当前)"));
            plainTextReport.append("  当前 ratio: ").append(String.format("%.2f", brightness.hdrSdrRatio)).append("\n");
            plainTextReport.append("  最高 ratio: ").append(String.format("%.2f", brightness.highestHdrSdrRatio)).append("\n");

            if (brightness.isComputedFromRatio && brightness.computedPeakBrightness > 0) {
                addKeyValue(card, "Ratio 峰值",
                        String.format("%.0f nits", brightness.computedPeakBrightness));
                plainTextReport.append("  Ratio 峰值: ").append(
                        String.format("%.0f", brightness.computedPeakBrightness)).append("\n");
            }
            addBadge(card, "ℹ Android 未公开 sdrNits，精度受限", COLOR_INFO);
        }

        // 上报服务端
        addDivider(card);
        addSectionLabel(card, "上报服务端");

        int[] sv = HdrCapabilityHelper.getBrightnessRangeAsInts(this);
        addKeyValue(card, "minBrightness", sv[0] + " nits");
        addKeyValue(card, "maxBrightness", sv[1] + " nits");
        addKeyValue(card, "maxAvgBrightness", sv[2] + " nits");
        plainTextReport.append("  上报: min=").append(sv[0]).append(" max=").append(sv[1])
                .append(" avg=").append(sv[2]).append("\n");

        // 系统亮度
        addDivider(card);
        addSectionLabel(card, "系统亮度");

        int sysBr = HdrCapabilityHelper.getSystemBrightness(this);
        if (sysBr >= 0) {
            addKeyValue(card, "当前亮度",
                    sysBr + "/255 (" + String.format("%.0f%%", sysBr / 255f * 100) + ")");
            plainTextReport.append("  系统亮度: ").append(sysBr).append("/255\n");
        }
        boolean autoBr = HdrCapabilityHelper.isAutoBrightnessEnabled(this);
        addKeyValue(card, "自动亮度", autoBr ? "开启" : "关闭");
        plainTextReport.append("  自动亮度: ").append(autoBr).append("\n\n");

        container.addView(card);
    }

    @SuppressLint("NewApi")
    private void buildDecoderCards() {
        plainTextReport.append("【视频解码器】\n");

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        List<MediaCodecInfo> hevc = new ArrayList<>(), avc = new ArrayList<>(), av1 = new ArrayList<>();
        for (MediaCodecInfo info : codecInfos) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase("video/hevc")) hevc.add(info);
                else if (type.equalsIgnoreCase("video/avc")) avc.add(info);
                else if (type.equalsIgnoreCase("video/av01")) av1.add(info);
            }
        }

        buildOneCodecCard("🎬", "HEVC (H.265)", hevc, "video/hevc");
        buildOneCodecCard("🎞", "AVC (H.264)", avc, "video/avc");
        buildOneCodecCard("🔮", "AV1", av1, "video/av01");
    }

    @SuppressLint("NewApi")
    private void buildOneCodecCard(String icon, String codecName,
                                   List<MediaCodecInfo> decoders, String mime) {
        LinearLayout card = createCard(icon, codecName + "  (" + decoders.size() + ")");
        plainTextReport.append("\n  ").append(codecName).append(" (").append(decoders.size()).append("):\n");

        if (decoders.isEmpty()) {
            addBadge(card, "无可用解码器", COLOR_ERROR);
            plainTextReport.append("    ❌ 无\n");
            container.addView(card);
            return;
        }

        boolean first = true;
        for (MediaCodecInfo info : decoders) {
            if (!first) addDivider(card);
            first = false;

            boolean isHw = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isHw = info.isHardwareAccelerated();
            } else {
                String n = info.getName().toLowerCase();
                isHw = !n.contains("omx.google") && !n.contains("c2.android");
            }

            // 名称 + 硬件/软件标签
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(4);
            nameRow.setLayoutParams(np);

            TextView nameView = new TextView(this);
            nameView.setText(info.getName());
            nameView.setTextColor(COLOR_TEXT_PRIMARY);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            nameView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            nameRow.addView(nameView);

            TextView hwTag = new TextView(this);
            hwTag.setText(isHw ? "硬件" : "软件");
            hwTag.setTextColor(isHw ? COLOR_SUCCESS : COLOR_TEXT_SECONDARY);
            hwTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            hwTag.setBackgroundResource(R.drawable.diag_tag_background);
            hwTag.setPadding(dp(8), dp(2), dp(8), dp(2));
            nameRow.addView(hwTag);
            card.addView(nameRow);

            plainTextReport.append("    ").append(info.getName())
                    .append(isHw ? " [硬件]" : " [软件]").append("\n");

            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);

                // Profile tags
                boolean main10 = false, hdr10 = false, hdr10p = false;
                ViewGroup tagFlow = createTagFlow();
                for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                    String pn = getProfileName(mime, pl.profile);
                    if (pn != null && isInterestingProfile(mime, pl.profile)) {
                        addTagToFlow(tagFlow, pn);
                        plainTextReport.append("      ").append(pn).append("\n");
                    }
                    if (mime.equals("video/hevc")) {
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true;
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) hdr10 = true;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) hdr10p = true;
                    }
                }
                card.addView(tagFlow);

                // HDR grid for HEVC
                if (mime.equals("video/hevc")) {
                    LinearLayout hdrRow = new LinearLayout(this);
                    hdrRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams hrp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    hrp.topMargin = dp(6);
                    hdrRow.setLayoutParams(hrp);

                    addMiniStatus(hdrRow, "10bit", main10);
                    addMiniStatus(hdrRow, "HDR10", hdr10);
                    addMiniStatus(hdrRow, "HDR10+", hdr10p);
                    addMiniStatus(hdrRow, "HLG", main10);
                    card.addView(hdrRow);

                    plainTextReport.append("      10bit=").append(main10 ? "✅" : "❌")
                            .append(" HDR10=").append(hdr10 ? "✅" : "❌")
                            .append(" HDR10+=").append(hdr10p ? "✅" : "❌")
                            .append(" HLG=").append(main10 ? "✅" : "❌").append("\n");
                }

                // Capabilities row: 4K, P010, resolution
                boolean p010 = false;
                for (int cf : caps.colorFormats) { if (cf == 54) p010 = true; }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    if (vc != null) {
                        int mw = vc.getSupportedWidths().getUpper();
                        int mh = vc.getSupportedHeights().getUpper();
                        boolean is4k = false;
                        try { is4k = vc.isSizeSupported(3840, 2160); } catch (Exception e) {}

                        LinearLayout capRow = new LinearLayout(this);
                        capRow.setOrientation(LinearLayout.HORIZONTAL);
                        capRow.setGravity(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        crp.topMargin = dp(6);
                        capRow.setLayoutParams(crp);

                        addMiniStatus(capRow, "4K", is4k);
                        if (mime.equals("video/hevc") || mime.equals("video/av01")) {
                            addMiniStatus(capRow, "P010", p010);
                        }

                        TextView resTag = new TextView(this);
                        resTag.setText("Max " + mw + "×" + mh);
                        resTag.setTextColor(COLOR_TEXT_MUTED);
                        resTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                        resTag.setPadding(dp(8), 0, 0, 0);
                        capRow.addView(resTag);
                        card.addView(capRow);

                        plainTextReport.append("      4K=").append(is4k ? "✅" : "❌")
                                .append(" P010=").append(p010 ? "✅" : "❌")
                                .append(" Max=").append(mw).append("×").append(mh).append("\n");
                    }
                }

                // COLOR_TRANSFER_REQUEST check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && mime.equals("video/hevc") && isHw && main10) {
                    buildTransferRequestCheck(card, info, mime);
                }

            } catch (Exception e) {
                addBadge(card, "能力查询失败", COLOR_ERROR);
                plainTextReport.append("      失败: ").append(e.getMessage()).append("\n");
            }
        }
        container.addView(card);
    }

    @SuppressLint("NewApi")
    private void buildTransferRequestCheck(LinearLayout card, MediaCodecInfo info, String mime) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        try {
            android.media.MediaCodec codec =
                    android.media.MediaCodec.createByCodecName(info.getName());
            try {
                MediaFormat f1 = MediaFormat.createVideoFormat(mime, 1920, 1080);
                f1.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
                f1.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                f1.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_HLG);
                codec.configure(f1, null, null, 0);
                boolean hlgOk = codec.getInputFormat()
                        .getInteger("color-transfer-request", 0) == MediaFormat.COLOR_TRANSFER_HLG;
                codec.stop(); codec.reset();

                MediaFormat f2 = MediaFormat.createVideoFormat(mime, 1920, 1080);
                f2.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
                f2.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                f2.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_ST2084);
                codec.configure(f2, null, null, 0);
                boolean pqOk = codec.getInputFormat()
                        .getInteger("color-transfer-request", 0) == MediaFormat.COLOR_TRANSFER_ST2084;
                codec.stop();

                addMiniStatus(row, "HLG透传", hlgOk);
                addMiniStatus(row, "PQ透传", pqOk);
                plainTextReport.append("      HLG透传=").append(hlgOk ? "✅" : "❌")
                        .append(" PQ透传=").append(pqOk ? "✅" : "❌").append("\n");
            } finally {
                codec.release();
            }
        } catch (Exception e) {
            addBadge(card, "传递函数检测失败", COLOR_WARNING);
            plainTextReport.append("      传递函数检测失败\n");
        }
        card.addView(row);
    }

    // ========================== UI 工具方法 ==========================

    private LinearLayout createCard(String icon, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.diag_card_background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(10);
        titleRow.setLayoutParams(tp);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        iconView.setPadding(0, 0, dp(10), 0);
        titleRow.addView(iconView);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(null, Typeface.BOLD);
        titleRow.addView(titleView);

        card.addView(titleRow);
        return card;
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(COLOR_TEXT_SECONDARY);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        k.setLayoutParams(new LinearLayout.LayoutParams(
                dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(COLOR_TEXT_PRIMARY);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(v);

        parent.addView(row);
    }

    private void addDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        d.setLayoutParams(lp);
        parent.addView(d);
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextColor(COLOR_ACCENT);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setTypeface(null, Typeface.BOLD);
        l.setAllCaps(true);
        l.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        l.setLayoutParams(lp);
        parent.addView(l);
    }

    private void addStatusRow(LinearLayout parent, String label, boolean ok, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);

        TextView dot = new TextView(this);
        dot.setText(ok ? "●" : "○");
        dot.setTextColor(ok ? COLOR_SUCCESS : COLOR_TEXT_MUTED);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        dot.setPadding(0, 0, dp(8), 0);
        row.addView(dot);

        TextView lbl = new TextView(this);
        lbl.setText(note != null ? label + "  " + note : label);
        lbl.setTextColor(ok ? COLOR_TEXT_PRIMARY : COLOR_TEXT_SECONDARY);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(lbl);

        parent.addView(row);
    }

    private void addHdrTypeBadge(LinearLayout parent, String name, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText(ok ? "✓" : "✗");
        icon.setTextColor(ok ? COLOR_SUCCESS : COLOR_TEXT_MUTED);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        icon.setTypeface(null, Typeface.BOLD);
        icon.setPadding(dp(4), 0, dp(10), 0);
        row.addView(icon);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(ok ? COLOR_TEXT_PRIMARY : COLOR_TEXT_MUTED);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(n);

        parent.addView(row);
    }

    private void addCompatRow(LinearLayout parent, String feature, boolean ok,
                              String okMsg, String failMsg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        row.setLayoutParams(lp);

        TextView feat = new TextView(this);
        feat.setText(feature);
        feat.setTextColor(COLOR_TEXT_SECONDARY);
        feat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        feat.setLayoutParams(new LinearLayout.LayoutParams(
                dp(100), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(feat);

        TextView st = new TextView(this);
        st.setText(ok ? "✓ " + okMsg : "⚠ " + failMsg);
        st.setTextColor(ok ? COLOR_SUCCESS : COLOR_WARNING);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(st);

        parent.addView(row);
        plainTextReport.append("  ").append(feature).append(": ")
                .append(ok ? "✅ " + okMsg : "⚠️ " + failMsg).append("\n");
    }

    private void addBadge(LinearLayout parent, String msg, int color) {
        TextView b = new TextView(this);
        b.setText(msg);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int bg;
        if (color == COLOR_SUCCESS) bg = R.drawable.diag_badge_success;
        else if (color == COLOR_WARNING) bg = R.drawable.diag_badge_warning;
        else if (color == COLOR_ERROR) bg = R.drawable.diag_badge_error;
        else bg = R.drawable.diag_badge_info;
        b.setBackgroundResource(bg);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        b.setLayoutParams(lp);
        parent.addView(b);
    }

    private ViewGroup createTagFlow() {
        FlowLayout flow = new FlowLayout(this, dp(6), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        flow.setLayoutParams(lp);
        return flow;
    }

    private void addTagToFlow(ViewGroup flow, String text) {
        TextView tag = new TextView(this);
        tag.setText(text);
        tag.setTextColor(COLOR_TEXT_SECONDARY);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tag.setBackgroundResource(R.drawable.diag_tag_background);
        tag.setPadding(dp(8), dp(3), dp(8), dp(3));
        flow.addView(tag);
    }

    /**
     * 自动换行的流式布局
     */
    private static class FlowLayout extends ViewGroup {
        private final int hGap;
        private final int vGap;

        FlowLayout(Context context, int horizontalGap, int verticalGap) {
            super(context);
            this.hGap = horizontalGap;
            this.vGap = verticalGap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            int maxWidth = widthMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : widthSize;

            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowHeight = 0;

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(
                        MeasureSpec.makeMeasureSpec(maxWidth - getPaddingLeft() - getPaddingRight(), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();

                if (x + cw + getPaddingRight() > maxWidth && x > getPaddingLeft()) {
                    x = getPaddingLeft();
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }

            int totalHeight = y + rowHeight + getPaddingBottom();
            setMeasuredDimension(
                    widthMode == MeasureSpec.EXACTLY ? widthSize : maxWidth,
                    resolveSize(totalHeight, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxWidth = r - l;
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowHeight = 0;

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();

                if (x + cw + getPaddingRight() > maxWidth && x > getPaddingLeft()) {
                    x = getPaddingLeft();
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
        }
    }

    private void addMiniStatus(LinearLayout parent, String label, boolean ok) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, 0, dp(12), 0);

        TextView dot = new TextView(this);
        dot.setText(ok ? "●" : "○");
        dot.setTextColor(ok ? COLOR_SUCCESS : 0x44FFFFFF);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        dot.setPadding(0, 0, dp(4), 0);
        item.addView(dot);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextColor(ok ? COLOR_TEXT_PRIMARY : COLOR_TEXT_MUTED);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        item.addView(txt);

        parent.addView(item);
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    // ========================== Profile 工具 ==========================

    private String getProfileName(String mime, int profile) {
        if (mime.equals("video/hevc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10: return "Main10";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill: return "Still";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10: return "HDR10";
                default:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                        return "HDR10+";
                    return null;
            }
        } else if (mime.equals("video/avc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline: return "Baseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh: return "High";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10: return "High10";
                default: return null;
            }
        } else if (mime.equals("video/av01")) {
            switch (profile) {
                case 1: return "Main";
                case 2: return "High";
                case 4: return "Pro";
                default: return null;
            }
        }
        return null;
    }

    private boolean isInterestingProfile(String mime, int profile) {
        if (mime.equals("video/hevc")) {
            return profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus);
        } else if (mime.equals("video/avc")) {
            return profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;
        }
        return true;
    }
}
