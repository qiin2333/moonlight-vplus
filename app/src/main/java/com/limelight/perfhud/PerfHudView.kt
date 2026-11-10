package com.limelight.perfhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** HUD 上出现的所有文案，由 Manager 从资源里填充；默认值仅用于预览和测试。 */
data class PerfHudStrings(
    val fps: String = "FPS",
    val onePercentLow: String = "1%L",
    val latency: String = "Latency",
    val latencyChain: String = "Latency chain (est.)",
    val network: String = "Net",
    val host: String = "Host",
    val decode: String = "Decode",
    val render: String = "Render",
    val loss: String = "Loss",
    val rtt: String = "RTT",
    val bandwidth: String = "BW",
    val battery: String = "Battery",
    val decoder: String = "Decoder",
    val resolution: String = "Res",
    val clock: String = "Time",
    val fpsHistory: String = "FPS history",
    val drainPerHour: String = "%.0f%%/h",
    val moods: Map<PerfMood, String> = mapOf(
        PerfMood.WARMING_UP to "🌙 Warming up…",
        PerfMood.SMOOTH to "Smooth (๑•̀ㅂ•́)و✧",
        PerfMood.FULL_MOON_SMOOTH to "Full moon, full frames",
        PerfMood.HOT_DECODE to "🥵 Decoder is burning",
        PerfMood.LOSSY to "📶 Dropping packets, meow~",
        PerfMood.FPS_DROP to "Frames are dropping, meow~",
        PerfMood.LAGGY_NETWORK to "🐢 Network is laggy",
        PerfMood.LOW_BATTERY to "🔋 Low battery, feed me a charger"
    )
) {
    fun mood(m: PerfMood): String = moods[m] ?: ""
}

/**
 * 新版性能 HUD：单个 Canvas 自绘 View，替代逐行 TextView。
 *
 * 测量和绘制共用同一个 [render]（canvas 为 null 时只测量），两者不会出现尺寸漂移；
 * 绘制时顺带记录每个指标的命中区域，供长按时 [targetAt] 反查。
 */
class PerfHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var hudLayout: PerfHudLayout = PerfHudLayout.BAR
        private set
    var detailed: Boolean = false
        private set

    private var snapshot: PerfHudSnapshot = EMPTY
    private var visibility = PerfHudVisibility()
    private var strings = PerfHudStrings()
    private var bgAlpha = 0xB8
    private val fpsHistory = FloatRing(HISTORY_SIZE)

    private val dp = resources.displayMetrics.density
    private var em = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)

    private val valuePaint = newTextPaint(bold = true)
    private val labelPaint = newTextPaint(bold = false)
    private val bigPaint = newTextPaint(bold = true)
    private val tilePaint = newTextPaint(bold = true)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sparkPath = Path()
    private val rect = RectF()

    private class Hit(val rect: RectF, val target: PerfHudTarget)
    private val hits = ArrayList<Hit>()

    private var contentW = 0f
    private var contentH = 0f

    init {
        applyTextSize()
        isClickable = false
        isFocusable = false
    }

    fun setHudLayout(layout: PerfHudLayout, detailed: Boolean) {
        if (layout == hudLayout && detailed == this.detailed) return
        hudLayout = layout
        this.detailed = detailed
        requestLayout()
        invalidate()
    }

    fun setSnapshot(snapshot: PerfHudSnapshot) {
        this.snapshot = snapshot
        if (snapshot.renderedFps > 0f) fpsHistory.add(snapshot.renderedFps)
        // 每秒刷新一次：尺寸不变时只重绘，避免整个串流界面跟着重新布局
        if (width > 0 && height > 0) {
            render(null, width.toFloat())
            val sameWidth = hudLayout == PerfHudLayout.DOCK || ceil(contentW).toInt() == width
            if (sameWidth && ceil(contentH).toInt() == height) {
                invalidate()
                return
            }
        }
        requestLayout()
        invalidate()
    }

    fun setItemVisibility(visibility: PerfHudVisibility) {
        if (visibility == this.visibility) return
        this.visibility = visibility
        requestLayout()
        invalidate()
    }

    fun setStrings(strings: PerfHudStrings) {
        this.strings = strings
        requestLayout()
        invalidate()
    }

    /** @param alpha 0..1，对应设置里的“背景透明度”。 */
    fun setBackgroundOpacity(alpha: Float) {
        bgAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        invalidate()
    }

    /** 顶栏贴着屏幕上沿时只圆下面两个角，背景向下渐淡。 */
    var stickTop: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var pressX = Float.NaN
    private var pressY = Float.NaN

    /** 按住 HUD 时把手指下的指标高亮出来，提示长按会打开它的说明。坐标为本 View 坐标。 */
    fun setPressedAt(x: Float, y: Float) {
        pressX = x
        pressY = y
        invalidate()
    }

    fun clearPressed() {
        if (pressX.isNaN()) return
        pressX = Float.NaN
        pressY = Float.NaN
        invalidate()
    }

    fun setBaseTextSizePx(px: Float) {
        if (px <= 0f || px == em) return
        em = px
        applyTextSize()
        requestLayout()
        invalidate()
    }

    fun clearHistory() {
        fpsHistory.clear()
        invalidate()
    }

    /** 反查长按位置对应的指标；落在 HUD 内但不在任何指标上时视为“串流体检”。 */
    fun targetAt(x: Float, y: Float): PerfHudTarget? {
        if (x < 0 || y < 0 || x > width || y > height) return null
        for (i in hits.indices.reversed()) {
            if (hits[i].rect.contains(x, y)) return hits[i].target
        }
        return PerfHudTarget.DIAGNOSIS
    }

    /** 预先测量某种布局在给定宽度下的高度（用于判断竖屏黑边能否放下停靠卡片），不改变当前状态。 */
    fun measureHeightFor(layout: PerfHudLayout, detailed: Boolean, widthPx: Int): Int {
        val savedLayout = hudLayout
        val savedDetailed = this.detailed
        hudLayout = layout
        this.detailed = detailed
        render(null, widthPx.toFloat())
        val h = ceil(contentH).toInt()
        hudLayout = savedLayout
        this.detailed = savedDetailed
        // 恢复当前布局的尺寸和命中区域
        render(null, if (width > 0) width.toFloat() else UNBOUNDED)
        return h
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) UNBOUNDED else size.toFloat()
        render(null, maxW)
        val w = if (mode == MeasureSpec.EXACTLY) size else ceil(contentW).toInt()
        setMeasuredDimension(w, ceil(contentH).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        render(canvas, width.toFloat())
        drawPressHighlight(canvas)
    }

    private fun drawPressHighlight(c: Canvas) {
        if (pressX.isNaN()) return
        val h = hits.lastOrNull { it.rect.contains(pressX, pressY) } ?: return
        rect.set(h.rect)
        rect.intersect(dp, dp, width - dp, height - dp)
        val r = em * 0.45f
        fillPaint.color = PerfHudPalette.withAlpha(TXT, 0x22)
        c.drawRoundRect(rect, r, r, fillPaint)
        strokePaint.color = PerfHudPalette.withAlpha(TXT, 0x55)
        strokePaint.strokeWidth = dp
        c.drawRoundRect(rect, r, r, strokePaint)
    }

    // ---------------------------------------------------------------- render

    private fun render(c: Canvas?, maxW: Float) {
        hits.clear()
        if (c != null) drawBackground(c)
        when (hudLayout) {
            PerfHudLayout.CAPSULE -> renderCapsule(c)
            PerfHudLayout.BAR -> renderBar(c, maxW)
            PerfHudLayout.COLUMN -> renderColumn(c, maxW)
            PerfHudLayout.PANEL -> renderPanel(c, maxW)
            PerfHudLayout.DOCK -> renderDock(c, maxW)
        }
    }

    private var stickShader: LinearGradient? = null
    private var stickShaderKey = 0L

    /** 吸顶背景的纵向渐变，只在高度或透明度变化时重建。 */
    private fun stickGradient(): LinearGradient {
        val key = (height.toLong() shl 8) or bgAlpha.toLong()
        stickShader?.takeIf { key == stickShaderKey }?.let { return it }
        val bg = (bgAlpha shl 24) or BG_RGB
        return LinearGradient(0f, 0f, 0f, height.toFloat(), bg,
            PerfHudPalette.withAlpha(BG_RGB, (bgAlpha * 0.7f).toInt()), Shader.TileMode.CLAMP)
            .also { stickShader = it; stickShaderKey = key }
    }

    private fun drawBackground(c: Canvas) {
        val radius = when (hudLayout) {
            PerfHudLayout.CAPSULE -> height / 2f
            PerfHudLayout.DOCK -> em * 1.1f
            else -> em * 0.8f
        }
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (stickTop && hudLayout == PerfHudLayout.BAR) {
            fillPaint.shader = stickGradient()
            // 上沿超出画布一个圆角高度，只露出下面两个圆角
            rect.top = -radius
            c.drawRoundRect(rect, radius, radius, fillPaint)
            fillPaint.shader = null
            strokePaint.color = HAIRLINE
            strokePaint.strokeWidth = dp
            rect.inset(dp / 2, dp / 2)
            c.drawRoundRect(rect, radius, radius, strokePaint)
            return
        }
        fillPaint.color = (bgAlpha shl 24) or BG_RGB
        c.drawRoundRect(rect, radius, radius, fillPaint)
        strokePaint.color = HAIRLINE
        strokePaint.strokeWidth = dp
        rect.inset(dp / 2, dp / 2)
        c.drawRoundRect(rect, radius, radius, strokePaint)
    }

    /** 迷你胶囊：🌔 120 FPS | ≈18 ms（丢包时追加 📶），月相光圈颜色即整体状态。 */
    private fun renderCapsule(c: Canvas?) {
        val s = snapshot
        val padX = em * 0.7f
        val padY = em * 0.4f
        val lh = lineHeight(valuePaint)
        val h = lh + padY * 2
        val base = padY - valuePaint.fontMetrics.ascent
        val gap = em * 0.9f
        var x = padX

        x = drawMoonBadge(c, x, 0f, h)
        x += em * 0.45f

        val fpsStart = x
        x += run(c, fpsText(s.renderedFps), x, base, valuePaint, sevColor(s.fpsSeverity))
        x += run(c, " " + strings.fps, x, base, labelPaint, DIM)
        hit(fpsStart, 0f, x, h, PerfHudTarget.FPS)

        x = drawVDivider(c, x, gap, padY, lh)
        x = hitRun(c, x, base, "≈" + ms0(s.latency.totalMs), valuePaint, sevColor(latencySeverity(s)),
            PerfHudTarget.LATENCY, 0f, h)

        if (s.lossSeverity != PerfSeverity.OK) {
            x = drawVDivider(c, x, gap, padY, lh)
            x = hitRun(c, x, base, "📶 " + pct(s.packetLossPercent), valuePaint, sevColor(s.lossSeverity),
                PerfHudTarget.LOSS, 0f, h)
        }
        contentW = x + padX
        contentH = h
    }

    /** 在 [x] 之后留出 [gap] 宽度，并在正中画一条细竖线，替代文字里的 “·” 分隔。 */
    private fun drawVDivider(c: Canvas?, x: Float, gap: Float, top: Float, lh: Float): Float {
        if (c != null) {
            val mid = x + gap / 2
            strokePaint.color = DIVIDER
            strokePaint.strokeWidth = dp
            c.drawLine(mid, top + lh * 0.2f, mid, top + lh * 0.8f, strokePaint)
        }
        return x + gap
    }

    /**
     * 横屏顶栏：月相徽标 + 若干分组，每组上面一行小标签、下面一行数值，
     * 组与组之间用整高细线隔开；超宽时整组折到下一行。吸顶时（[stickTop]）只圆下面两个角。
     * 顶栏压在画面上沿，字号和留白都比其他形态更紧凑。
     */
    private fun renderBar(c: Canvas?, maxW: Float) {
        val savedLabel = labelPaint.textSize
        val savedValue = valuePaint.textSize
        labelPaint.textSize = em * BAR_LABEL_SCALE
        valuePaint.textSize = em * BAR_VALUE_SCALE
        try {
            renderBarCompact(c, maxW)
        } finally {
            labelPaint.textSize = savedLabel
            valuePaint.textSize = savedValue
        }
    }

    private fun renderBarCompact(c: Canvas?, maxW: Float) {
        val groups = buildBarGroups()
        val padX = em * 0.75f
        val padTop = em * 0.25f
        val padBottom = em * 0.3f
        val gap = em * 0.9f
        val labelH = lineHeight(labelPaint)
        val valueH = lineHeight(valuePaint)
        val rowH = labelH + valueH
        val rowGap = em * 0.3f
        val limit = max(maxW - padX, padX + em * 4)

        var top = padTop
        var x = drawMoonBadge(c, padX, top, top + rowH)
        var right = x
        var first = true
        for (g in groups) {
            val w = groupWidth(g)
            val spacing = if (first) em * 0.6f else gap
            if (x + spacing + w > limit && !first) {
                x = padX
                top += rowH + rowGap
            } else {
                if (!first && c != null) {
                    val mid = x + spacing / 2
                    strokePaint.color = DIVIDER
                    strokePaint.strokeWidth = dp
                    c.drawLine(mid, top + em * 0.1f, mid, top + rowH - em * 0.05f, strokePaint)
                }
                x += spacing
            }
            // 命中区上下各放宽到边缘，顶栏变矮后手指也好按
            val hitTop = top - padTop
            val hitBottom = top + rowH + padBottom
            hit(x - gap / 2, hitTop, x + w + gap / 2, hitBottom, g.target)
            drawPieces(c, g.label, x, top, labelH, labelPaint, hitTop, hitBottom)
            drawPieces(c, g.value, x, top + labelH, valueH, valuePaint, hitTop, hitBottom)
            x += w
            right = max(right, x)
            first = false
        }
        contentW = right + padX
        contentH = top + rowH + padBottom
    }

    private sealed class Piece(val gapBefore: Float, val target: PerfHudTarget?) {
        class Text(val text: String, val paint: TextPaint, val color: Int, gap: Float = 0f, target: PerfHudTarget? = null) :
            Piece(gap, target)
        class Chip(val text: String, val color: Int, gap: Float, target: PerfHudTarget? = null) : Piece(gap, target)
        class Spark(val w: Float, gap: Float) : Piece(gap, PerfHudTarget.FPS)
        class Chain(val w: Float, gap: Float) : Piece(gap, PerfHudTarget.LATENCY)
    }

    private class BarGroup(val target: PerfHudTarget, val label: List<Piece>, val value: List<Piece>)

    private fun buildBarGroups(): List<BarGroup> {
        val s = snapshot
        val v = visibility
        val sp = em * 0.4f
        val out = ArrayList<BarGroup>()
        fun lbl(text: String, gap: Float = 0f, target: PerfHudTarget? = null) =
            Piece.Text(text, labelPaint, DIM, gap, target)

        if (v.stream) {
            val value = ArrayList<Piece>()
            if (v.resolution) {
                val res = if (s.width > 0 && s.height > 0) "${s.width}×${s.height}" else "—"
                value += Piece.Text(res, valuePaint, TXT, target = PerfHudTarget.RESOLUTION)
            }
            if (v.decoder && s.codec.isNotBlank()) {
                value += Piece.Chip(s.codec, TXT, if (value.isEmpty()) 0f else sp, PerfHudTarget.DECODER)
            }
            if (v.resolution && !s.hdrLabel.isNullOrEmpty()) {
                value += Piece.Chip(s.hdrLabel, WARN, sp, PerfHudTarget.RESOLUTION)
            }
            if (value.isNotEmpty()) {
                val target = if (v.resolution) PerfHudTarget.RESOLUTION else PerfHudTarget.DECODER
                out += BarGroup(target, listOf(lbl(strings.resolution)), value)
            }
        }
        if (v.fps || v.onePercentLow) {
            val label = ArrayList<Piece>()
            val value = ArrayList<Piece>()
            if (v.fps) {
                label += lbl(RD_LABEL, target = PerfHudTarget.FPS)
                value += Piece.Text(fpsText(s.renderedFps), valuePaint, sevColor(s.fpsSeverity), target = PerfHudTarget.FPS)
                if (s.targetFps > 0) value += Piece.Text("/" + s.targetFps, labelPaint, MUTE, target = PerfHudTarget.FPS)
                // Rx 正常时灰色退后，主机没送够帧时才变黄 / 红
                addColumn(label, value, RX_LABEL, fpsText(s.receivedFps), rxColor(s), sp, PerfHudTarget.FPS)
                if (s.framegenFps > 0.5f) {
                    addColumn(label, value, FG_LABEL, fps0(s.framegenFps), TXT, sp, PerfHudTarget.FPS)
                }
            }
            if (v.onePercentLow) {
                val low = if (s.onePercentLowFps > 0f) fps0(s.onePercentLowFps) else "—"
                val color = if (s.onePercentLowSeverity == PerfSeverity.OK) DIM else sevColor(s.onePercentLowSeverity)
                addColumn(label, value, strings.onePercentLow, low, color, sp, PerfHudTarget.ONE_PERCENT_LOW)
            }
            if (v.fps) value += Piece.Spark(em * 3.2f, sp * 1.5f)
            out += BarGroup(if (v.fps) PerfHudTarget.FPS else PerfHudTarget.ONE_PERCENT_LOW, label, value)
        }
        if (v.latency) {
            val value = arrayListOf<Piece>(
                Piece.Text("≈" + num0(s.latency.totalMs), valuePaint, sevColor(latencySeverity(s))),
                Piece.Text(" ms", labelPaint, DIM)
            )
            if (s.isDecodeHot) value += Piece.Text("🥵", valuePaint, TXT, sp * 0.5f, PerfHudTarget.DECODE)
            value += Piece.Chain(em * 4.6f, sp * 1.5f)
            out += BarGroup(PerfHudTarget.LATENCY, listOf(lbl(strings.latency)), value)
        }
        if (v.network) {
            val value = ArrayList<Piece>()
            val label = arrayListOf<Piece>(lbl(strings.network))
            val bw = s.bandwidth.takeIf { it.isNotBlank() && it != "N/A" }
            if (bw != null) value += Piece.Text(bw, valuePaint, TXT)
            val bwW = value.sumOf { pieceWidth(it).toDouble() }.toFloat()
            val col = if (bw != null) max(bwW, labelPaint.measureText(strings.network)) + sp * 1.5f else 0f
            value += Piece.Text(s.rttMs.toString(), valuePaint, sevColor(s.rttSeverity), col - bwW)
            value += Piece.Text("±" + s.rttVarianceMs, labelPaint, MUTE)
            if (bw != null) {
                label += Piece.Text(strings.rtt, labelPaint, MUTE, col - labelPaint.measureText(strings.network))
            } else {
                label[0] = lbl(strings.rtt)
            }
            out += BarGroup(PerfHudTarget.NETWORK, label, value)
        }
        if (v.loss) {
            out += BarGroup(PerfHudTarget.LOSS, listOf(lbl(strings.loss)),
                listOf(Piece.Text(pct(s.packetLossPercent), valuePaint, sevColor(s.lossSeverity))))
        }
        val clock = s.clock.takeIf { v.clock }
        if (v.battery && s.batteryLevel >= 0) {
            val value = arrayListOf<Piece>(
                Piece.Text((if (s.charging) "⚡" else "🔋") + s.batteryLevel + "%", valuePaint, sevColor(s.batterySeverity))
            )
            s.drainPercentPerHour?.let {
                value += Piece.Text("−" + String.format(Locale.US, strings.drainPerHour, it), labelPaint, DIM, sp)
            }
            val label = if (clock != null) Piece.Text(clock, labelPaint, DIM, target = PerfHudTarget.CLOCK) else lbl(strings.battery)
            out += BarGroup(PerfHudTarget.BATTERY, listOf(label), value)
        } else if (clock != null) {
            out += BarGroup(PerfHudTarget.CLOCK, listOf(lbl(strings.clock)), listOf(Piece.Text(clock, valuePaint, TXT)))
        }
        return out
    }

    /** 在分组里追加一列：标签和数值左对齐到同一个 x，紧接在前面已有内容之后。 */
    private fun addColumn(
        label: MutableList<Piece>, value: MutableList<Piece>,
        labelText: String, valueText: String, valueColor: Int, sp: Float, target: PerfHudTarget
    ) {
        val valueW = value.sumOf { pieceWidth(it).toDouble() }.toFloat()
        val labelW = label.sumOf { pieceWidth(it).toDouble() }.toFloat()
        val col = if (value.isEmpty() && label.isEmpty()) 0f else max(valueW, labelW) + sp * 1.5f
        value += Piece.Text(valueText, valuePaint, valueColor, col - valueW, target)
        label += Piece.Text(labelText, labelPaint, MUTE, col - labelW, target)
    }

    private fun groupWidth(g: BarGroup): Float =
        max(g.label.sumOf { pieceWidth(it).toDouble() }, g.value.sumOf { pieceWidth(it).toDouble() }).toFloat()

    private fun pieceWidth(p: Piece): Float = p.gapBefore + when (p) {
        is Piece.Text -> p.paint.measureText(p.text)
        is Piece.Chip -> labelPaint.measureText(p.text) + em * 0.7f
        is Piece.Spark -> p.w
        is Piece.Chain -> p.w
    }

    /** 在一行里依次画出各片段；带 target 的片段在整组高度 [hitTop, hitBottom] 上登记更细的命中区。 */
    private fun drawPieces(
        c: Canvas?, pieces: List<Piece>, left: Float, top: Float, lh: Float, basePaint: TextPaint,
        hitTop: Float, hitBottom: Float
    ) {
        val base = top - basePaint.fontMetrics.ascent
        var x = left
        for (p in pieces) {
            x += p.gapBefore
            val start = x
            when (p) {
                is Piece.Text -> x += run(c, p.text, x, base, p.paint, p.color)
                is Piece.Chip -> {
                    val tw = labelPaint.measureText(p.text)
                    val w = tw + em * 0.7f
                    val h = lineHeight(labelPaint) * 1.05f
                    val cy = top + lh / 2
                    if (c != null) {
                        fillPaint.color = PerfHudPalette.withAlpha(p.color, 0x2E)
                        rect.set(x, cy - h / 2, x + w, cy + h / 2)
                        c.drawRoundRect(rect, em * 0.25f, em * 0.25f, fillPaint)
                    }
                    val fm = labelPaint.fontMetrics
                    run(c, p.text, x + em * 0.35f, cy - (fm.ascent + fm.descent) / 2, labelPaint, p.color)
                    x += w
                }
                is Piece.Spark -> {
                    drawMiniSpark(c, x, top + lh * 0.15f, p.w, lh * 0.7f)
                    x += p.w
                }
                is Piece.Chain -> {
                    val barH = em * 0.28f
                    drawChainBar(c, x, top + (lh - barH) / 2, p.w, barH)
                    x += p.w
                }
            }
            if (p.target != null) hit(start - em * 0.15f, hitTop, x + em * 0.15f, hitBottom, p.target)
        }
    }

    /** 竖条：标题行是月相（光圈颜色即整体状态），其下每项一行，标签左对齐、数值右对齐。 */
    private fun renderColumn(c: Canvas?, maxW: Float) {
        val segs = buildSegments()
        val padX = em * 0.7f
        val padY = em * 0.5f
        val lh = lineHeight(valuePaint)
        val rowGap = em * 0.2f
        val labelGap = em * 0.8f

        var inner = moonBadgeWidth()
        for (seg in segs) {
            val labelW = seg.label?.let { labelPaint.measureText(it) + labelGap } ?: 0f
            inner = max(inner, labelW + runsWidth(seg.runs))
        }
        val w = min(inner + padX * 2, maxW)
        val innerRight = w - padX

        var top = padY
        drawMoonBadge(c, padX, top, top + lh)
        top += lh + rowGap

        for (seg in segs) {
            val base = top - valuePaint.fontMetrics.ascent
            if (seg.label != null) {
                run(c, seg.label, padX, base, labelPaint, DIM)
                drawRuns(c, seg.runs, innerRight - runsWidth(seg.runs), base)
            } else {
                drawRuns(c, seg.runs, padX, base)
            }
            hit(0f, top - rowGap / 2, w, top + lh + rowGap / 2, seg.target)
            top += lh + rowGap
        }
        contentW = w
        contentH = top - rowGap + padY
    }

    /** 横屏详细面板：状态文案 + 大号 FPS + 历史曲线 + 延迟链 + 明细。 */
    private fun renderPanel(c: Canvas?, maxW: Float) {
        val w = min(em * PANEL_WIDTH_EM, maxW)
        val padX = em * 0.9f
        val padY = em * 0.75f
        val inner = w - padX * 2
        var top = padY

        top = drawMoodHeader(c, padX, top, inner, rightText = null) + em * 0.5f
        if (visibility.fps || visibility.onePercentLow) {
            top = drawBigFps(c, padX, top, inner) + em * 0.25f
            top = drawSparkline(c, padX, top, inner, em * 2.2f) + em * 0.6f
        }
        if (visibility.latency) {
            top = drawLatencyChain(c, padX, top, inner, withLegend = true) + em * 0.6f
        }
        val rows = detailRows(includeResolution = true)
        if (rows.isNotEmpty()) {
            top = drawDivider(c, padX, top, inner) + em * 0.5f
            top = drawKvRows(c, rows, padX, top, inner)
        } else {
            top -= em * 0.6f
        }
        contentW = w
        contentH = top + padY
    }

    /** 竖屏停靠卡片：铺满视频下方，标准档 = 状态 + 指标块 + 延迟链；详细档追加曲线和明细。 */
    private fun renderDock(c: Canvas?, maxW: Float) {
        val s = snapshot
        val w = if (maxW >= UNBOUNDED) em * PANEL_WIDTH_EM else maxW
        val padX = em * 1.0f
        val padY = em * 0.8f
        val inner = w - padX * 2
        var top = padY

        val right = if (visibility.stream) streamSummary(s) else null
        top = drawMoodHeader(c, padX, top, inner, right) + em * 0.6f
        top = drawTiles(c, padX, top, inner)
        if (visibility.latency) {
            top += em * 0.7f
            top = drawLatencyChain(c, padX, top, inner, withLegend = detailed)
        }
        if (detailed) {
            if (visibility.fps) {
                top += em * 0.6f
                val labelBase = top - labelPaint.fontMetrics.ascent
                run(c, strings.fpsHistory, padX, labelBase, labelPaint, DIM)
                top += lineHeight(labelPaint) + em * 0.2f
                top = drawSparkline(c, padX, top, inner, em * 2.4f)
            }
            val rows = detailRows(includeResolution = false)
            if (rows.isNotEmpty()) {
                top += em * 0.6f
                top = drawDivider(c, padX, top, inner) + em * 0.5f
                top = drawKvRows(c, rows, padX, top, inner)
            }
        }
        contentW = w
        contentH = top + padY
    }

    // ---------------------------------------------------------------- blocks

    private fun drawMoodHeader(c: Canvas?, left: Float, top: Float, inner: Float, rightText: String?): Float {
        val s = snapshot
        val lh = lineHeight(valuePaint)
        val base = top - valuePaint.fontMetrics.ascent
        var x = drawMoonBadge(c, left, top, top + lh)
        x += em * 0.45f

        var rightW = 0f
        if (rightText != null) {
            val avail = (left + inner - x) * 0.5f
            val shown = ellipsize(rightText, labelPaint, avail)
            val tw = labelPaint.measureText(shown)
            val rx = left + inner - tw
            run(c, shown, rx, base, labelPaint, DIM)
            hit(rx, top, rx + tw, top + lh, PerfHudTarget.RESOLUTION)
            rightW += tw + em * 0.6f
        }
        val moodColor = when (s.mood) {
            PerfMood.SMOOTH, PerfMood.FULL_MOON_SMOOTH, PerfMood.WARMING_UP -> TXT
            else -> sevColor(s.overallSeverity.worst(s.batterySeverity))
        }
        val mood = ellipsize(strings.mood(s.mood), valuePaint, left + inner - rightW - x)
        val end = x + run(c, mood, x, base, valuePaint, moodColor)
        hit(x, top, end, top + lh, PerfHudTarget.DIAGNOSIS)
        return top + lh
    }

    /** 大号 Rd 帧率在左；Rx / FG / 1%L 作为小列从右往左排在同一行，和顶栏的帧率组一致。 */
    private fun drawBigFps(c: Canvas?, left: Float, top: Float, inner: Float): Float {
        val s = snapshot
        val h = lineHeight(bigPaint)
        val base = top - bigPaint.fontMetrics.ascent
        var x = left
        if (visibility.fps) {
            x += run(c, fpsText(s.renderedFps), x, base, bigPaint, sevColor(s.fpsSeverity))
            val suffix = buildString {
                if (s.targetFps > 0) append(" / ").append(s.targetFps)
                append(' ').append(RD_LABEL)
            }
            x += run(c, suffix, x, base, labelPaint, DIM)
            hit(left, top, x, top + h, PerfHudTarget.FPS)
        }

        val cols = ArrayList<Triple<String, Pair<String, Int>, PerfHudTarget>>()
        if (visibility.fps) {
            cols += Triple(RX_LABEL, fpsText(s.receivedFps) to rxColor(s), PerfHudTarget.FPS)
            if (s.framegenFps > 0.5f) cols += Triple(FG_LABEL, fps0(s.framegenFps) to TXT, PerfHudTarget.FPS)
        }
        if (visibility.onePercentLow) {
            val value = if (s.onePercentLowFps > 0f) fps0(s.onePercentLowFps) else "—"
            cols += Triple(strings.onePercentLow, value to sevColor(s.onePercentLowSeverity), PerfHudTarget.ONE_PERCENT_LOW)
        }
        var rx = left + inner
        for ((label, v, target) in cols.asReversed()) {
            val vw = valuePaint.measureText(v.first)
            val lw = labelPaint.measureText("$label ")
            val start = rx - vw - lw
            if (start < x + em * 0.5f) break
            run(c, "$label ", start, base, labelPaint, DIM)
            run(c, v.first, start + lw, base, valuePaint, v.second)
            hit(start - em * 0.2f, top, rx, top + h, target)
            rx = start - em * 0.9f
        }
        return top + h
    }

    private fun drawSparkline(c: Canvas?, left: Float, top: Float, inner: Float, h: Float): Float {
        hit(left, top, left + inner, top + h, PerfHudTarget.FPS)
        if (c == null) return top + h
        val s = snapshot
        val bottom = top + h
        val ceiling = max(max(s.targetFps.toFloat(), fpsHistory.max()), 1f) * 1.08f

        fillPaint.color = TRACK
        rect.set(left, top, left + inner, bottom)
        c.drawRoundRect(rect, em * 0.3f, em * 0.3f, fillPaint)

        if (s.targetFps > 0) {
            val ty = bottom - h * (s.targetFps / ceiling)
            strokePaint.color = MUTE
            strokePaint.strokeWidth = dp
            c.drawLine(left, ty, left + inner, ty, strokePaint)
        }
        val n = fpsHistory.size
        if (n >= 2) {
            val step = inner / (HISTORY_SIZE - 1)
            val startX = left + inner - step * (n - 1)
            sparkPath.reset()
            for (i in 0 until n) {
                val px = startX + step * i
                val py = bottom - h * (fpsHistory[i] / ceiling)
                if (i == 0) sparkPath.moveTo(px, py) else sparkPath.lineTo(px, py)
            }
            strokePaint.color = sevColor(s.fpsSeverity)
            strokePaint.strokeWidth = dp * 1.5f
            strokePaint.strokeJoin = Paint.Join.ROUND
            c.drawPath(sparkPath, strokePaint)
            sparkPath.lineTo(startX + step * (n - 1), bottom)
            sparkPath.lineTo(startX, bottom)
            sparkPath.close()
            fillPaint.color = (sevColor(s.fpsSeverity) and 0x00FFFFFF) or 0x22000000
            c.drawPath(sparkPath, fillPaint)
        }
        return bottom
    }

    private fun drawLatencyChain(c: Canvas?, left: Float, top: Float, inner: Float, withLegend: Boolean): Float {
        val s = snapshot
        val chain = s.latency
        val blockTop = top
        val lh = lineHeight(valuePaint)
        val base = top - valuePaint.fontMetrics.ascent
        run(c, strings.latencyChain, left, base, labelPaint, DIM)
        val total = "≈" + ms1(chain.totalMs)
        val tw = valuePaint.measureText(total)
        run(c, total, left + inner - tw, base, valuePaint, sevColor(latencySeverity(s)))
        var y = top + lh + em * 0.3f

        val barH = em * 0.45f
        drawChainBar(c, left, y, inner, barH)
        y += barH

        if (withLegend) {
            y += em * 0.35f
            y = drawLegend(c, left, y, inner)
        }
        hit(left, blockTop, left + inner, y, PerfHudTarget.LATENCY)
        return y
    }

    /** 网络 / 主机 / 解码 / 渲染 按占比拼成的分段条。 */
    private fun drawChainBar(c: Canvas?, left: Float, y: Float, w: Float, barH: Float) {
        if (c == null) return
        fillPaint.color = TRACK
        rect.set(left, y, left + w, y + barH)
        c.drawRoundRect(rect, barH / 2, barH / 2, fillPaint)
        val parts = chainParts(snapshot.latency)
        val sum = parts.sumOf { it.second.toDouble() }.toFloat()
        if (sum <= 0f) return
        c.save()
        c.clipRect(left, y, left + w, y + barH)
        var x = left
        for ((color, value) in parts) {
            val segW = w * value / sum
            if (segW <= 0f) continue
            fillPaint.color = color
            rect.set(x, y, x + segW, y + barH)
            c.drawRoundRect(rect, barH / 2, barH / 2, fillPaint)
            x += segW
        }
        c.restore()
    }

    /** 顶栏里的迷你帧率曲线：不画底板，只画目标线和折线。 */
    private fun drawMiniSpark(c: Canvas?, left: Float, top: Float, w: Float, h: Float) {
        if (c == null) return
        val s = snapshot
        val n = fpsHistory.size
        val bottom = top + h
        val ceiling = max(max(s.targetFps.toFloat(), fpsHistory.max()), 1f) * 1.08f
        if (s.targetFps > 0) {
            val ty = bottom - h * (s.targetFps / ceiling)
            strokePaint.color = DIVIDER
            strokePaint.strokeWidth = dp
            c.drawLine(left, ty, left + w, ty, strokePaint)
        }
        if (n < 2) return
        val step = w / (HISTORY_SIZE - 1)
        val startX = left + w - step * (n - 1)
        sparkPath.reset()
        for (i in 0 until n) {
            val px = startX + step * i
            val py = bottom - h * (fpsHistory[i] / ceiling)
            if (i == 0) sparkPath.moveTo(px, py) else sparkPath.lineTo(px, py)
        }
        strokePaint.color = sevColor(s.fpsSeverity)
        strokePaint.strokeWidth = dp * 1.3f
        strokePaint.strokeJoin = Paint.Join.ROUND
        c.drawPath(sparkPath, strokePaint)
    }

    private fun drawLegend(c: Canvas?, left: Float, top: Float, inner: Float): Float {
        val s = snapshot
        val chain = s.latency
        val items = ArrayList<Triple<Int, String, PerfHudTarget>>()
        if (visibility.network) items += Triple(NET, strings.network + " " + num1(chain.networkMs), PerfHudTarget.NETWORK)
        if (visibility.host) items += Triple(HOST, strings.host + " " + (chain.hostMs?.let(::num1) ?: "🧋"), PerfHudTarget.HOST)
        if (visibility.decode) items += Triple(DEC, strings.decode + " " + num1(chain.decodeMs) + if (s.isDecodeHot) " 🥵" else "", PerfHudTarget.DECODE)
        items += Triple(RND, strings.render + " " + num1(chain.renderMs), PerfHudTarget.LATENCY)

        val lh = lineHeight(labelPaint)
        val dotR = em * 0.22f
        val gap = em * 0.8f
        var x = left
        var y = top
        for ((color, text, target) in items) {
            val w = dotR * 2 + em * 0.3f + labelPaint.measureText(text)
            if (x > left && x + w > left + inner) {
                x = left
                y += lh + em * 0.15f
            }
            if (c != null) {
                fillPaint.color = color
                c.drawCircle(x + dotR, y + lh / 2, dotR, fillPaint)
            }
            val base = y - labelPaint.fontMetrics.ascent
            run(c, text, x + dotR * 2 + em * 0.3f, base, labelPaint, DIM)
            hit(x, y, x + w, y + lh, target)
            x += w + gap
        }
        return y + lh
    }

    private fun drawTiles(c: Canvas?, left: Float, top: Float, inner: Float): Float {
        val s = snapshot
        data class Tile(val value: String, val label: String, val color: Int, val target: PerfHudTarget)
        val tiles = ArrayList<Tile>(4)
        if (visibility.fps) {
            val label = buildString {
                append(RD_LABEL).append("  ").append(RX_LABEL).append(' ').append(fpsText(s.receivedFps))
                if (s.framegenFps > 0.5f) append("  ").append(FG_LABEL).append(' ').append(fps0(s.framegenFps))
            }
            tiles += Tile(fpsText(s.renderedFps), label, sevColor(s.fpsSeverity), PerfHudTarget.FPS)
        }
        if (visibility.latency) tiles += Tile("≈" + num0(s.latency.totalMs), strings.latency + " ms", sevColor(latencySeverity(s)), PerfHudTarget.LATENCY)
        if (visibility.loss) tiles += Tile(pct(s.packetLossPercent), strings.loss, sevColor(s.lossSeverity), PerfHudTarget.LOSS)
        if (visibility.battery && s.batteryLevel >= 0) {
            val v = (if (s.charging) "⚡" else "") + s.batteryLevel + "%"
            tiles += Tile(v, strings.battery, sevColor(s.batterySeverity), PerfHudTarget.BATTERY)
        } else if (visibility.onePercentLow) {
            val v = if (s.onePercentLowFps > 0f) fps0(s.onePercentLowFps) else "—"
            tiles += Tile(v, strings.onePercentLow, sevColor(s.onePercentLowSeverity), PerfHudTarget.ONE_PERCENT_LOW)
        }
        if (tiles.isEmpty()) return top - em * 0.6f

        val gap = em * 0.5f
        val tileW = (inner - gap * (tiles.size - 1)) / tiles.size
        val tilePad = em * 0.5f
        val valueLh = lineHeight(tilePaint)
        val labelLh = lineHeight(labelPaint)
        val tileH = tilePad * 2 + valueLh + labelLh
        var x = left
        for (t in tiles) {
            if (c != null) {
                fillPaint.color = TILE
                rect.set(x, top, x + tileW, top + tileH)
                c.drawRoundRect(rect, em * 0.6f, em * 0.6f, fillPaint)
            }
            val value = ellipsize(t.value, tilePaint, tileW - tilePad * 2)
            run(c, value, x + tilePad, top + tilePad - tilePaint.fontMetrics.ascent, tilePaint, t.color)
            val label = ellipsize(t.label, labelPaint, tileW - tilePad * 2)
            run(c, label, x + tilePad, top + tilePad + valueLh - labelPaint.fontMetrics.ascent, labelPaint, DIM)
            hit(x, top, x + tileW, top + tileH, t.target)
            x += tileW + gap
        }
        return top + tileH
    }

    private fun drawDivider(c: Canvas?, left: Float, top: Float, inner: Float): Float {
        if (c != null) {
            strokePaint.color = DIVIDER
            strokePaint.strokeWidth = dp
            c.drawLine(left, top, left + inner, top, strokePaint)
        }
        return top
    }

    private class KvRow(val label: String, val value: String, val color: Int, val target: PerfHudTarget)

    private fun detailRows(includeResolution: Boolean): List<KvRow> {
        val s = snapshot
        val rows = ArrayList<KvRow>()
        if (includeResolution && visibility.resolution) {
            rows += KvRow(strings.resolution, resolutionText(s), TXT, PerfHudTarget.RESOLUTION)
        }
        if (visibility.decoder) {
            val v = if (s.decoderName.isNotEmpty()) s.codec + " (" + s.decoderName + ")" else s.codec
            rows += KvRow(strings.decoder, v, TXT, PerfHudTarget.DECODER)
        }
        if (visibility.loss && hudLayout == PerfHudLayout.PANEL) {
            rows += KvRow(strings.loss, pct(s.packetLossPercent), sevColor(s.lossSeverity), PerfHudTarget.LOSS)
        }
        if (visibility.network) {
            rows += KvRow(strings.rtt, "${s.rttMs} ms ±${s.rttVarianceMs}", sevColor(s.rttSeverity), PerfHudTarget.NETWORK)
            rows += KvRow(strings.bandwidth, s.bandwidth, TXT, PerfHudTarget.NETWORK)
        }
        if (visibility.onePercentLow && hudLayout == PerfHudLayout.DOCK) {
            val v = if (s.onePercentLowFps > 0f) fps0(s.onePercentLowFps) + " " + strings.fps else "—"
            rows += KvRow(strings.onePercentLow, v, sevColor(s.onePercentLowSeverity), PerfHudTarget.ONE_PERCENT_LOW)
        }
        if (visibility.battery && s.batteryLevel >= 0) {
            val v = buildString {
                if (s.charging) append("⚡ ")
                append(s.batteryLevel).append('%')
                s.drainPercentPerHour?.let { append(" (-").append(String.format(Locale.US, strings.drainPerHour, it)).append(')') }
            }
            rows += KvRow(strings.battery, v, sevColor(s.batterySeverity), PerfHudTarget.BATTERY)
        }
        if (visibility.clock && s.clock != null) {
            rows += KvRow(strings.clock, s.clock, TXT, PerfHudTarget.CLOCK)
        }
        return rows
    }

    private fun drawKvRows(c: Canvas?, rows: List<KvRow>, left: Float, top: Float, inner: Float): Float {
        val lh = lineHeight(valuePaint)
        val rowGap = em * 0.25f
        var y = top
        for (row in rows) {
            val base = y - valuePaint.fontMetrics.ascent
            val lw = run(c, row.label, left, base, labelPaint, DIM)
            val value = ellipsize(row.value, valuePaint, inner - lw - em * 0.8f)
            val vw = valuePaint.measureText(value)
            run(c, value, left + inner - vw, base, valuePaint, row.color)
            hit(left, y - rowGap / 2, left + inner, y + lh + rowGap / 2, row.target)
            y += lh + rowGap
        }
        return y - rowGap
    }

    // ---------------------------------------------------------------- segments (COLUMN)

    private class Run(val text: String, val paint: TextPaint, val color: Int)
    private class Seg(val target: PerfHudTarget, val label: String?, val runs: List<Run>)

    private fun buildSegments(): List<Seg> {
        val s = snapshot
        val v = visibility
        val segs = ArrayList<Seg>()

        if (v.resolution) {
            segs += Seg(PerfHudTarget.RESOLUTION, null, listOf(Run(resolutionText(s), valuePaint, TXT)))
        }
        if (v.decoder) {
            segs += Seg(PerfHudTarget.DECODER, strings.decoder, listOf(Run(s.codec, valuePaint, DIM)))
        }
        if (v.fps) {
            val runs = arrayListOf(Run(fpsText(s.renderedFps), valuePaint, sevColor(s.fpsSeverity)))
            if (s.targetFps > 0) runs += Run("/" + s.targetFps, labelPaint, MUTE)
            segs += Seg(PerfHudTarget.FPS, RD_LABEL, runs)
            segs += Seg(PerfHudTarget.FPS, RX_LABEL, listOf(Run(fpsText(s.receivedFps), valuePaint, rxColor(s))))
            if (s.framegenFps > 0.5f) segs += Seg(PerfHudTarget.FPS, FG_LABEL, listOf(Run(fps0(s.framegenFps), valuePaint, TXT)))
        }
        if (v.onePercentLow) {
            val value = if (s.onePercentLowFps > 0f) fps0(s.onePercentLowFps) else "—"
            segs += Seg(PerfHudTarget.ONE_PERCENT_LOW, strings.onePercentLow,
                listOf(Run(value, valuePaint, sevColor(s.onePercentLowSeverity))))
        }
        if (v.latency) {
            segs += Seg(PerfHudTarget.LATENCY, strings.latency,
                listOf(Run("≈" + ms0(s.latency.totalMs), valuePaint, sevColor(latencySeverity(s)))))
        }
        if (v.network) {
            segs += Seg(PerfHudTarget.NETWORK, strings.rtt, listOf(
                Run(s.rttMs.toString(), valuePaint, sevColor(s.rttSeverity)),
                Run(" ±" + s.rttVarianceMs, labelPaint, MUTE)))
        }
        if (v.loss) {
            segs += Seg(PerfHudTarget.LOSS, strings.loss, listOf(Run(pct(s.packetLossPercent), valuePaint, sevColor(s.lossSeverity))))
        }
        if (v.decode) {
            val runs = arrayListOf(Run(num1(s.latency.decodeMs), valuePaint, sevColor(s.decodeSeverity)))
            if (s.isDecodeHot) runs += Run(" 🥵", valuePaint, TXT)
            segs += Seg(PerfHudTarget.DECODE, strings.decode, runs)
        }
        if (v.host) {
            segs += Seg(PerfHudTarget.HOST, strings.host,
                listOf(Run(s.latency.hostMs?.let(::num1) ?: "🧋", valuePaint, TXT)))
        }
        if (v.battery && s.batteryLevel >= 0) {
            val text = (if (s.charging) "⚡" else "🔋") + s.batteryLevel + "%"
            segs += Seg(PerfHudTarget.BATTERY, strings.battery,
                listOf(Run(text, valuePaint, sevColor(s.batterySeverity))))
        }
        if (v.clock && s.clock != null) {
            segs += Seg(PerfHudTarget.CLOCK, strings.clock, listOf(Run(s.clock, valuePaint, DIM)))
        }
        return segs
    }

    private fun runsWidth(runs: List<Run>): Float = runs.sumOf { it.paint.measureText(it.text).toDouble() }.toFloat()

    private fun drawRuns(c: Canvas?, runs: List<Run>, left: Float, base: Float): Float {
        var x = left
        for (r in runs) x += run(c, r.text, x, base, r.paint, r.color)
        return x
    }

    // ---------------------------------------------------------------- primitives

    private fun run(c: Canvas?, text: String, x: Float, base: Float, paint: TextPaint, color: Int): Float {
        if (c != null) {
            paint.color = color
            c.drawText(text, x, base, paint)
        }
        return paint.measureText(text)
    }

    private fun hitRun(
        c: Canvas?, x: Float, base: Float, text: String, paint: TextPaint, color: Int,
        target: PerfHudTarget, top: Float, bottom: Float
    ): Float {
        val end = x + run(c, text, x, base, paint, color)
        hit(x, top, end, bottom, target)
        return end
    }

    /**
     * 月相状态徽标：真实月相 emoji 外套一圈状态色光圈（绿/黄/红 = 整体严重度），
     * 光圈也让深色背景上的新月 🌑 看得见。长按打开 🍥 串流体检。
     */
    private fun drawMoonBadge(c: Canvas?, x: Float, top: Float, bottom: Float): Float {
        val icon = snapshot.moonIcon
        val w = moonBadgeWidth()
        val iconW = labelPaint.measureText(icon)
        val fm = labelPaint.fontMetrics
        val cx = x + w / 2
        val cy = (top + bottom) / 2
        val iconBase = cy - (fm.ascent + fm.descent) / 2
        if (c != null) {
            val r = w / 2 - dp
            val color = sevColor(snapshot.overallSeverity)
            fillPaint.color = (color and 0x00FFFFFF) or (0x40 shl 24)
            c.drawCircle(cx, cy, r, fillPaint)
            strokePaint.color = color
            strokePaint.strokeWidth = dp * 1.5f
            c.drawCircle(cx, cy, r, strokePaint)
        }
        run(c, icon, cx - iconW / 2, iconBase, labelPaint, TXT)
        hit(x - em * 0.3f, top, x + w + em * 0.2f, bottom, PerfHudTarget.DIAGNOSIS)
        return x + w
    }

    private fun moonBadgeWidth(): Float = max(labelPaint.measureText(snapshot.moonIcon), lineHeight(labelPaint) * 0.9f) + em * 0.35f

    private fun hit(l: Float, t: Float, r: Float, b: Float, target: PerfHudTarget) {
        hits += Hit(RectF(l, t, r, b), target)
    }

    private fun lineHeight(p: TextPaint): Float = p.fontMetrics.let { it.descent - it.ascent }

    private fun ellipsize(text: String, paint: TextPaint, avail: Float): String {
        if (avail <= 0f) return ""
        if (paint.measureText(text) <= avail) return text
        return TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.END).toString()
    }

    private fun applyTextSize() {
        valuePaint.textSize = em
        labelPaint.textSize = em * 0.82f
        bigPaint.textSize = em * 2.4f
        tilePaint.textSize = em * 1.55f
        val shadow = max(dp * 1.5f, em * 0.1f)
        for (p in arrayOf(valuePaint, labelPaint, bigPaint, tilePaint)) {
            p.setShadowLayer(shadow, 0f, dp * 0.5f, SHADOW)
        }
    }

    private fun newTextPaint(bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }

    private fun chainParts(chain: LatencyChain): List<Pair<Int, Float>> = listOf(
        NET to chain.networkMs,
        HOST to (chain.hostMs ?: 0f),
        DEC to chain.decodeMs,
        RND to chain.renderMs
    )

    private fun latencySeverity(s: PerfHudSnapshot) = s.rttSeverity.worst(s.decodeSeverity)

    /** Rx 正常时灰色退后，主机没送够帧时才变黄 / 红。 */
    private fun rxColor(s: PerfHudSnapshot): Int =
        PerfHudThresholds.fps(s.receivedFps, s.targetFps).let { if (it == PerfSeverity.OK) DIM else sevColor(it) }

    private fun streamSummary(s: PerfHudSnapshot): String = buildString {
        if (visibility.resolution) append(resolutionText(s))
        if (visibility.decoder) {
            if (isNotEmpty()) append("  ")
            append(s.codec)
        }
    }

    private fun resolutionText(s: PerfHudSnapshot): String = buildString {
        if (s.width > 0 && s.height > 0) append(s.width).append('×').append(s.height) else append("—")
        if (!s.hdrLabel.isNullOrEmpty()) append(' ').append(s.hdrLabel)
    }

    private fun fpsText(v: Float) = if (v > 0f) fps0(v) else "--"
    private fun fps0(v: Float) = String.format(Locale.US, "%.0f", v)
    private fun num0(v: Float) = String.format(Locale.US, "%.0f", v)
    private fun num1(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun ms0(v: Float) = num0(v) + " ms"
    private fun ms1(v: Float) = num1(v) + " ms"
    private fun pct(v: Float) = String.format(Locale.US, if (v >= 10f) "%.0f%%" else "%.1f%%", v)

    private fun sevColor(s: PerfSeverity) = when (s) {
        PerfSeverity.OK -> OK
        PerfSeverity.WARN -> WARN
        PerfSeverity.BAD -> BAD
    }

    companion object {
        const val HISTORY_SIZE = 45
        /** 帧率三项的缩写，与经典样式和帧率说明卡片保持一致，不做翻译。 */
        private const val RD_LABEL = "Rd"
        private const val RX_LABEL = "Rx"
        private const val FG_LABEL = "FG"
        private const val PANEL_WIDTH_EM = 20f
        /** 顶栏字号相对 em 的比例：标签 / 数值。 */
        private const val BAR_LABEL_SCALE = 0.7f
        private const val BAR_VALUE_SCALE = 0.92f
        private const val UNBOUNDED = 100_000f

        private const val BG_RGB = PerfHudPalette.BG_RGB
        private const val TXT = PerfHudPalette.TXT
        private const val DIM = PerfHudPalette.DIM
        private const val MUTE = PerfHudPalette.MUTE
        private const val DIVIDER = PerfHudPalette.DIVIDER
        private const val HAIRLINE = PerfHudPalette.HAIRLINE
        private const val TRACK = PerfHudPalette.TRACK
        private const val TILE = PerfHudPalette.TILE
        private const val SHADOW = PerfHudPalette.SHADOW
        private const val OK = PerfHudPalette.OK
        private const val WARN = PerfHudPalette.WARN
        private const val BAD = PerfHudPalette.BAD
        private const val NET = PerfHudPalette.NET
        private const val HOST = PerfHudPalette.HOST
        private const val DEC = PerfHudPalette.DEC
        private const val RND = PerfHudPalette.RND

        private val EMPTY = PerfHudSnapshot(
            width = 0, height = 0, targetFps = 0, codec = "", hdrLabel = null, decoderName = "",
            renderedFps = 0f, receivedFps = 0f, framegenFps = 0f, onePercentLowFps = 0f,
            packetLossPercent = 0f, rttMs = 0, rttVarianceMs = 0, bandwidth = "",
            latency = LatencyChain(0f, null, 0f, 0f), batteryLevel = -1, charging = false,
            drainPercentPerHour = null, clock = null, moonIcon = "🌙", fullMoon = false
        )
    }
}
