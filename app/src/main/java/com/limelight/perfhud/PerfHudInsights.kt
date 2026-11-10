package com.limelight.perfhud

import java.util.Locale

/**
 * 说明卡片里与界面无关的判断：帧掉在哪一段、右上角显示什么实时值、串流体检有哪些问题。
 * 只依赖 [PerfHudSnapshot]，可以直接在 JVM 单元测试里验证。
 */
object PerfHudInsights {

    /** 帧率卡片里“帧掉在哪一段”的结论。 */
    enum class FpsVerdict {
        /** 主机或网络没送够帧。 */
        REMOTE,
        /** 帧收到了，本机解码渲染跟不上。 */
        LOCAL,
        /** 主机送来的比显示的多，多余的被跳过，画面不受影响。 */
        SURPLUS,
        OK
    }

    /** 还没收到帧时返回 null，不下结论。 */
    fun fpsVerdict(s: PerfHudSnapshot): FpsVerdict? = when {
        s.receivedFps <= 0f -> null
        s.rxSeverity != PerfSeverity.OK -> FpsVerdict.REMOTE
        s.fpsSeverity != PerfSeverity.OK -> FpsVerdict.LOCAL
        s.receivedFps > s.renderedFps * SURPLUS_RATIO -> FpsVerdict.SURPLUS
        else -> FpsVerdict.OK
    }

    /** 串流体检里需要给建议的问题，按卡片上的展示顺序排列。 */
    enum class Issue { FPS, LOSS, RTT, DECODE, BATTERY }

    fun issues(s: PerfHudSnapshot): List<Issue> = buildList {
        if (s.fpsSeverity != PerfSeverity.OK) add(Issue.FPS)
        if (s.lossSeverity != PerfSeverity.OK) add(Issue.LOSS)
        if (s.rttSeverity != PerfSeverity.OK) add(Issue.RTT)
        if (s.decodeSeverity != PerfSeverity.OK) add(Issue.DECODE)
        if (s.batterySeverity != PerfSeverity.OK) add(Issue.BATTERY)
    }

    /**
     * 卡片右上角的实时值。
     * @param color 状态色或指标分类色；卡片强调色另由 appAccent 决定。
     */
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

    /** Rx 比 Rd 多出这个比例以上，才算“主机多送了帧”。 */
    private const val SURPLUS_RATIO = 1.1f
}
