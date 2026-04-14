@file:Suppress("DEPRECATION")
package com.limelight.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.limelight.R
import com.limelight.utils.HdrCapabilityHelper
import com.limelight.utils.UiHelper

/**
 * 编解码与屏幕能力检测页面
 * 使用卡片式 UI 展示设备的视频解码器能力、HDR 支持信息、屏幕参数等
 */
class CapabilityDiagnosticActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var plainTextReport: StringBuilder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val window: Window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = 0xFF16162A.toInt()
            window.navigationBarColor = 0xFF16162A.toInt()
        }

        setContentView(R.layout.activity_capability_diagnostic)

        UiHelper.setLocale(this)
        UiHelper.notifyNewRootView(this)

        container = findViewById(R.id.report_container)
        val copyButton: View = findViewById(R.id.btn_copy_report)
        val backButton: View = findViewById(R.id.btn_back)

        plainTextReport = StringBuilder()
        generateReport()

        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Capability Report", plainTextReport.toString()))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        backButton.setOnClickListener { finish() }
    }

    private fun generateReport() {
        plainTextReport.append("═══════════════════════════════════\n")
        plainTextReport.append("  设备能力检测报告\n")
        plainTextReport.append("═══════════════════════════════════\n\n")

        buildDeviceCard()
        buildDisplayCard()
        buildHdrCard()
        buildDecoderCards()

        plainTextReport.append("\n═══════════════════════════════════\n")
        plainTextReport.append("  报告生成完毕\n")
        plainTextReport.append("═══════════════════════════════════\n")
    }

    // ========================== 卡片构建 ==========================

    private fun buildDeviceCard() {
        val card = createCard("📱", "设备信息")
        plainTextReport.append("【设备信息】\n")

        addKeyValue(card, "品牌", Build.BRAND)
        addKeyValue(card, "型号", Build.MODEL)
        addKeyValue(card, "芯片", Build.HARDWARE)
        addKeyValue(card, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        plainTextReport.append("  品牌: ").append(Build.BRAND).append("\n")
        plainTextReport.append("  型号: ").append(Build.MODEL).append("\n")
        plainTextReport.append("  芯片: ").append(Build.HARDWARE).append("\n")
        plainTextReport.append("  Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addKeyValue(card, "SOC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            plainTextReport.append("  SOC: ").append(Build.SOC_MANUFACTURER)
                .append(" ").append(Build.SOC_MODEL).append("\n")
        }

        plainTextReport.append("\n")
        container.addView(card)
    }

    @SuppressLint("NewApi")
    private fun buildDisplayCard() {
        val card = createCard("🖥", "屏幕信息")
        plainTextReport.append("【屏幕信息】\n")

        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                addBadge(card, "无法获取 Display 信息", COLOR_ERROR)
                plainTextReport.append("  无法获取\n\n")
                container.addView(card)
                return
            }

            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val res = "${metrics.widthPixels} × ${metrics.heightPixels}"
            addKeyValue(card, "分辨率", res)
            addKeyValue(card, "密度", "${metrics.densityDpi} dpi")
            plainTextReport.append("  分辨率: ").append(res).append("\n")
            plainTextReport.append("  密度: ").append(metrics.densityDpi).append(" dpi\n")

            val rr = display.refreshRate
            addKeyValue(card, "刷新率", String.format("%.1f Hz", rr))
            plainTextReport.append("  刷新率: ").append(String.format("%.1f Hz", rr)).append("\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val modes = display.supportedModes
                if (modes.size > 1) {
                    addDivider(card)
                    addSectionLabel(card, "显示模式")
                    val flow = createTagFlow()
                    for (mode in modes) {
                        val m = "${mode.physicalWidth}×${mode.physicalHeight} ${String.format("%.0fHz", mode.refreshRate)}"
                        addTagToFlow(flow, m)
                    }
                    card.addView(flow)
                }
            }
        } catch (e: Exception) {
            addBadge(card, "获取失败: ${e.message}", COLOR_ERROR)
        }

        plainTextReport.append("\n")
        container.addView(card)
    }

    @SuppressLint("NewApi")
    private fun buildHdrCard() {
        val card = createCard("🌈", "HDR 能力")
        plainTextReport.append("【HDR 能力】\n")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            addBadge(card, "需要 Android 7.0+", COLOR_WARNING)
            plainTextReport.append("  需要 Android 7.0+\n\n")
            container.addView(card)
            return
        }

        val capInfo = HdrCapabilityHelper.getFullCapabilityInfo(this)
        val brightness = capInfo.brightness
        val ts = capInfo.typeSupport

        // 系统检测
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addStatusRow(card, "屏幕 HDR", capInfo.isScreenHdr, "isScreenHdr()")
            addStatusRow(card, "广色域", capInfo.isWideColorGamut, null)
            plainTextReport.append("  isScreenHdr: ").append(capInfo.isScreenHdr).append("\n")
            plainTextReport.append("  广色域: ").append(capInfo.isWideColorGamut).append("\n")
        }

        // HDR 类型
        addDivider(card)
        addSectionLabel(card, "HDR 类型")

        if (ts.rawTypes.isEmpty()) {
            addBadge(card, "设备不支持任何 HDR 类型", COLOR_WARNING)
            plainTextReport.append("  ❌ 无 HDR 类型\n")
        } else {
            addHdrTypeBadge(card, "Dolby Vision", ts.hasDolbyVision)
            addHdrTypeBadge(card, "HDR10 / PQ", ts.hasHdr10)
            addHdrTypeBadge(card, "HLG", ts.hasHlg)
            addHdrTypeBadge(card, "HDR10+", ts.hasHdr10Plus)

            plainTextReport.append("  DV: ").append(if (ts.hasDolbyVision) "✅" else "❌")
                .append("  HDR10: ").append(if (ts.hasHdr10) "✅" else "❌")
                .append("  HLG: ").append(if (ts.hasHlg) "✅" else "❌")
                .append("  HDR10+: ").append(if (ts.hasHdr10Plus) "✅" else "❌").append("\n")

            // 串流兼容性
            addDivider(card)
            addSectionLabel(card, "串流兼容性")
            addCompatRow(card, "HLG 直通", ts.hasHlg, "设备支持", "设备未声明")
            addCompatRow(card, "HDR10/PQ 直通", ts.hasHdr10, "设备支持", "设备未声明")
        }

        // 亮度
        addDivider(card)
        addSectionLabel(card, "屏幕亮度")

        var maxDesc = String.format("%.0f nits", brightness.maxLuminance)
        if (brightness.isDefault) maxDesc += " (默认)"
        else if (brightness.isFromHdrCaps) maxDesc += " (EDID)"
        addKeyValue(card, "峰值亮度", maxDesc)
        addKeyValue(card, "最小亮度", String.format("%.4f nits", brightness.minLuminance))
        addKeyValue(card, "平均亮度", String.format("%.0f nits", brightness.maxAvgLuminance))

        plainTextReport.append("  峰值: ").append(maxDesc).append("\n")
        plainTextReport.append("  最小: ").append(String.format("%.4f", brightness.minLuminance)).append("\n")
        plainTextReport.append("  平均: ").append(String.format("%.0f", brightness.maxAvgLuminance)).append("\n")

        // 亮度评级
        when {
            brightness.isDefault ->
                addBadge(card, "⚠ 驱动未报告 EDID 亮度", COLOR_WARNING)
            brightness.maxLuminance >= 1000 ->
                addBadge(card, "✦ 高阶 HDR 面板 ≥1000 nits", COLOR_SUCCESS)
            brightness.maxLuminance >= 600 ->
                addBadge(card, "✦ 中等 HDR 面板", COLOR_SUCCESS)
            brightness.maxLuminance < 400 ->
                addBadge(card, "⚠ 峰值亮度偏低 <400 nits", COLOR_WARNING)
        }

        // HDR/SDR Ratio
        addDivider(card)
        addSectionLabel(card, "HDR/SDR 动态比率")

        if (Build.VERSION.SDK_INT < 34) {
            addBadge(card, "需要 Android 14+ (API 34)", COLOR_TEXT_MUTED)
            plainTextReport.append("  HDR/SDR Ratio: 需要 API 34+\n")
        } else if (!brightness.isHdrSdrRatioAvailable) {
            addBadge(card, "设备不支持 ratio 查询", COLOR_WARNING)
            plainTextReport.append("  HDR/SDR Ratio: 不支持\n")
        } else {
            addKeyValue(card, "当前比率", String.format("%.2f×", brightness.hdrSdrRatio))
            addKeyValue(card, "最高比率",
                String.format("%.2f×", brightness.highestHdrSdrRatio) +
                    if (Build.VERSION.SDK_INT >= 36) "" else " (≈当前)")
            plainTextReport.append("  当前 ratio: ").append(String.format("%.2f", brightness.hdrSdrRatio)).append("\n")
            plainTextReport.append("  最高 ratio: ").append(String.format("%.2f", brightness.highestHdrSdrRatio)).append("\n")

            if (brightness.isComputedFromRatio && brightness.computedPeakBrightness > 0) {
                addKeyValue(card, "Ratio 峰值",
                    String.format("%.0f nits", brightness.computedPeakBrightness))
                plainTextReport.append("  Ratio 峰值: ").append(
                    String.format("%.0f", brightness.computedPeakBrightness)).append("\n")
            }
            addBadge(card, "ℹ Android 未公开 sdrNits，精度受限", COLOR_INFO)
        }

        // 上报服务端
        addDivider(card)
        addSectionLabel(card, "上报服务端")

        val sv = HdrCapabilityHelper.getBrightnessRangeAsInts(this)
        addKeyValue(card, "minBrightness", "${sv[0]} nits")
        addKeyValue(card, "maxBrightness", "${sv[1]} nits")
        addKeyValue(card, "maxAvgBrightness", "${sv[2]} nits")
        plainTextReport.append("  上报: min=").append(sv[0]).append(" max=").append(sv[1])
            .append(" avg=").append(sv[2]).append("\n")

        // 系统亮度
        addDivider(card)
        addSectionLabel(card, "系统亮度")

        val sysBr = HdrCapabilityHelper.getSystemBrightness(this)
        if (sysBr >= 0) {
            addKeyValue(card, "当前亮度",
                "$sysBr/255 (${String.format("%.0f%%", sysBr / 255f * 100)})")
            plainTextReport.append("  系统亮度: ").append(sysBr).append("/255\n")
        }
        val autoBr = HdrCapabilityHelper.isAutoBrightnessEnabled(this)
        addKeyValue(card, "自动亮度", if (autoBr) "开启" else "关闭")
        plainTextReport.append("  自动亮度: ").append(autoBr).append("\n\n")

        container.addView(card)
    }

    @SuppressLint("NewApi")
    private fun buildDecoderCards() {
        plainTextReport.append("【视频解码器】\n")

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfos = codecList.codecInfos

        val hevc = mutableListOf<MediaCodecInfo>()
        val avc = mutableListOf<MediaCodecInfo>()
        val av1 = mutableListOf<MediaCodecInfo>()
        for (info in codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                when {
                    type.equals("video/hevc", ignoreCase = true) -> hevc.add(info)
                    type.equals("video/avc", ignoreCase = true) -> avc.add(info)
                    type.equals("video/av01", ignoreCase = true) -> av1.add(info)
                }
            }
        }

        buildOneCodecCard("🎬", "HEVC (H.265)", hevc, "video/hevc")
        buildOneCodecCard("🎞", "AVC (H.264)", avc, "video/avc")
        buildOneCodecCard("🔮", "AV1", av1, "video/av01")
    }

    @SuppressLint("NewApi")
    private fun buildOneCodecCard(icon: String, codecName: String,
                                  decoders: List<MediaCodecInfo>, mime: String) {
        val card = createCard(icon, "$codecName  (${decoders.size})")
        plainTextReport.append("\n  ").append(codecName).append(" (").append(decoders.size).append("):\n")

        if (decoders.isEmpty()) {
            addBadge(card, "无可用解码器", COLOR_ERROR)
            plainTextReport.append("    ❌ 无\n")
            container.addView(card)
            return
        }

        var first = true
        for (info in decoders) {
            if (!first) addDivider(card)
            first = false

            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                val n = info.name.lowercase()
                !n.contains("omx.google") && !n.contains("c2.android")
            }

            // 名称 + 硬件/软件标签
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4f) }
            }

            val nameView = TextView(this).apply {
                text = info.name
                setTextColor(COLOR_TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameRow.addView(nameView)

            val hwTag = TextView(this).apply {
                text = if (isHw) "硬件" else "软件"
                setTextColor(if (isHw) COLOR_SUCCESS else COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setBackgroundResource(R.drawable.diag_tag_background)
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            }
            nameRow.addView(hwTag)
            card.addView(nameRow)

            plainTextReport.append("    ").append(info.name)
                .append(if (isHw) " [硬件]" else " [软件]").append("\n")

            try {
                val caps = info.getCapabilitiesForType(mime)

                // Profile tags
                var main10 = false
                var hdr10 = false
                var hdr10p = false
                val tagFlow = createTagFlow()
                for (pl in caps.profileLevels) {
                    val pn = getProfileName(mime, pl.profile)
                    if (pn != null && isInterestingProfile(mime, pl.profile)) {
                        addTagToFlow(tagFlow, pn)
                        plainTextReport.append("      ").append(pn).append("\n")
                    }
                    if (mime == "video/hevc") {
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) hdr10 = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) hdr10p = true
                    }
                }
                card.addView(tagFlow)

                // HDR grid for HEVC
                if (mime == "video/hevc") {
                    val hdrRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6f) }
                    }

                    addMiniStatus(hdrRow, "10bit", main10)
                    addMiniStatus(hdrRow, "HDR10", hdr10)
                    addMiniStatus(hdrRow, "HDR10+", hdr10p)
                    addMiniStatus(hdrRow, "HLG", main10)
                    card.addView(hdrRow)

                    plainTextReport.append("      10bit=").append(if (main10) "✅" else "❌")
                        .append(" HDR10=").append(if (hdr10) "✅" else "❌")
                        .append(" HDR10+=").append(if (hdr10p) "✅" else "❌")
                        .append(" HLG=").append(if (main10) "✅" else "❌").append("\n")
                }

                // Capabilities row: 4K, P010, resolution
                var p010 = false
                for (cf in caps.colorFormats) { if (cf == 54) p010 = true }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        val mw = vc.supportedWidths.upper
                        val mh = vc.supportedHeights.upper
                        val is4k = try { vc.isSizeSupported(3840, 2160) } catch (_: Exception) { false }

                        val capRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(6f) }
                        }

                        addMiniStatus(capRow, "4K", is4k)
                        if (mime == "video/hevc" || mime == "video/av01") {
                            addMiniStatus(capRow, "P010", p010)
                        }

                        val resTag = TextView(this).apply {
                            text = "Max ${mw}×${mh}"
                            setTextColor(COLOR_TEXT_MUTED)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            setPadding(dp(8f), 0, 0, 0)
                        }
                        capRow.addView(resTag)
                        card.addView(capRow)

                        plainTextReport.append("      4K=").append(if (is4k) "✅" else "❌")
                            .append(" P010=").append(if (p010) "✅" else "❌")
                            .append(" Max=").append(mw).append("×").append(mh).append("\n")
                    }
                }

                // COLOR_TRANSFER_REQUEST check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && mime == "video/hevc" && isHw && main10) {
                    buildTransferRequestCheck(card, info, mime)
                }

            } catch (e: Exception) {
                addBadge(card, "能力查询失败", COLOR_ERROR)
                plainTextReport.append("      失败: ").append(e.message).append("\n")
            }
        }
        container.addView(card)
    }

    @SuppressLint("NewApi")
    private fun buildTransferRequestCheck(card: LinearLayout, info: MediaCodecInfo, mime: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        }

        try {
            val codec = android.media.MediaCodec.createByCodecName(info.name)
            try {
                val f1 = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_HLG)
                }
                codec.configure(f1, null, null, 0)
                val hlgOk = codec.inputFormat
                    .getInteger("color-transfer-request", 0) == MediaFormat.COLOR_TRANSFER_HLG
                codec.stop(); codec.reset()

                val f2 = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_ST2084)
                }
                codec.configure(f2, null, null, 0)
                val pqOk = codec.inputFormat
                    .getInteger("color-transfer-request", 0) == MediaFormat.COLOR_TRANSFER_ST2084
                codec.stop()

                addMiniStatus(row, "HLG透传", hlgOk)
                addMiniStatus(row, "PQ透传", pqOk)
                plainTextReport.append("      HLG透传=").append(if (hlgOk) "✅" else "❌")
                    .append(" PQ透传=").append(if (pqOk) "✅" else "❌").append("\n")
            } finally {
                codec.release()
            }
        } catch (_: Exception) {
            addBadge(card, "传递函数检测失败", COLOR_WARNING)
            plainTextReport.append("      传递函数检测失败\n")
        }
        card.addView(row)
    }

    // ========================== UI 工具方法 ==========================

    private fun createCard(icon: String, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.diag_card_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        val iconView = TextView(this).apply {
            text = icon
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dp(10f), 0)
        }
        titleRow.addView(iconView)

        val titleView = TextView(this).apply {
            text = title
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
        }
        titleRow.addView(titleView)

        card.addView(titleRow)
        return card
    }

    private fun addKeyValue(parent: LinearLayout, key: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3f) }
        }

        val k = TextView(this).apply {
            text = key
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                dp(110f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(k)

        val v = TextView(this).apply {
            text = value
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(v)

        parent.addView(row)
    }

    private fun addDivider(parent: LinearLayout) {
        val d = View(this).apply {
            setBackgroundColor(COLOR_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)
            ).apply {
                topMargin = dp(10f)
                bottomMargin = dp(10f)
            }
        }
        parent.addView(d)
    }

    private fun addSectionLabel(parent: LinearLayout, text: String) {
        val l = TextView(this).apply {
            this.text = text
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        parent.addView(l)
    }

    private fun addStatusRow(parent: LinearLayout, label: String, ok: Boolean, note: String?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3f) }
        }

        val dot = TextView(this).apply {
            text = if (ok) "●" else "○"
            setTextColor(if (ok) COLOR_SUCCESS else COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, dp(8f), 0)
        }
        row.addView(dot)

        val lbl = TextView(this).apply {
            text = if (note != null) "$label  $note" else label
            setTextColor(if (ok) COLOR_TEXT_PRIMARY else COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        row.addView(lbl)

        parent.addView(row)
    }

    private fun addHdrTypeBadge(parent: LinearLayout, name: String, ok: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3f) }
        }

        val iconTv = TextView(this).apply {
            text = if (ok) "✓" else "✗"
            setTextColor(if (ok) COLOR_SUCCESS else COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4f), 0, dp(10f), 0)
        }
        row.addView(iconTv)

        val n = TextView(this).apply {
            text = name
            setTextColor(if (ok) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        row.addView(n)

        parent.addView(row)
    }

    private fun addCompatRow(parent: LinearLayout, feature: String, ok: Boolean,
                             okMsg: String, failMsg: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3f) }
        }

        val feat = TextView(this).apply {
            text = feature
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                dp(100f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(feat)

        val st = TextView(this).apply {
            text = if (ok) "✓ $okMsg" else "⚠ $failMsg"
            setTextColor(if (ok) COLOR_SUCCESS else COLOR_WARNING)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(st)

        parent.addView(row)
        plainTextReport.append("  ").append(feature).append(": ")
            .append(if (ok) "✅ $okMsg" else "⚠️ $failMsg").append("\n")
    }

    private fun addBadge(parent: LinearLayout, msg: String, color: Int) {
        val b = TextView(this).apply {
            text = msg
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bg = when (color) {
                COLOR_SUCCESS -> R.drawable.diag_badge_success
                COLOR_WARNING -> R.drawable.diag_badge_warning
                COLOR_ERROR -> R.drawable.diag_badge_error
                else -> R.drawable.diag_badge_info
            }
            setBackgroundResource(bg)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4f)
                bottomMargin = dp(4f)
            }
        }
        parent.addView(b)
    }

    private fun createTagFlow(): ViewGroup {
        val flow = FlowLayout(this, dp(6f), dp(6f))
        flow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4f) }
        return flow
    }

    private fun addTagToFlow(flow: ViewGroup, text: String) {
        val tag = TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setBackgroundResource(R.drawable.diag_tag_background)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        }
        flow.addView(tag)
    }

    /**
     * 自动换行的流式布局
     */
    private class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else widthSize

            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                child.measure(
                    MeasureSpec.makeMeasureSpec(maxWidth - paddingLeft - paddingRight, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val cw = child.measuredWidth
                val ch = child.measuredHeight

                if (x + cw + paddingRight > maxWidth && x > paddingLeft) {
                    x = paddingLeft
                    y += rowHeight + vGap
                    rowHeight = 0
                }
                x += cw + hGap
                rowHeight = maxOf(rowHeight, ch)
            }

            val totalHeight = y + rowHeight + paddingBottom
            setMeasuredDimension(
                if (widthMode == MeasureSpec.EXACTLY) widthSize else maxWidth,
                resolveSize(totalHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = r - l
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val cw = child.measuredWidth
                val ch = child.measuredHeight

                if (x + cw + paddingRight > maxWidth && x > paddingLeft) {
                    x = paddingLeft
                    y += rowHeight + vGap
                    rowHeight = 0
                }
                child.layout(x, y, x + cw, y + ch)
                x += cw + hGap
                rowHeight = maxOf(rowHeight, ch)
            }
        }
    }

    private fun addMiniStatus(parent: LinearLayout, label: String, ok: Boolean) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12f), 0)
        }

        val dot = TextView(this).apply {
            text = if (ok) "●" else "○"
            setTextColor(if (ok) COLOR_SUCCESS else 0x44FFFFFF)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setPadding(0, 0, dp(4f), 0)
        }
        item.addView(dot)

        val txt = TextView(this).apply {
            text = label
            setTextColor(if (ok) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        item.addView(txt)

        parent.addView(item)
    }

    private fun dp(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    // ========================== Profile 工具 ==========================

    private fun getProfileName(mime: String, profile: Int): String? {
        if (mime == "video/hevc") {
            return when (profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Still"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "HDR10"
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                        "HDR10+"
                    else null
                }
            }
        } else if (mime == "video/avc") {
            return when (profile) {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
                else -> null
            }
        } else if (mime == "video/av01") {
            return when (profile) {
                1 -> "Main"
                2 -> "High"
                4 -> "Pro"
                else -> null
            }
        }
        return null
    }

    private fun isInterestingProfile(mime: String, profile: Int): Boolean {
        if (mime == "video/hevc") {
            return profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        } else if (mime == "video/avc") {
            return profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        }
        return true
    }

    companion object {
        private const val COLOR_TEXT_PRIMARY = 0xFFEEEEEE.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xAAFFFFFF.toInt()
        private const val COLOR_TEXT_MUTED = 0x66FFFFFF
        private const val COLOR_ACCENT = 0xFFFF6B9D.toInt()
        private const val COLOR_SUCCESS = 0xFF4CAF50.toInt()
        private const val COLOR_WARNING = 0xFFFF9800.toInt()
        private const val COLOR_ERROR = 0xFFE53935.toInt()
        private const val COLOR_INFO = 0xFF42A5F5.toInt()
        private const val COLOR_DIVIDER = 0x1AFFFFFF
    }
}
