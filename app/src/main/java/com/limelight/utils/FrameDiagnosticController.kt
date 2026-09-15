package com.limelight.utils

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.FramegenSettings
import com.limelight.preferences.PreferenceConfiguration
import java.util.Locale

/**
 * 机体诊断控制台：把连接等待渲染成一次出撃前的机检序列。
 *
 * 设计要点：外壳层（面板/角括号/仪表块/标签，6dp 圆角）跟随 App 圆角语言，
 * 内核层（等宽字体/点线导读/OK 反白闪/状态码）保持机检硬度；
 * 逐行机检本局串流配置，HOST LINK 随真实连接 stage 推进，
 * SYNC RATIO 仪表按评级打分充能，连接建立时翻 ESTABLISHED 进入 READY 态。
 * 所有数据来自 PreferenceConfiguration 现有字段，无新增设置。
 */
class FrameDiagnosticController(private val context: Context, root: View) {

    companion object {
        private const val ACCENT = 0xFFFF6B9D.toInt()      // theme_pink_primary
        private const val ACCENT_DARK = 0xFF06070C.toInt()
        private const val AMBER = 0xFFF5A623.toInt()
        private const val ADAPT_GREEN = 0xFF57E0A5.toInt()
        private const val TEXT_DIM = 0xB3FFFFFF.toInt()
        private const val TEXT_FAINT = 0x4DFFFFFF.toInt()
        private const val PANEL_RADIUS_DP = 6f             // 已确认的「6 档」
        private const val READY_LINE = "出撃シーケンス開始 — ALL SYSTEMS GREEN"

        /** 评级打分与设计稿一致：分辨率 + 帧率 + 编码 + HDR，满分 9。 */
        fun syncScore(cfg: PreferenceConfiguration): Int {
            val res = when {
                cfg.height >= 2160 -> 3
                cfg.height >= 1440 -> 2
                cfg.height >= 1080 -> 1
                else -> 0
            }
            val fps = when {
                cfg.fps >= 120 -> 3
                cfg.fps >= 90 -> 2
                cfg.fps >= 60 -> 1
                else -> 0
            }
            val codec = when (cfg.videoFormat) {
                PreferenceConfiguration.FormatOption.FORCE_AV1 -> 2
                PreferenceConfiguration.FormatOption.FORCE_HEVC -> 1
                PreferenceConfiguration.FormatOption.FORCE_H264 -> 0
                else -> 1 // AUTO 按 HEVC 计
            }
            return res + fps + codec + if (cfg.enableHdr) 1 else 0
        }
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private val mono: Typeface = Typeface.MONOSPACE
    private val handler = Handler(Looper.getMainLooper())
    private val animators = ArrayList<ValueAnimator>()

    private val console: LinearLayout = root.findViewById(R.id.diagConsole)
    private val linesBox: LinearLayout = root.findViewById(R.id.diagLines)
    private val blocksBox: LinearLayout = root.findViewById(R.id.diagBlocks)
    private val timerView: TextView = root.findViewById(R.id.diagTimer)
    private val pctView: TextView = root.findViewById(R.id.diagPct)
    private val rankView: TextView = root.findViewById(R.id.diagRank)
    private val msTag: TextView = root.findViewById(R.id.diagMsTag)
    private val logView: TextView = root.findViewById(R.id.diagLog)
    private val cursorView: TextView = root.findViewById(R.id.diagCursor)
    private val metaView: TextView = root.findViewById(R.id.diagMeta)
    private val viewfinder: PosterViewfinderView = root.findViewById(R.id.posterViewfinder)

    private val panelDrawable = DiagPanelDrawable(dp(PANEL_RADIUS_DP).toFloat())
    private var hostLine: LineHolder? = null
    private var stageSeq = 0
    private var bootTime = 0L
    private var timerRunning = false
    private var established = false
    private var hostLabel = ""

    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            timerView.text = formatElapsed(SystemClock.elapsedRealtime() - bootTime)
            handler.postDelayed(this, 100)
        }
    }

    init {
        console.background = panelDrawable
        console.alpha = 0f
        listOf(
            R.id.diagTitle, R.id.diagTitleSub, R.id.diagTimer, R.id.diagPct,
            R.id.diagRank, R.id.diagMsTag, R.id.diagLog, R.id.diagCursor,
            R.id.diagSync, R.id.diagMeta
        ).forEach { root.findViewById<TextView>(it).typeface = mono }
        bindBracket(root.findViewById(R.id.cbA), BracketDrawable.TL)
        bindBracket(root.findViewById(R.id.cbB), BracketDrawable.TR)
        bindBracket(root.findViewById(R.id.cbC), BracketDrawable.BL)
        bindBracket(root.findViewById(R.id.cbD), BracketDrawable.BR)
        msTag.background = TagBoxDrawable(AMBER, dp(2f).toFloat())
        msTag.setPadding(dp(3f), dp(1f), dp(3f), 0)
    }

    private fun bindBracket(view: View, corner: Int) {
        view.background = BracketDrawable(dp(4f).toFloat(), corner)
    }

    /** 载入配置并播放开机时序。config 为空时隐藏控制台（防御路径）。 */
    fun start(config: PreferenceConfiguration?) {
        cancel()
        established = false
        stageSeq = 0
        if (config == null) {
            console.visibility = View.GONE
            viewfinder.visibility = View.GONE
            return
        }
        console.visibility = View.VISIBLE
        // 等一帧布局再定宽启动机检，保证点线导读有正确宽度可铺
        console.post {
            val parentWidth = (console.parent as? View)?.width ?: 0
            if (parentWidth > 0) {
                console.layoutParams.width = (parentWidth * 0.58f).toInt()
                console.requestLayout()
            }
            console.post { boot(config) }
        }
    }

    private fun boot(config: PreferenceConfiguration) {
        // 旧一轮的呼吸动画全部作废（cancel 后视图若复用会停留在半透明）
        animators.clear()
        blink(cursorView).also { animators.add(it) }

        val holders = buildLines(config).map { addLine(it) }
        hostLine = holders.lastOrNull { it.data.kind == LineKind.LIVE }

        pctView.text = "--%"
        rankView.text = "RANK -"
        msTag.visibility = View.INVISIBLE
        blocksBox.removeAllViews()
        val blocks = ArrayList<View>()
        repeat(10) {
            val b = View(context)
            b.background = SkewBlockDrawable(ACCENT, 0x1FFFFFFF, dp(3f).toFloat())
            b.layoutParams = LinearLayout.LayoutParams(dp(8f), dp(11f)).apply { marginEnd = dp(3f) }
            blocksBox.addView(b)
            blocks.add(b)
        }
        metaView.text = metaText()

        // T0 控制台载入
        console.alpha = 0f
        console.translationY = dp(6f).toFloat()
        at(120) {
            console.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }

        // T0.25 起逐行机检：行滑入，状态列稍后反白闪成 OK
        holders.forEachIndexed { i, h ->
            at(300 + i * 140) {
                h.row.translationX = dp(-8f).toFloat()
                h.row.alpha = 0f
                h.row.animate().translationX(0f).alpha(1f).setDuration(180)
                    .setInterpolator(DecelerateInterpolator()).start()
                if (h.data.kind == LineKind.OK) {
                    handler.postDelayed({ flashOk(h.status) }, 120)
                }
            }
        }

        // 仪表充能 → 百分比 → RANK 定格
        val score = syncScore(config)
        val rank = rankOf(score)
        val filled = Math.round(rank.pct / 100f * 10)
        val gaugeT = (300 + holders.size * 140 + 200).toLong()
        blocks.forEachIndexed { i, b ->
            if (i < filled) {
                handler.postDelayed({
                    (b.background as SkewBlockDrawable).on = true
                    b.invalidate()
                }, gaugeT + 120 + i * 60)
            }
        }
        handler.postDelayed({ pctView.text = rank.pctText }, gaugeT + 140 + filled * 60)
        handler.postDelayed({
            rankView.text = "RANK " + rank.letter
            flashTextView(rankView)
        }, gaugeT + 240 + filled * 60)
        if (score >= 8) {
            handler.postDelayed({ msTag.visibility = View.VISIBLE }, gaugeT + 360 + filled * 60)
        }

        bootTime = SystemClock.elapsedRealtime()
        timerRunning = true
        handler.post(timerTick)
    }

    /** 连接 stage 回调：计数 + 写日志行。 */
    fun onStage(message: String) {
        stageSeq++
        metaView.text = metaText()
        setLog(message)
    }

    fun setLog(message: String) {
        logView.text = message
    }

    /** 连接建立（connectionStarted）：HOST LINK → ESTABLISHED，面板脉冲，READY。 */
    fun onConnectionEstablished() {
        if (established) return
        established = true
        hostLine?.let { h ->
            h.value.text = "ESTABLISHED"
            h.blink?.cancel()
            h.status.alpha = 1f
            flashOk(h.status)
        }
        panelDrawable.pulse()
        setLog(READY_LINE)
    }

    fun cancel() {
        timerRunning = false
        handler.removeCallbacksAndMessages(null)
        animators.forEach { it.cancel() }
        console.animate().cancel()
    }

    fun setHostLabel(host: String?) {
        hostLabel = host ?: ""
        metaView.text = metaText()
    }

    // ---------------- 内部 ----------------

    private fun at(delay: Int, action: () -> Unit) {
        handler.postDelayed(action, delay.toLong())
    }

    private fun blink(view: View): ValueAnimator =
        ValueAnimator.ofFloat(1f, 0.25f).apply {
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            duration = 450
            interpolator = LinearInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        val tenth = (ms % 1000) / 100
        return String.format(Locale.US, "T+%02d:%02d.%d", s / 60, s % 60, tenth)
    }

    private fun metaText(): String =
        "MOONLIGHT//FRAME-DIAG" + (if (hostLabel.isNotEmpty()) " · $hostLabel" else "") +
            " · SEQ ${"%02d".format(stageSeq)}"

    private class Rank(val letter: String, val pct: Float) {
        val pctText: String
            get() = if (letter == "SSS") "99.8%" else String.format(Locale.US, "%.1f%%", pct)
    }

    private fun rankOf(score: Int): Rank {
        val letter = when {
            score >= 8 -> "SSS"
            score >= 6 -> "S"
            score >= 3 -> "A"
            else -> "B"
        }
        return Rank(letter, if (letter == "SSS") 99.8f else 55f + score * 4.9f)
    }

    private enum class LineKind { OK, LIVE }

    private data class DiagLine(
        val label: String,
        val value: String,
        val tag: String? = null,
        val adapt: Boolean = false,
        val kind: LineKind = LineKind.OK
    )

    private class LineHolder(
        val data: DiagLine,
        val row: View,
        val value: TextView,
        val status: TextView,
        val blink: ValueAnimator?
    )

    private fun buildLines(cfg: PreferenceConfiguration): List<DiagLine> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val l = ArrayList<DiagLine>()

        val res = when {
            cfg.height >= 2160 -> "2160p"
            cfg.height >= 1440 -> "1440p"
            cfg.height >= 1080 -> "1080p"
            else -> cfg.height.toString() + "p"
        }
        l += DiagLine("VIDEO UNIT", "$res · ${cfg.fps}FPS")

        val codec = when (cfg.videoFormat) {
            PreferenceConfiguration.FormatOption.FORCE_AV1 -> "AV1"
            PreferenceConfiguration.FormatOption.FORCE_HEVC -> "HEVC"
            PreferenceConfiguration.FormatOption.FORCE_H264 -> "H.264"
            else -> "AUTO"
        }
        l += DiagLine("CODEC", codec, if (codec == "AV1") "NEXT" else null)

        if (cfg.enableHdr) {
            val hdrName = when (cfg.hdrMode) {
                MoonBridge.HDR_MODE_HLG -> "HLG"
                MoonBridge.HDR_MODE_HDR10_PLUS -> "HDR10+"
                MoonBridge.HDR_MODE_DOLBY_VISION -> "DOLBY-V"
                MoonBridge.HDR_MODE_DOLBY_VISION_84 -> "DOLBY-V84"
                else -> "HDR10"
            }
            l += DiagLine("HDR OUTPUT", hdrName)
        }

        val mbps = Math.round(cfg.bitrate / 1000f)
        l += DiagLine("DATA LINK", "$mbps Mbps", if (cfg.enableAdaptiveBitrate) "ADAPT" else null, cfg.enableAdaptiveBitrate)

        if (FramegenSettings.isUserEnabled(prefs)) {
            val adaptive = FramegenSettings.isAdaptiveEnabled(prefs)
            l += DiagLine("FG MODULE", "ACTIVE", if (adaptive) "ADAPT" else null, adaptive)
        }

        when {
            cfg.enableSpatializer -> l += DiagLine("AUDIO UNIT", "SPATIAL")
            cfg.audioConfiguration === MoonBridge.AUDIO_CONFIGURATION_51_SURROUND -> l += DiagLine("AUDIO UNIT", "5.1CH")
            cfg.audioConfiguration === MoonBridge.AUDIO_CONFIGURATION_71_SURROUND -> l += DiagLine("AUDIO UNIT", "7.1CH")
            cfg.audioConfiguration === MoonBridge.AUDIO_CONFIGURATION_714_SURROUND -> l += DiagLine("AUDIO UNIT", "7.1.4CH")
        }

        l += DiagLine("HOST LINK", "NEGOTIATING", kind = LineKind.LIVE)
        return l
    }

    private fun addLine(data: DiagLine): LineHolder {
        val row = LinearLayout(context)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2f); bottomMargin = dp(2f) }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val label = TextView(context).apply {
            typeface = mono
            textSize = 11f
            setTextColor(TEXT_DIM)
            text = data.label
        }
        row.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val leader = View(context)
        leader.background = DashLeaderDrawable(density)
        row.addView(leader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(6f); marginEnd = dp(6f)
        })

        val value = TextView(context).apply {
            typeface = mono
            textSize = 11f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            text = data.value
        }
        row.addView(value, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        data.tag?.let { tagText ->
            val tagColor = if (data.adapt) ADAPT_GREEN else ACCENT
            val tag = TextView(context).apply {
                typeface = mono
                textSize = 7.5f
                setTextColor(tagColor)
                text = tagText
                background = TagBoxDrawable(tagColor, dp(2f).toFloat())
                setPadding(dp(3f), dp(1f), dp(3f), 0)
            }
            row.addView(tag, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4f)
            })
        }

        val status = TextView(context).apply {
            typeface = mono
            textSize = 11f
            setTextColor(TEXT_FAINT)
            gravity = Gravity.END
            text = if (data.kind == LineKind.LIVE) "▮▮▮" else "--"
        }
        row.addView(status, LinearLayout.LayoutParams(dp(38f), LinearLayout.LayoutParams.WRAP_CONTENT))

        if (data.kind == LineKind.LIVE) {
            row.tag = blink(status).also { animators.add(it) }
        }

        linesBox.addView(row)
        return LineHolder(data, row, value, status, row.tag as? ValueAnimator)
    }

    /** 状态列反白闪：背景吃强调色、文字瞬时反色，落回「粉字透明底」。 */
    private fun flashOk(status: TextView) {
        status.setBackgroundColor(ACCENT)
        status.setTextColor(ACCENT_DARK)
        status.text = "OK"
        handler.postDelayed({
            status.setBackgroundColor(Color.TRANSPARENT)
            status.setTextColor(ACCENT)
        }, 110)
    }

    private fun flashTextView(v: TextView) {
        v.setBackgroundColor(ACCENT)
        v.setTextColor(ACCENT_DARK)
        handler.postDelayed({
            v.setBackgroundColor(Color.TRANSPARENT)
            v.setTextColor(ACCENT)
        }, 110)
    }

    // ---------------- 自绘元素（外壳层 6dp 圆角档） ----------------

    /** 诊断面板：圆角近黑底 + 1px 描边 + 极淡扫描线纹理；glow 属性驱动 READY 描边脉冲。 */
    private class DiagPanelDrawable(private val radiusPx: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD06070C.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        private val scan = Paint().apply { color = 0x07FFFFFF }

        var glow = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val r = RectF(bounds)
            canvas.drawRoundRect(r, radiusPx, radiusPx, fill)
            var y = bounds.top + 2f
            while (y < bounds.bottom) {
                canvas.drawRect(bounds.left.toFloat(), y, bounds.right.toFloat(), y + 1f, scan)
                y += 3f
            }
            stroke.color = blendArgb(0x38FFFFFF, ACCENT, glow)
            canvas.drawRoundRect(r, radiusPx, radiusPx, stroke)
            if (glow > 0.02f) {
                stroke.color = (ACCENT and 0x00FFFFFF) or ((0xB4 * glow).toInt() shl 24)
                canvas.drawRoundRect(r, radiusPx, radiusPx, stroke)
            }
        }

        fun pulse() {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 550
                interpolator = DecelerateInterpolator()
                addUpdateListener { glow = it.animatedValue as Float }
                start()
            }
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            stroke.alpha = alpha
            scan.alpha = alpha / 3
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 座舱角括号：L 形描边，拐角带圆角（≈面板半径 70%）。 */
    private class BracketDrawable(cornerPx: Float, private val corner: Int) : Drawable() {

        companion object {
            const val TL = 0
            const val TR = 1
            const val BL = 2
            const val BR = 3
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
            color = 0x4DFFFFFF
            pathEffect = CornerPathEffect(cornerPx)
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
            val r = bounds.right.toFloat(); val b = bounds.bottom.toFloat()
            path.reset()
            when (corner) {
                TL -> { path.moveTo(r, t); path.lineTo(l, t); path.lineTo(l, b) }
                TR -> { path.moveTo(l, t); path.lineTo(r, t); path.lineTo(r, b) }
                BL -> { path.moveTo(l, t); path.lineTo(l, b); path.lineTo(r, b) }
                else -> { path.moveTo(r, t); path.lineTo(r, b); path.lineTo(l, b) }
            }
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 点线导读：行内填充线，让所有值列右对齐成仪表盘。 */
    private class DashLeaderDrawable(density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = density
            color = 0x38FFFFFF
            pathEffect = DashPathEffect(floatArrayOf(density * 2, density * 3), 0f)
        }

        override fun draw(canvas: Canvas) {
            val y = bounds.height() / 2f
            canvas.drawLine(0f, y, bounds.width().toFloat(), y, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** SYNC 仪表块：斜切圆角小方块（内核的运动感 + 外壳的 3dp 圆角）。 */
    private class SkewBlockDrawable(private val onColor: Int, private val offColor: Int, private val radiusPx: Float) : Drawable() {
        var on = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            paint.color = if (on) onColor else offColor
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            canvas.save()
            canvas.skew(-0.18f, 0f)
            rect.set(-w * 0.15f, 0f, w * 0.92f, h)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 值旁小标签（NEXT / ADAPT / MAX SYNC）：描边小盒。 */
    private class TagBoxDrawable(color: Int, radiusPx: Float) : GradientDrawable() {
        init {
            setCornerRadius(radiusPx)
            setStroke(1, (color and 0x00FFFFFF) or 0x66000000)
            setColor((color and 0x00FFFFFF) or 0x14000000)
        }
    }

}

/** 海报取景框：四角小角标 + 开机扫描线掠过一次。 */
class PosterViewfinderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

        private val density = context.resources.displayMetrics.density
        private var rect: RectF? = null
        private var sweepT = -1f

        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = 0x80FFFFFF.toInt()
        }
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setPosterRect(r: RectF?) {
            rect = r
            visibility = if (r == null) INVISIBLE else VISIBLE
        }

        fun playSweep() {
            val r = rect ?: return
            sweepPaint.shader = LinearGradient(
                r.left, 0f, r.right, 0f,
                intArrayOf(0x00FF6B9D.toInt(), 0xD9FF6B9D.toInt(), 0x00FF6B9D.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            sweepT = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                interpolator = LinearInterpolator()
                addUpdateListener {
                    sweepT = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(a: Animator) = Unit
                    override fun onAnimationCancel(a: Animator) = Unit
                    override fun onAnimationRepeat(a: Animator) = Unit
                    override fun onAnimationEnd(a: Animator) {
                        sweepT = -1f
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = rect ?: return
            val len = 9f * density
            val off = 4f * density
            val corners = listOf(
                Triple(r.left - off, r.top - off, listOf(1f to 0f, 0f to 1f)),
                Triple(r.right + off, r.top - off, listOf(-1f to 0f, 0f to 1f)),
                Triple(r.left - off, r.bottom + off, listOf(1f to 0f, 0f to -1f)),
                Triple(r.right + off, r.bottom + off, listOf(-1f to 0f, 0f to -1f))
            )
            corners.forEach { (x, y, dirs) ->
                dirs.forEach { (dx, dy) ->
                    canvas.drawLine(x, y, x + dx * len, y + dy * len, tickPaint)
                }
            }
            if (sweepT in 0f..1f) {
                val fade = Math.sin((sweepT * Math.PI)).toFloat()
                sweepPaint.alpha = (fade * 220).toInt()
                val y = r.top + r.height() * sweepT
                canvas.drawRect(r.left, y, r.right, y + 2f * density, sweepPaint)
            }
    }
}

private fun blendArgb(a: Int, b: Int, t: Float): Int {
    val ia = a ushr 24; val ir = a shr 16 and 0xFF; val ig = a shr 8 and 0xFF; val ib = a and 0xFF
    val ja = b ushr 24; val jr = b shr 16 and 0xFF; val jg = b shr 8 and 0xFF; val jb = b and 0xFF
    val oa = ia + ((ja - ia) * t).toInt()
    val or = ir + ((jr - ir) * t).toInt()
    val og = ig + ((jg - ig) * t).toInt()
    val ob = ib + ((jb - ib) * t).toInt()
    return (oa shl 24) or (or shl 16) or (og shl 8) or ob
}
