package com.limelight.perfhud

import androidx.annotation.StringRes
import com.limelight.R
import java.util.Locale

/** 说明卡片里与界面无关的判断，只依赖 [PerfHudSnapshot]，可直接做 JVM 单元测试。 */
object PerfHudInsights {

    /** 帧掉在哪一段的结论文案；还没收到帧时为 null。 */
    @StringRes
    fun fpsVerdict(s: PerfHudSnapshot): Int? = when {
        s.receivedFps <= 0f -> null
        s.rxSeverity != PerfSeverity.OK -> R.string.perf_hud_fps_verdict_remote
        s.fpsSeverity != PerfSeverity.OK -> R.string.perf_hud_fps_verdict_local
        // Rx 比 Rd 多出一成以上：主机多送的帧被跳过，画面不受影响
        s.receivedFps > s.renderedFps * 1.1f -> R.string.perf_hud_fps_verdict_surplus
        else -> R.string.perf_hud_fps_verdict_ok
    }

    /** 串流体检的建议，按卡片展示顺序；全部正常时只有一句夸奖。 */
    fun diagnosisTips(s: PerfHudSnapshot): List<Int> = buildList {
        if (s.fpsSeverity != PerfSeverity.OK) add(R.string.perf_diag_tip_fps)
        if (s.lossSeverity != PerfSeverity.OK) add(R.string.perf_diag_tip_loss)
        if (s.rttSeverity != PerfSeverity.OK) add(R.string.perf_diag_tip_rtt)
        if (s.decodeSeverity != PerfSeverity.OK) add(R.string.perf_diag_tip_decode)
        if (s.batterySeverity != PerfSeverity.OK) add(R.string.perf_diag_tip_battery)
        if (isEmpty()) add(R.string.perf_diag_all_good)
    }

    /** 卡片右上角的实时值；颜色是状态色或分类色，卡片强调色另由 appAccent 决定。 */
    data class LiveValue(val color: Int, val text: String? = null)

    fun liveValue(target: PerfHudTarget?, s: PerfHudSnapshot?, locale: Locale): LiveValue {
        if (target == null || s == null) return LiveValue(PerfHudPalette.MOON)
        fun sev(x: PerfSeverity) = PerfHudPalette.severity(x)
        fun fps(v: Float) = fps0(v, locale) + " FPS"
        return when (target) {
            PerfHudTarget.FPS -> LiveValue(sev(s.fpsSeverity), fps(s.renderedFps))
            PerfHudTarget.ONE_PERCENT_LOW ->
                LiveValue(sev(s.onePercentLowSeverity), s.onePercentLowFps.takeIf { it > 0f }?.let(::fps))
            PerfHudTarget.LATENCY -> LiveValue(PerfHudPalette.RND, "≈" + fps0(s.latency.totalMs, locale) + " ms")
            PerfHudTarget.NETWORK -> LiveValue(sev(s.rttSeverity), "${s.rttMs} ms")
            PerfHudTarget.DECODE -> LiveValue(sev(s.decodeSeverity), ms1(s.latency.decodeMs, locale))
            PerfHudTarget.HOST -> LiveValue(PerfHudPalette.HOST, s.latency.hostMs?.let { ms1(it, locale) })
            PerfHudTarget.LOSS ->
                LiveValue(sev(s.lossSeverity), String.format(locale, "%.2f%%", s.packetLossPercent))
            PerfHudTarget.BATTERY -> LiveValue(sev(s.batterySeverity), s.batteryLevel.takeIf { it >= 0 }?.let { "$it%" })
            PerfHudTarget.RESOLUTION -> LiveValue(PerfHudPalette.MOON, if (s.width > 0) "${s.width}×${s.height}" else null)
            PerfHudTarget.DECODER -> LiveValue(PerfHudPalette.DEC, s.codec.ifBlank { null })
            PerfHudTarget.DIAGNOSIS -> LiveValue(sev(s.overallSeverity))
            PerfHudTarget.MOON, PerfHudTarget.CLOCK -> LiveValue(PerfHudPalette.MOON)
        }
    }
}
