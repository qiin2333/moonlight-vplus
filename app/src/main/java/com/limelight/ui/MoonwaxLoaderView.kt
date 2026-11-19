package com.limelight.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.utils.UiHelper
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「月盈」连接加载动画。
 *
 * 设计意图：把「正在建立连接」翻译成一枚逐渐变圆的月亮。每完成一段握手，月面亮起一相，
 * 外圈的光弧同步推进；首帧到达前停在满月。它和应用内性能 HUD 的月相标识、首页的
 * MoonWheel 共用同一套月亮隐喻，因此加载页不是一块独立的皮肤，而是 V+ 自己的语言。
 *
 * 性能：单 Canvas 矢量绘制，不使用 RenderEffect / 模糊；一个环境动画器驱动呼吸、星尘与
 * 扫描彗尾，离屏或不可见时自动停止。系统关闭动画时退化为静态一帧，只保留最终状态。
 */
class MoonwaxLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** 未收到任何连接阶段时，月亮保持一枚细月牙，避免空白。 */
        const val IDLE_FRACTION = 0.10f
        const val STAGE_COUNT = 4
        const val STAGE_ANIMATION_MS = 950L
        const val AMBIENT_CYCLE_MS = 12_000L
        const val PULSE_DECAY_MS = 760f
        const val STAR_COUNT = 11

        /** 月面直径占屏幕高度的比例，以及高度下限 / 上限。 */
        const val MOON_HEIGHT_RATIO = 0.27f
        const val MIN_MOON_DP = 100f
        const val MAX_MOON_DP = 184f

        /** View 边长 / 月面直径；留出轨道与光晕的空间。 */
        const val VIEW_PER_DIAMETER = 1.50f
        const val SIDE_PER_RADIUS = VIEW_PER_DIAMETER * 2f
        const val ORBIT_RADIUS_RATIO = 1.30f
        const val HALO_RADIUS_RATIO = 1.47f

        const val TWO_PI = (Math.PI * 2.0).toFloat()
    }

    private val density = resources.displayMetrics.density

    private val moonLight = ContextCompat.getColor(context, R.color.loading_moon_light)
    private val moonWarm = ContextCompat.getColor(context, R.color.loading_moon_warm)
    private val moonCrater = ContextCompat.getColor(context, R.color.loading_moon_crater)

    private var accent = UiHelper.accentColor(context)

    private var radius = 0f
    private var orbitRadius = 0f
    private var illumination = 0f
    private var targetIllumination = IDLE_FRACTION
    private var stageReached = 0
    private var phase = 0f
    private var pulse = 0f
    private var lastFrameTime = 0L

    private var illuminationAnimator: ValueAnimator? = null
    private var ambientAnimator: ValueAnimator? = null

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = 0x24FFFFFF
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val darkSidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    private val orbitRect = RectF()
    private val terminatorRect = RectF()
    private val litPath = Path()
    private val moonClip = Path()

    private val starAngle = FloatArray(STAR_COUNT)
    private val starDistance = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)

    init {
        val random = Random(20261113L)
        for (index in 0 until STAR_COUNT) {
            starAngle[index] = random.nextFloat() * TWO_PI
            starDistance[index] = 1.33f + random.nextFloat() * 0.12f
            starSize[index] = (0.55f + random.nextFloat() * 0.75f) * density
            starPhase[index] = random.nextFloat() * TWO_PI
        }
        setWillNotDraw(false)
    }

    /** 主题强调色变化后重新取色（跟随壁纸取色时使用）。 */
    fun refreshAccentColor() {
        accent = UiHelper.accentColor(context)
        arcPaint.color = accent
        cometPaint.color = accent
        headPaint.color = accent
        pulsePaint.color = accent
        haloPaint.shader = buildHaloShader()
        invalidate()
    }

    /** 重置为「刚开始连接」：细月牙 + 扫描彗尾。 */
    fun resetForShow() {
        stopIlluminationAnimation()
        stopAmbientAnimation()
        stageReached = 0
        illumination = IDLE_FRACTION
        targetIllumination = IDLE_FRACTION
        phase = 0f
        pulse = 0f
        lastFrameTime = 0L
        invalidate()
        updateAnimationState()
    }

    /** 推进到第 [stage] 段（1..4）。只前进不后退，重复 / 乱序的阶段不会让月亮倒退。 */
    fun markStage(stage: Int) {
        if (stage <= stageReached) return
        stageReached = stage.coerceAtMost(STAGE_COUNT)
        pulse = if (animationsEnabled()) 1f else 0f
        animateIlluminationTo(
            if (stageReached >= STAGE_COUNT) 1f else stageReached.toFloat() / STAGE_COUNT
        )
    }

    /** 由外部进度直接驱动（0..1），与阶段推进共用同一条动画曲线。 */
    fun setProgress(fraction: Float) {
        val boundedFraction = fraction.coerceIn(0f, 1f)
        // 百分比回调可能先于协议阶段到达；只按已经完成的比例点亮刻度，
        // 避免月亮还是细月牙时外圈已经显示为满进度。
        stageReached = maxOf(
            stageReached,
            (boundedFraction * STAGE_COUNT).toInt().coerceIn(0, STAGE_COUNT),
        )
        animateIlluminationTo(maxOf(targetIllumination, boundedFraction))
    }

    /** 不确定进度：回到细月牙并让彗尾持续扫描。 */
    fun setIndeterminate(indeterminate: Boolean) {
        if (!indeterminate) return
        stageReached = 0
        animateIlluminationTo(IDLE_FRACTION)
    }

    /** 首帧到达前的一刻：满月。 */
    fun markReady() {
        stageReached = STAGE_COUNT
        pulse = if (animationsEnabled()) 1f else 0f
        animateIlluminationTo(1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenHeightDp = resources.configuration.screenHeightDp.toFloat()
        val diameterDp = (screenHeightDp * MOON_HEIGHT_RATIO)
            .coerceIn(MIN_MOON_DP, MAX_MOON_DP)
        val side = (diameterDp * VIEW_PER_DIAMETER * density).roundToInt()
        setMeasuredDimension(
            resolveSize(side, widthMeasureSpec),
            resolveSize(side, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        radius = side / SIDE_PER_RADIUS
        orbitRadius = radius * ORBIT_RADIUS_RATIO
        orbitRect.set(
            width / 2f - orbitRadius,
            height / 2f - orbitRadius,
            width / 2f + orbitRadius,
            height / 2f + orbitRadius,
        )
        haloPaint.shader = buildHaloShader()
        basePaint.shader = LinearGradient(
            0f, height / 2f - radius, 0f, height / 2f + radius,
            intArrayOf(0xE6241C2C.toInt(), 0xF0171320.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        litPaint.shader = LinearGradient(
            width / 2f - radius, height / 2f - radius,
            width / 2f + radius, height / 2f + radius,
            intArrayOf(moonLight, moonWarm),
            null,
            Shader.TileMode.CLAMP,
        )
        shadePaint.shader = RadialGradient(
            width / 2f - radius * 0.35f,
            height / 2f - radius * 0.42f,
            radius * 1.75f,
            intArrayOf(0x00000000, 0x00000000, 0x45120B1A),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        darkSidePaint.shader = RadialGradient(
            width / 2f - radius * 0.38f,
            height / 2f - radius * 0.50f,
            radius * 1.5f,
            intArrayOf(0x333B3148, 0x12251F2E, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        invalidate()
    }

    private fun buildHaloShader(): Shader? {
        if (radius <= 0f) return null
        return RadialGradient(
            width / 2f,
            height / 2f,
            radius * HALO_RADIUS_RATIO,
            intArrayOf(
                withAlpha(moonLight, 78),
                withAlpha(accent, 38),
                0x00000000,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val phaseRadians = phase * TWO_PI
        val breath = 0.5f + 0.5f * sin(phaseRadians * 2f)
        val fill = illumination.coerceIn(0f, 1f)

        drawHalo(canvas, cx, cy, fill, breath)
        drawStardust(canvas, cx, cy, phaseRadians)
        drawOrbit(canvas, cx, cy, phaseRadians, fill)
        drawMoon(canvas, cx, cy, fill)
        drawPulse(canvas, cx, cy)
    }

    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, fill: Float, breath: Float) {
        haloPaint.alpha = ((0.52f + 0.48f * fill) * (0.9f + 0.1f * breath) * 255f)
            .roundToInt()
            .coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * HALO_RADIUS_RATIO, haloPaint)
    }

    private fun drawStardust(canvas: Canvas, cx: Float, cy: Float, phaseRadians: Float) {
        for (index in 0 until STAR_COUNT) {
            val twinkle = 0.5f + 0.5f * sin(phaseRadians * 3f + starPhase[index])
            starPaint.alpha = (0.06f + 0.24f * twinkle).times(255f).roundToInt()
            val distance = radius * starDistance[index]
            canvas.drawCircle(
                cx + cos(starAngle[index]) * distance,
                cy + sin(starAngle[index]) * distance,
                starSize[index],
                starPaint,
            )
        }
    }

    private fun drawOrbit(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        phaseRadians: Float,
        fill: Float,
    ) {
        canvas.drawCircle(cx, cy, orbitRadius, trackPaint)

        // 4 段边界只画 3 个刻度：12 点方向留给进度弧的起点，避免出现「T」形接缝。
        val tickInner = orbitRadius - 4.5f * density
        val tickOuter = orbitRadius + 2.5f * density
        for (index in 1 until STAGE_COUNT) {
            val angle = Math.toRadians((-90f + index * 90f).toDouble())
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            tickPaint.color = if (index < stageReached) accent else 0x4DFFFFFF
            canvas.drawLine(
                cx + cosAngle * tickInner,
                cy + sinAngle * tickInner,
                cx + cosAngle * tickOuter,
                cy + sinAngle * tickOuter,
                tickPaint,
            )
        }

        if (stageReached == 0 && fill <= IDLE_FRACTION + 0.015f) {
            // 尚未收到阶段：一小段彗尾绕行，表示链路正在扫描。
            val cometStart = -90f + phaseRadians * 180f / Math.PI.toFloat()
            cometPaint.color = withAlpha(accent, 150)
            canvas.drawArc(orbitRect, cometStart, 22f, false, cometPaint)
            val headAngle = Math.toRadians((cometStart + 22f).toDouble())
            headPaint.color = withAlpha(accent, 210)
            canvas.drawCircle(
                cx + cos(headAngle).toFloat() * orbitRadius,
                cy + sin(headAngle).toFloat() * orbitRadius,
                2.4f * density,
                headPaint,
            )
            return
        }

        if (fill > 0.004f) {
            arcGlowPaint.color = withAlpha(accent, 46)
            canvas.drawArc(orbitRect, -90f, 360f * fill, false, arcGlowPaint)
            arcPaint.color = accent
            canvas.drawArc(orbitRect, -90f, 360f * fill, false, arcPaint)
            drawArcHead(canvas, cx, cy, -90f + 360f * fill)
        }
    }

    private fun drawArcHead(canvas: Canvas, cx: Float, cy: Float, angleDegrees: Float) {
        val angle = Math.toRadians(angleDegrees.toDouble())
        val headX = cx + cos(angle).toFloat() * orbitRadius
        val headY = cy + sin(angle).toFloat() * orbitRadius
        headPaint.color = withAlpha(accent, 52)
        canvas.drawCircle(headX, headY, 8.5f * density, headPaint)
        headPaint.color = accent
        canvas.drawCircle(headX, headY, 3.4f * density, headPaint)
        headPaint.color = Color.WHITE
        canvas.drawCircle(headX, headY, 1.5f * density, headPaint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, fill: Float) {
        canvas.drawCircle(cx, cy, radius, basePaint)

        moonClip.reset()
        moonClip.addCircle(cx, cy, radius, Path.Direction.CW)

        // 暗面不是死黑：左上抹一层极淡的紫灰，让玻璃球有厚度。
        canvas.save()
        canvas.clipPath(moonClip)
        canvas.drawCircle(cx, cy, radius, darkSidePaint)
        canvas.restore()

        buildLitPath(cx, cy, fill)
        if (!litPath.isEmpty) {
            canvas.drawPath(litPath, litPaint)

            canvas.save()
            canvas.clipPath(litPath)
            drawCraters(canvas, cx, cy)
            canvas.restore()
        }

        // 玻璃球内部阴影：右下压暗，光源保持在左上。
        canvas.save()
        canvas.clipPath(moonClip)
        shadePaint.alpha = 96
        canvas.drawCircle(cx, cy, radius, shadePaint)
        canvas.restore()

        rimPaint.color = 0x4DFFFFFF
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // 左上镜面高光：一小段弧，模拟玻璃边缘的受光。
        rimPaint.color = 0x8AFFFFFF.toInt()
        rimPaint.strokeWidth = 1.5f * density
        rimPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            cx - radius * 0.92f,
            cy - radius * 0.92f,
            cx + radius * 0.92f,
            cy + radius * 0.92f,
            202f,
            54f,
            false,
            rimPaint,
        )
    }

    /**
     * 构造「上弦向右变圆」的亮面路径：右半圆 + 终止线椭圆。
     * 比例 < 0.5 时终止线向右凸（细月牙），> 0.5 时向左凸（凸月），近似真实月相投影。
     */
    private fun buildLitPath(cx: Float, cy: Float, fill: Float) {
        litPath.reset()
        val fraction = fill.coerceIn(0.004f, 1f)
        litPath.moveTo(cx, cy - radius)
        litPath.arcTo(
            cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, 180f, false,
        )

        val terminatorWidth = radius * abs(1f - 2f * fraction)
        terminatorRect.set(
            cx - terminatorWidth, cy - radius,
            cx + terminatorWidth, cy + radius,
        )
        if (fraction < 0.5f) {
            // 从底部沿椭圆右半回到顶部，形成右侧细月牙。
            litPath.arcTo(terminatorRect, 90f, -180f, false)
        } else {
            // 沿椭圆左半回到顶部，亮面越过中线成为凸月。
            litPath.arcTo(terminatorRect, 90f, 180f, false)
        }
        litPath.close()
    }

    private fun drawCraters(canvas: Canvas, cx: Float, cy: Float) {
        craterPaint.color = withAlpha(moonCrater, 44)
        canvas.drawCircle(cx + radius * 0.36f, cy - radius * 0.22f, radius * 0.11f, craterPaint)
        craterPaint.color = withAlpha(moonCrater, 32)
        canvas.drawCircle(cx + radius * 0.52f, cy + radius * 0.14f, radius * 0.07f, craterPaint)
        craterPaint.color = withAlpha(moonCrater, 38)
        canvas.drawCircle(cx + radius * 0.20f, cy + radius * 0.38f, radius * 0.05f, craterPaint)
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float) {
        if (pulse <= 0f) return
        pulsePaint.color = withAlpha(accent, (150f * pulse).roundToInt())
        val pulseRadius = radius * (1.04f + 0.34f * (1f - pulse))
        canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
    }

    private fun animateIlluminationTo(target: Float) {
        targetIllumination = target.coerceIn(0f, 1f)
        illuminationAnimator?.cancel()
        if (!animationsEnabled()) {
            illumination = targetIllumination
            invalidate()
            return
        }

        val start = illumination
        illuminationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAGE_ANIMATION_MS
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener {
                val animated = it.animatedValue as Float
                illumination = start + (targetIllumination - start) * animated
                invalidate()
            }
            start()
        }
    }

    private fun stopIlluminationAnimation() {
        illuminationAnimator?.cancel()
        illuminationAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        stopAmbientAnimation()
        stopIlluminationAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimationState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimationState()
    }

    private fun updateAnimationState() {
        if (isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE) {
            startAmbientAnimation()
        } else {
            stopAmbientAnimation()
        }
    }

    private fun startAmbientAnimation() {
        if (ambientAnimator?.isRunning == true) return
        if (!animationsEnabled()) {
            invalidate()
            return
        }
        lastFrameTime = 0L
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AMBIENT_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = AnimationUtils.currentAnimationTimeMillis()
                if (lastFrameTime != 0L) {
                    pulse = (pulse - (now - lastFrameTime) / PULSE_DECAY_MS).coerceAtLeast(0f)
                }
                lastFrameTime = now
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAmbientAnimation() {
        ambientAnimator?.cancel()
        ambientAnimator = null
        lastFrameTime = 0L
    }

    private fun animationsEnabled(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }
}
