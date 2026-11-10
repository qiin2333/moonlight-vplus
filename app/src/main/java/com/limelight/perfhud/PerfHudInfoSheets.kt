package com.limelight.perfhud

import android.app.Activity
import android.view.View
import com.limelight.R
import com.limelight.utils.MoonPhaseUtils
import com.limelight.utils.UiHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 指标说明卡片，HUD 长按和经典样式点击都走这里。
 * 分辨率 / 解码器 / 电量依赖 Manager 状态，由 Manager 拼好正文后调 [showText]。
 */
class PerfHudInfoSheets(
    private val activity: Activity,
    private val snapshot: () -> PerfHudSnapshot?,
    private val isHudStyle: () -> Boolean,
    private val anchor: () -> View?,
    private val moodText: (PerfMood) -> String,
    private val fpsHeader: () -> View
) {
    private val sheet by lazy { PerfHudSheet(activity) }

    fun dismiss() = sheet.dismiss()

    /** 按长按命中的指标弹出对应卡片；分辨率 / 解码器 / 电量由 Manager 处理，返回 false。 */
    fun showFor(target: PerfHudTarget): Boolean {
        when (target) {
            PerfHudTarget.MOON, PerfHudTarget.CLOCK -> showMoonPhase()
            PerfHudTarget.FPS -> showFps()
            PerfHudTarget.LATENCY -> showLatencyChain()
            PerfHudTarget.DIAGNOSIS -> showDiagnosis()
            PerfHudTarget.ONE_PERCENT_LOW, PerfHudTarget.NETWORK, PerfHudTarget.DECODE,
            PerfHudTarget.HOST, PerfHudTarget.LOSS -> showMetric(target)
            PerfHudTarget.RESOLUTION, PerfHudTarget.DECODER, PerfHudTarget.BATTERY -> return false
        }
        return true
    }

    fun showText(title: String, message: String, target: PerfHudTarget? = null) {
        show(title, PerfHudSheetText.parse(message), target)
    }

    /** 标题开头的 emoji 自动拆成光圈图标。 */
    fun show(
        title: String,
        blocks: List<SheetBlock>,
        target: PerfHudTarget?,
        icon: String? = null,
        subtitle: String? = null,
        header: List<View> = emptyList(),
        extraActions: List<PerfHudSheet.Action> = emptyList()
    ) {
        val (titleIcon, name) = PerfHudSheetText.splitIcon(title)
        val live = PerfHudInsights.liveValue(target, snapshot(), Locale.getDefault())
        sheet.show(
            PerfHudSheet.Spec(
                title = name,
                icon = icon ?: titleIcon,
                accent = UiHelper.accentColor(activity),
                value = live.text,
                valueColor = live.color,
                subtitle = subtitle,
                blocks = blocks,
                header = header,
                actions = extraActions + PerfHudSheet.Action(activity.getString(R.string.yes), primary = true)
            ),
            anchor()
        )
    }

    fun showMoonPhase() {
        val info = MoonPhaseUtils.getCurrentMoonPhaseInfo(activity)
        val phase = MoonPhaseUtils.getCurrentMoonPhase()
        val dateFormat = SimpleDateFormat(activity.getString(R.string.perf_moon_date_format), Locale.getDefault())
        val currentDate = dateFormat.format(Calendar.getInstance(TimeZone.getDefault()).time)
        val text = String.format(
            Locale.getDefault(),
            activity.getString(R.string.perf_moon_phase_info),
            info.icon, info.name, MoonPhaseUtils.getMoonPhasePercentage(phase),
            MoonPhaseUtils.getDaysInMoonCycle(phase), currentDate, info.description
        )
        show(info.poeticTitle, PerfHudSheetText.parse(text), PerfHudTarget.MOON, icon = info.icon)
    }

    fun showFps() {
        val blocks = if (isHudStyle()) {
            fpsLiveBlocks() + PerfHudSheetText.parse(activity.getString(R.string.perf_hud_fps_info))
        } else {
            PerfHudSheetText.parse(activity.getString(R.string.perf_fps_info))
        }
        show(activity.getString(R.string.perf_fps_title), blocks, PerfHudTarget.FPS, header = listOf(fpsHeader()))
    }

    /** HUD 与经典样式的阈值不同，说明文案各用各的。 */
    fun showMetric(target: PerfHudTarget) {
        val (title, classic, hud) = METRIC_TEXT[target] ?: return
        show(activity.getString(title), PerfHudSheetText.parse(activity.getString(if (isHudStyle()) hud else classic)), target)
    }

    fun showLatencyChain() {
        val chain = snapshot()?.latency ?: return showMoonPhase()
        val locale = Locale.getDefault()
        fun ms(v: Float) = ms1(v, locale)
        val host = chain.hostMs?.let(::ms) ?: activity.getString(R.string.perf_latency_chain_no_host)
        val hot = chain.decodeMs >= PerfHudThresholds.HOT_DECODE_MS
        val content = activity.getString(
            R.string.perf_latency_chain_content,
            ms(chain.networkMs), host, ms(chain.decodeMs) + if (hot) " 🥵" else "", ms(chain.renderMs), ms(chain.totalMs)
        )
        // 前四行依次是网络 / 主机 / 解码 / 渲染，配上与 HUD 分段条一致的色块
        val segments = chain.segments()
        val swatches = ArrayDeque(segments.map { it.color })
        val blocks = PerfHudSheetText.parse(content).map { b ->
            if (b is SheetBlock.Row && swatches.isNotEmpty()) b.copy(swatch = swatches.removeFirst()) else b
        }
        show(
            activity.getString(R.string.perf_latency_chain_title), blocks, PerfHudTarget.LATENCY,
            header = listOf(PerfHudSheet.ChainBarView(activity, segments))
        )
    }

    fun showDiagnosis() {
        val s = snapshot() ?: return showMoonPhase()
        val locale = Locale.getDefault()
        fun line(text: String, severity: PerfSeverity): SheetBlock =
            PerfHudSheetText.row(text, valueColor = PerfHudPalette.severity(severity)) ?: SheetBlock.Paragraph(text)

        val blocks = ArrayList<SheetBlock>()
        blocks += line(activity.getString(R.string.perf_diag_line_fps, fps0(s.renderedFps, locale), s.targetFps), s.fpsSeverity)
        blocks += line(activity.getString(
            R.string.perf_diag_line_loss, String.format(locale, "%.2f%%", s.packetLossPercent)
        ), s.lossSeverity)
        blocks += line(activity.getString(R.string.perf_diag_line_rtt, s.rttMs, s.rttVarianceMs), s.rttSeverity)
        blocks += line(activity.getString(
            R.string.perf_diag_line_decode, ms1(s.latency.decodeMs, locale) + if (s.isDecodeHot) " 🥵" else ""
        ), s.decodeSeverity)
        if (s.batteryLevel >= 0) {
            blocks += line(activity.getString(R.string.perf_diag_line_battery, s.batteryLevel), s.batterySeverity)
        }

        PerfHudInsights.diagnosisTips(s).forEach {
            blocks += SheetBlock.Gap
            blocks += SheetBlock.Paragraph(activity.getString(it))
        }
        blocks += SheetBlock.Tip(activity.getString(R.string.perf_diag_footer))

        show(
            activity.getString(R.string.perf_diag_title),
            blocks,
            PerfHudTarget.DIAGNOSIS,
            icon = s.moonIcon,
            subtitle = moodText(s.mood),
            extraActions = listOf(
                PerfHudSheet.Action(activity.getString(R.string.perf_diag_moon_button, s.moonIcon)) { showMoonPhase() }
            )
        )
    }

    /** Rx / Rd / FG 实时表和帧掉在哪一段的判断。 */
    private fun fpsLiveBlocks(): List<SheetBlock> {
        val s = snapshot() ?: return emptyList()
        val locale = Locale.getDefault()
        fun fps(v: Float) = if (v > 0f) fps0(v, locale) + " FPS" else "—"
        val out = arrayListOf<SheetBlock>(
            SheetBlock.Row(activity.getString(R.string.perf_hud_fps_row_rx), fps(s.receivedFps),
                PerfHudPalette.severity(s.rxSeverity), PerfHudPalette.NET),
            SheetBlock.Row(activity.getString(R.string.perf_hud_fps_row_rd), fps(s.renderedFps),
                PerfHudPalette.severity(s.fpsSeverity), PerfHudPalette.RND)
        )
        if (s.framegenFps > 0.5f) {
            out += SheetBlock.Row(activity.getString(R.string.perf_hud_fps_row_fg), fps(s.framegenFps),
                PerfHudPalette.TXT, PerfHudPalette.MOON)
        }
        PerfHudInsights.fpsVerdict(s)?.let { out += SheetBlock.Paragraph(activity.getString(it)) }
        out += SheetBlock.Divider
        return out
    }

    private companion object {
        /** 标题 / 经典样式说明 / HUD 样式说明。 */
        val METRIC_TEXT = mapOf(
            PerfHudTarget.LOSS to Triple(
                R.string.perf_packet_loss_title, R.string.perf_packet_loss_info, R.string.perf_hud_packet_loss_info),
            PerfHudTarget.NETWORK to Triple(
                R.string.perf_network_latency_title, R.string.perf_network_latency_info, R.string.perf_hud_network_latency_info),
            PerfHudTarget.DECODE to Triple(
                R.string.perf_decode_latency_title, R.string.perf_decode_latency_info, R.string.perf_hud_decode_latency_info),
            PerfHudTarget.HOST to Triple(
                R.string.perf_host_latency_title, R.string.perf_host_latency_info, R.string.perf_hud_host_latency_info),
            PerfHudTarget.ONE_PERCENT_LOW to Triple(
                R.string.perf_one_percent_low_title, R.string.perf_one_percent_low_info, R.string.perf_hud_one_percent_low_info)
        )
    }
}
