@file:Suppress("DEPRECATION")
package com.limelight.preferences

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.utils.HdrCapabilityHelper
import com.limelight.utils.UiHelper

/**
 * 编解码与屏幕能力检测页面。
 *
 * 检测逻辑生成结构化数据，Compose 只负责渲染，方便后续继续统一页面风格。
 */
class CapabilityDiagnosticActivity : ComponentActivity() {

    private lateinit var plainTextReport: StringBuilder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val systemBarColor = 0xFF16162A.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val window: Window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = systemBarColor
            window.navigationBarColor = systemBarColor
        }

        UiHelper.setLocale(this)

        plainTextReport = StringBuilder()
        val cards = generateReport()

        setContent {
            CapabilityDiagnosticScreen(
                    cards = cards,
                    onBack = { finish() },
                    onCopy = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(
                                ClipData.newPlainText("Capability Report", plainTextReport.toString()))
                        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
                    }
            )
        }

        UiHelper.notifyNewRootView(this)
    }

    private fun generateReport(): List<DiagnosticCard> {
        val cards = mutableListOf<DiagnosticCard>()
        plainTextReport.append("═══════════════════════════════════\n")
        plainTextReport.append("  设备能力检测报告\n")
        plainTextReport.append("═══════════════════════════════════\n\n")

        cards += buildDeviceCard()
        cards += buildDisplayCard()
        cards += buildHdrCard()
        cards += buildDecoderCards()

        plainTextReport.append("\n═══════════════════════════════════\n")
        plainTextReport.append("  报告生成完毕\n")
        plainTextReport.append("═══════════════════════════════════\n")
        return cards
    }

    private fun buildDeviceCard(): DiagnosticCard {
        val card = DiagnosticCard("设备", "设备信息")
        plainTextReport.append("【设备信息】\n")

        card.keyValue("品牌", Build.BRAND)
        card.keyValue("型号", Build.MODEL)
        card.keyValue("芯片", Build.HARDWARE)
        card.keyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        plainTextReport.append("  品牌: ").append(Build.BRAND).append("\n")
        plainTextReport.append("  型号: ").append(Build.MODEL).append("\n")
        plainTextReport.append("  芯片: ").append(Build.HARDWARE).append("\n")
        plainTextReport.append("  Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            card.keyValue("SOC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            plainTextReport.append("  SOC: ").append(Build.SOC_MANUFACTURER)
                    .append(" ").append(Build.SOC_MODEL).append("\n")
        }

        plainTextReport.append("\n")
        return card
    }

    @SuppressLint("NewApi")
    private fun buildDisplayCard(): DiagnosticCard {
        val card = DiagnosticCard("屏幕", "屏幕信息")
        plainTextReport.append("【屏幕信息】\n")

        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                card.badge("无法获取 Display 信息", DiagnosticTone.Error)
                plainTextReport.append("  无法获取\n\n")
                return card
            }

            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val res = "${metrics.widthPixels} × ${metrics.heightPixels}"
            card.keyValue("分辨率", res)
            card.keyValue("密度", "${metrics.densityDpi} dpi")
            plainTextReport.append("  分辨率: ").append(res).append("\n")
            plainTextReport.append("  密度: ").append(metrics.densityDpi).append(" dpi\n")

            val rr = display.refreshRate
            card.keyValue("刷新率", String.format("%.1f Hz", rr))
            plainTextReport.append("  刷新率: ").append(String.format("%.1f Hz", rr)).append("\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val modes = display.supportedModes
                if (modes.size > 1) {
                    card.divider()
                    card.section("显示模式")
                    card.tags(modes.map {
                        "${it.physicalWidth}×${it.physicalHeight} ${String.format("%.0fHz", it.refreshRate)}"
                    })
                }
            }
        } catch (e: Exception) {
            card.badge("获取失败: ${e.message}", DiagnosticTone.Error)
        }

        plainTextReport.append("\n")
        return card
    }

    @SuppressLint("NewApi")
    private fun buildHdrCard(): DiagnosticCard {
        val card = DiagnosticCard("HDR", "HDR 能力")
        plainTextReport.append("【HDR 能力】\n")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            card.badge("需要 Android 7.0+", DiagnosticTone.Warning)
            plainTextReport.append("  需要 Android 7.0+\n\n")
            return card
        }

        val capInfo = HdrCapabilityHelper.getFullCapabilityInfo(this)
        val brightness = capInfo.brightness
        val ts = capInfo.typeSupport

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            card.status("屏幕 HDR", capInfo.isScreenHdr, "isScreenHdr()")
            card.status("广色域", capInfo.isWideColorGamut)
            plainTextReport.append("  isScreenHdr: ").append(capInfo.isScreenHdr).append("\n")
            plainTextReport.append("  广色域: ").append(capInfo.isWideColorGamut).append("\n")
        }

        card.divider()
        card.section("HDR 类型")

        if (ts.rawTypes.isEmpty()) {
            card.badge("设备不支持任何 HDR 类型", DiagnosticTone.Warning)
            plainTextReport.append("  ❌ 无 HDR 类型\n")
        } else {
            card.status("Dolby Vision", ts.hasDolbyVision)
            card.status("HDR10 / PQ", ts.hasHdr10)
            card.status("HLG", ts.hasHlg)
            card.status("HDR10+", ts.hasHdr10Plus)

            plainTextReport.append("  DV: ").append(if (ts.hasDolbyVision) "✅" else "❌")
                    .append("  HDR10: ").append(if (ts.hasHdr10) "✅" else "❌")
                    .append("  HLG: ").append(if (ts.hasHlg) "✅" else "❌")
                    .append("  HDR10+: ").append(if (ts.hasHdr10Plus) "✅" else "❌").append("\n")

            card.divider()
            card.section("串流兼容性")
            card.compat("HLG 直通", ts.hasHlg, "设备支持", "设备未声明")
            card.compat("HDR10/PQ 直通", ts.hasHdr10, "设备支持", "设备未声明")
        }

        card.divider()
        card.section("屏幕亮度")

        var maxDesc = String.format("%.0f nits", brightness.maxLuminance)
        if (brightness.isDefault) maxDesc += " (默认)"
        else if (brightness.isFromHdrCaps) maxDesc += " (EDID)"
        card.keyValue("峰值亮度", maxDesc)
        card.keyValue("最小亮度", String.format("%.4f nits", brightness.minLuminance))
        card.keyValue("平均亮度", String.format("%.0f nits", brightness.maxAvgLuminance))

        plainTextReport.append("  峰值: ").append(maxDesc).append("\n")
        plainTextReport.append("  最小: ").append(String.format("%.4f", brightness.minLuminance)).append("\n")
        plainTextReport.append("  平均: ").append(String.format("%.0f", brightness.maxAvgLuminance)).append("\n")

        when {
            brightness.isDefault -> card.badge("驱动未报告 EDID 亮度", DiagnosticTone.Warning)
            brightness.maxLuminance >= 1000 -> card.badge("高阶 HDR 面板 ≥1000 nits", DiagnosticTone.Success)
            brightness.maxLuminance >= 600 -> card.badge("中等 HDR 面板", DiagnosticTone.Success)
            brightness.maxLuminance < 400 -> card.badge("峰值亮度偏低 <400 nits", DiagnosticTone.Warning)
        }

        card.divider()
        card.section("HDR/SDR 动态比率")

        if (Build.VERSION.SDK_INT < 34) {
            card.badge("需要 Android 14+ (API 34)", DiagnosticTone.Muted)
            plainTextReport.append("  HDR/SDR Ratio: 需要 API 34+\n")
        } else if (!brightness.isHdrSdrRatioAvailable) {
            card.badge("设备不支持 ratio 查询", DiagnosticTone.Warning)
            plainTextReport.append("  HDR/SDR Ratio: 不支持\n")
        } else {
            card.keyValue("当前比率", String.format("%.2f×", brightness.hdrSdrRatio))
            card.keyValue(
                    "最高比率",
                    String.format("%.2f×", brightness.highestHdrSdrRatio) +
                            if (Build.VERSION.SDK_INT >= 36) "" else " (≈当前)"
            )
            plainTextReport.append("  当前 ratio: ").append(String.format("%.2f", brightness.hdrSdrRatio)).append("\n")
            plainTextReport.append("  最高 ratio: ").append(String.format("%.2f", brightness.highestHdrSdrRatio)).append("\n")

            if (brightness.isComputedFromRatio && brightness.computedPeakBrightness > 0) {
                card.keyValue("Ratio 峰值", String.format("%.0f nits", brightness.computedPeakBrightness))
                plainTextReport.append("  Ratio 峰值: ").append(
                        String.format("%.0f", brightness.computedPeakBrightness)).append("\n")
            }
            card.badge("Android 未公开 sdrNits，精度受限", DiagnosticTone.Info)
        }

        card.divider()
        card.section("上报服务端")

        val sv = HdrCapabilityHelper.getBrightnessRangeAsInts(this)
        card.keyValue("minBrightness", "${sv[0]} nits")
        card.keyValue("maxBrightness", "${sv[1]} nits")
        card.keyValue("maxAvgBrightness", "${sv[2]} nits")
        plainTextReport.append("  上报: min=").append(sv[0]).append(" max=").append(sv[1])
                .append(" avg=").append(sv[2]).append("\n")

        card.divider()
        card.section("系统亮度")

        val sysBr = HdrCapabilityHelper.getSystemBrightness(this)
        if (sysBr >= 0) {
            card.keyValue("当前亮度", "$sysBr/255 (${String.format("%.0f%%", sysBr / 255f * 100)})")
            plainTextReport.append("  系统亮度: ").append(sysBr).append("/255\n")
        }
        val autoBr = HdrCapabilityHelper.isAutoBrightnessEnabled(this)
        card.keyValue("自动亮度", if (autoBr) "开启" else "关闭")
        plainTextReport.append("  自动亮度: ").append(autoBr).append("\n\n")

        return card
    }

    @SuppressLint("NewApi")
    private fun buildDecoderCards(): List<DiagnosticCard> {
        plainTextReport.append("【视频解码器】\n")

        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
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

        return listOf(
                buildOneCodecCard("HEVC", "HEVC (H.265)", hevc, "video/hevc"),
                buildOneCodecCard("AVC", "AVC (H.264)", avc, "video/avc"),
                buildOneCodecCard("AV1", "AV1", av1, "video/av01")
        )
    }

    @SuppressLint("NewApi")
    private fun buildOneCodecCard(
            icon: String,
            codecName: String,
            decoders: List<MediaCodecInfo>,
            mime: String
    ): DiagnosticCard {
        val card = DiagnosticCard(icon, "$codecName  (${decoders.size})")
        plainTextReport.append("\n  ").append(codecName).append(" (").append(decoders.size).append("):\n")

        if (decoders.isEmpty()) {
            card.badge("无可用解码器", DiagnosticTone.Error)
            plainTextReport.append("    ❌ 无\n")
            return card
        }

        decoders.forEachIndexed { index, info ->
            if (index > 0) card.divider()

            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                val n = info.name.lowercase()
                !n.contains("omx.google") && !n.contains("c2.android")
            }

            card.decoderName(info.name, isHw)
            plainTextReport.append("    ").append(info.name)
                    .append(if (isHw) " [硬件]" else " [软件]").append("\n")

            try {
                val caps = info.getCapabilitiesForType(mime)
                var main10 = false
                var hdr10 = false
                var hdr10p = false
                val tags = mutableListOf<String>()

                for (pl in caps.profileLevels) {
                    val pn = getProfileName(mime, pl.profile)
                    if (pn != null && isInterestingProfile(mime, pl.profile)) {
                        tags += pn
                        plainTextReport.append("      ").append(pn).append("\n")
                    }
                    if (mime == "video/hevc") {
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) hdr10 = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) hdr10p = true
                    }
                }
                if (tags.isNotEmpty()) card.tags(tags)

                if (mime == "video/hevc") {
                    card.miniStatus(
                            listOf(
                                    MiniStatus("10bit", main10),
                                    MiniStatus("HDR10", hdr10),
                                    MiniStatus("HDR10+", hdr10p),
                                    MiniStatus("HLG", main10)
                            )
                    )
                    plainTextReport.append("      10bit=").append(if (main10) "✅" else "❌")
                            .append(" HDR10=").append(if (hdr10) "✅" else "❌")
                            .append(" HDR10+=").append(if (hdr10p) "✅" else "❌")
                            .append(" HLG=").append(if (main10) "✅" else "❌").append("\n")
                }

                var p010 = false
                for (cf in caps.colorFormats) {
                    if (cf == 54) p010 = true
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        val mw = vc.supportedWidths.upper
                        val mh = vc.supportedHeights.upper
                        val is4k = try {
                            vc.isSizeSupported(3840, 2160)
                        } catch (_: Exception) {
                            false
                        }

                        val items = mutableListOf(MiniStatus("4K", is4k))
                        if (mime == "video/hevc" || mime == "video/av01") {
                            items += MiniStatus("P010", p010)
                        }
                        card.miniStatus(items, "Max ${mw}×${mh}")

                        plainTextReport.append("      4K=").append(if (is4k) "✅" else "❌")
                                .append(" P010=").append(if (p010) "✅" else "❌")
                                .append(" Max=").append(mw).append("×").append(mh).append("\n")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        mime == "video/hevc" && isHw && main10) {
                    buildTransferRequestCheck(card, info, mime)
                }
            } catch (e: Exception) {
                card.badge("能力查询失败", DiagnosticTone.Error)
                plainTextReport.append("      失败: ").append(e.message).append("\n")
            }
        }

        return card
    }

    @SuppressLint("NewApi")
    private fun buildTransferRequestCheck(card: DiagnosticCard, info: MediaCodecInfo, mime: String) {
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
                codec.stop()
                codec.reset()

                val f2 = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_ST2084)
                }
                codec.configure(f2, null, null, 0)
                val pqOk = codec.inputFormat
                        .getInteger("color-transfer-request", 0) == MediaFormat.COLOR_TRANSFER_ST2084
                codec.stop()

                card.miniStatus(listOf(MiniStatus("HLG透传", hlgOk), MiniStatus("PQ透传", pqOk)))
                plainTextReport.append("      HLG透传=").append(if (hlgOk) "✅" else "❌")
                        .append(" PQ透传=").append(if (pqOk) "✅" else "❌").append("\n")
            } finally {
                codec.release()
            }
        } catch (_: Exception) {
            card.badge("传递函数检测失败", DiagnosticTone.Warning)
            plainTextReport.append("      传递函数检测失败\n")
        }
    }

    private fun getProfileName(mime: String, profile: Int): String? {
        if (mime == "video/hevc") {
            return when (profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Still"
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "HDR10"
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) {
                        "HDR10+"
                    } else {
                        null
                    }
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
            return profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        } else if (mime == "video/avc") {
            return profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline ||
                    profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain ||
                    profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ||
                    profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        }
        return true
    }

    @Composable
    private fun CapabilityDiagnosticScreen(
            cards: List<DiagnosticCard>,
            onBack: () -> Unit,
            onCopy: () -> Unit
    ) {
        val background = Color(0xFF16162A)
        val panel = Color(0xE6101020)
        val primary = Color(0xFFEEEEEE)
        val secondary = Color(0xAAFFFFFF)
        val accent = colorResource(R.color.crown_accent)

        MaterialTheme(
                colorScheme = darkColorScheme(
                        primary = accent,
                        surface = Color(0xFF202033),
                        onSurface = primary,
                        onSurfaceVariant = secondary
                )
        ) {
            Box(
                    modifier = Modifier
                            .fillMaxSize()
                            .background(background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    DiagnosticTopBar(
                            panel = panel,
                            primary = primary,
                            secondary = secondary,
                            accent = accent,
                            onBack = onBack,
                            onCopy = onCopy
                    )

                    LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 80.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(cards) { card ->
                            DiagnosticCardView(card)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DiagnosticTopBar(
            panel: Color,
            primary: Color,
            secondary: Color,
            accent: Color,
            onBack: () -> Unit,
            onCopy: () -> Unit
    ) {
        Surface(
                color = panel,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp
        ) {
            Box(
                    modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                IconButton(
                        onClick = onBack,
                        modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(40.dp)
                ) {
                    Icon(
                            painter = painterResource(R.drawable.ic_arrow_right),
                            contentDescription = "Back",
                            tint = primary,
                            modifier = Modifier
                                    .size(20.dp)
                                    .rotate(180f)
                    )
                }

                Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                            text = stringResource(R.string.layout_capability_diagnostic_text_a5e80),
                            color = primary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                    )
                    Text(
                            text = stringResource(R.string.layout_capability_diagnostic_text_41b60),
                            color = secondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 1.dp)
                    )
                }

                TextButton(
                        onClick = onCopy,
                        colors = ButtonDefaults.textButtonColors(
                                containerColor = accent.copy(alpha = 0.18f),
                                contentColor = primary
                        ),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text(
                            text = stringResource(R.string.layout_capability_diagnostic_text_79d3a),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    private fun DiagnosticCardView(card: DiagnosticCard) {
        Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF242436)),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(
                            text = card.icon,
                            color = colorResource(R.color.crown_accent),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colorResource(R.color.crown_accent).copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                            text = card.title,
                            color = Color(0xFFEEEEEE),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                    )
                }

                card.rows.forEach { row ->
                    DiagnosticRowView(row)
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DiagnosticRowView(row: DiagnosticRow) {
        when (row) {
            is DiagnosticRow.KeyValue -> {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                ) {
                    Text(
                            text = row.key,
                            color = Color(0xAAFFFFFF),
                            fontSize = 13.sp,
                            modifier = Modifier.width(110.dp)
                    )
                    Text(
                            text = row.value,
                            color = Color(0xFFEEEEEE),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                    )
                }
            }

            is DiagnosticRow.Badge -> {
                Text(
                        text = row.message,
                        color = row.tone.foreground,
                        fontSize = 12.sp,
                        modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(row.tone.background)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            DiagnosticRow.Divider -> {
                Box(
                        modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0x1AFFFFFF))
                )
            }

            is DiagnosticRow.Section -> {
                Text(
                        text = row.title,
                        color = colorResource(R.color.crown_accent),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                )
            }

            is DiagnosticRow.Status -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(row.ok)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                            text = if (row.note != null) "${row.label}  ${row.note}" else row.label,
                            color = if (row.ok) Color(0xFFEEEEEE) else Color(0xAAFFFFFF),
                            fontSize = 13.sp
                    )
                }
            }

            is DiagnosticRow.Compat -> {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                ) {
                    Text(
                            text = row.feature,
                            color = Color(0xAAFFFFFF),
                            fontSize = 12.sp,
                            modifier = Modifier.width(100.dp)
                    )
                    Text(
                            text = if (row.ok) "✓ ${row.okMessage}" else "⚠ ${row.failMessage}",
                            color = if (row.ok) DiagnosticTone.Success.foreground else DiagnosticTone.Warning.foreground,
                            fontSize = 12.sp
                    )
                }
            }

            is DiagnosticRow.Tags -> {
                FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.tags.forEach { tag ->
                        Text(
                                text = tag,
                                color = Color(0xAAFFFFFF),
                                fontSize = 10.sp,
                                modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color(0x14FFFFFF))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            is DiagnosticRow.DecoderName -> {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = row.name,
                            color = Color(0xFFEEEEEE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                    )
                    Text(
                            text = if (row.hardware) "硬件" else "软件",
                            color = if (row.hardware) DiagnosticTone.Success.foreground else Color(0xAAFFFFFF),
                            fontSize = 11.sp,
                            modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0x14FFFFFF))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            is DiagnosticRow.MiniStatusList -> {
                Row(
                        modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(item.ok, small = true)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                    text = item.label,
                                    color = if (item.ok) Color(0xFFEEEEEE) else Color(0x66FFFFFF),
                                    fontSize = 11.sp
                            )
                        }
                    }
                    row.trailingText?.let {
                        Text(text = it, color = Color(0x66FFFFFF), fontSize = 10.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusDot(ok: Boolean, small: Boolean = false) {
        Box(
                modifier = Modifier
                        .size(if (small) 7.dp else 9.dp)
                        .clip(CircleShape)
                        .background(if (ok) DiagnosticTone.Success.foreground else Color(0x44FFFFFF))
        )
    }

    private data class DiagnosticCard(
            val icon: String,
            val title: String,
            val rows: MutableList<DiagnosticRow> = mutableListOf()
    ) {
        fun keyValue(key: String, value: String) {
            rows += DiagnosticRow.KeyValue(key, value)
        }

        fun badge(message: String, tone: DiagnosticTone) {
            rows += DiagnosticRow.Badge(message, tone)
        }

        fun divider() {
            rows += DiagnosticRow.Divider
        }

        fun section(title: String) {
            rows += DiagnosticRow.Section(title)
        }

        fun status(label: String, ok: Boolean, note: String? = null) {
            rows += DiagnosticRow.Status(label, ok, note)
        }

        fun compat(feature: String, ok: Boolean, okMessage: String, failMessage: String) {
            rows += DiagnosticRow.Compat(feature, ok, okMessage, failMessage)
        }

        fun tags(tags: List<String>) {
            rows += DiagnosticRow.Tags(tags)
        }

        fun decoderName(name: String, hardware: Boolean) {
            rows += DiagnosticRow.DecoderName(name, hardware)
        }

        fun miniStatus(items: List<MiniStatus>, trailingText: String? = null) {
            rows += DiagnosticRow.MiniStatusList(items, trailingText)
        }
    }

    private sealed class DiagnosticRow {
        data class KeyValue(val key: String, val value: String) : DiagnosticRow()
        data class Badge(val message: String, val tone: DiagnosticTone) : DiagnosticRow()
        data object Divider : DiagnosticRow()
        data class Section(val title: String) : DiagnosticRow()
        data class Status(val label: String, val ok: Boolean, val note: String? = null) : DiagnosticRow()
        data class Compat(
                val feature: String,
                val ok: Boolean,
                val okMessage: String,
                val failMessage: String
        ) : DiagnosticRow()
        data class Tags(val tags: List<String>) : DiagnosticRow()
        data class DecoderName(val name: String, val hardware: Boolean) : DiagnosticRow()
        data class MiniStatusList(val items: List<MiniStatus>, val trailingText: String? = null) : DiagnosticRow()
    }

    private data class MiniStatus(val label: String, val ok: Boolean)

    private enum class DiagnosticTone(
            val foreground: Color,
            val background: Color
    ) {
        Success(Color(0xFF4CAF50), Color(0x1A4CAF50)),
        Warning(Color(0xFFFF9800), Color(0x1AFF9800)),
        Error(Color(0xFFE53935), Color(0x1AE53935)),
        Info(Color(0xFF42A5F5), Color(0x1A42A5F5)),
        Muted(Color(0x66FFFFFF), Color(0x10FFFFFF))
    }
}
