package com.limelight.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ViewFeatureGuideStep(
    val targetProvider: () -> View?,
    val title: String,
    val body: String
) {
    constructor(target: View, title: String, body: String) : this({ target }, title, body)
}

internal enum class FeatureGuideCardSide {
    RIGHT,
    LEFT,
    BELOW,
    ABOVE,
    CENTER
}

internal data class FeatureGuideCardPlacement(
    val left: Float,
    val top: Float,
    val side: FeatureGuideCardSide
)

internal fun calculateFeatureGuideCardPlacement(
    overlayWidth: Float,
    overlayHeight: Float,
    targetLeft: Float,
    targetTop: Float,
    targetRight: Float,
    targetBottom: Float,
    cardWidth: Float,
    cardHeight: Float,
    edge: Float,
    gap: Float,
    safeLeft: Float = 0f,
    safeTop: Float = 0f,
    safeRight: Float = 0f,
    safeBottom: Float = 0f
): FeatureGuideCardPlacement {
    val minLeft = safeLeft + edge
    val minTop = safeTop + edge
    val maxRight = overlayWidth - safeRight - edge
    val maxBottom = overlayHeight - safeBottom - edge
    val maxLeft = max(minLeft, maxRight - cardWidth)
    val maxTop = max(minTop, maxBottom - cardHeight)
    val centeredTop = ((targetTop + targetBottom - cardHeight) / 2f).coerceIn(minTop, maxTop)
    val centeredLeft = ((targetLeft + targetRight - cardWidth) / 2f).coerceIn(minLeft, maxLeft)

    val rightLeft = targetRight + gap
    if (rightLeft + cardWidth <= maxRight) {
        return FeatureGuideCardPlacement(rightLeft, centeredTop, FeatureGuideCardSide.RIGHT)
    }

    val leftLeft = targetLeft - gap - cardWidth
    if (leftLeft >= minLeft) {
        return FeatureGuideCardPlacement(leftLeft, centeredTop, FeatureGuideCardSide.LEFT)
    }

    val belowTop = targetBottom + gap
    if (belowTop + cardHeight <= maxBottom) {
        return FeatureGuideCardPlacement(centeredLeft, belowTop, FeatureGuideCardSide.BELOW)
    }

    val aboveTop = targetTop - gap - cardHeight
    if (aboveTop >= minTop) {
        return FeatureGuideCardPlacement(centeredLeft, aboveTop, FeatureGuideCardSide.ABOVE)
    }

    return FeatureGuideCardPlacement(
        left = ((minLeft + maxRight - cardWidth) / 2f).coerceIn(minLeft, maxLeft),
        top = ((minTop + maxBottom - cardHeight) / 2f).coerceIn(minTop, maxTop),
        side = FeatureGuideCardSide.CENTER
    )
}

object ViewFeatureGuide {
    private const val OVERLAY_TAG = "moonlight_view_feature_guide"
    private const val DEFAULT_READY_TIMEOUT_MS = 5_000L
    private const val READY_RETRY_MS = 120L

    fun show(
        activity: Activity,
        spec: FeatureGuideSpec,
        steps: List<ViewFeatureGuideStep>
    ): Boolean {
        if (steps.isEmpty() || activity.isFinishing || activity.isDestroyed) return false
        val store = FeatureGuideStore(activity)
        if (!store.shouldShow(spec)) return false

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content.findViewWithTag<View>(OVERLAY_TAG) != null) return false

        val visibleSteps = steps.filter {
            val target = it.targetProvider()
            target != null && target.isShown && target.width > 0 && target.height > 0
        }
        if (visibleSteps.isEmpty()) return false

        val overlay = FeatureGuideOverlay(
            activity = activity,
            steps = visibleSteps,
            onRemembered = { store.markCompleted(spec) }
        ).apply { tag = OVERLAY_TAG }
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return true
    }

    /** Wait for a real visible target instead of relying on a startup delay. */
    fun showWhenReady(
        activity: Activity,
        spec: FeatureGuideSpec,
        timeoutMillis: Long = DEFAULT_READY_TIMEOUT_MS,
        stepsProvider: () -> List<ViewFeatureGuideStep>
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val store = FeatureGuideStore(activity)
        if (!store.shouldShow(spec)) return

        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        val attempt = object : Runnable, View.OnAttachStateChangeListener {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed || !store.shouldShow(spec)) {
                    stop()
                    return
                }
                if (activity.hasWindowFocus() && show(activity, spec, stepsProvider())) {
                    stop()
                    return
                }
                if (SystemClock.uptimeMillis() < deadline) {
                    content.postDelayed(this, READY_RETRY_MS)
                } else {
                    stop()
                }
            }

            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) = stop()

            private fun stop() {
                content.removeCallbacks(this)
                content.removeOnAttachStateChangeListener(this)
            }
        }
        content.addOnAttachStateChangeListener(attempt)
        content.post(attempt)
    }
}

/**
 * Only the hand-drawn decoration lives on Canvas. Text, scrolling and actions are
 * ordinary Android views so sizing, keyboard focus and accessibility stay native.
 */
@SuppressLint("ViewConstructor")
private class FeatureGuideOverlay(
    private val activity: Activity,
    private val steps: List<ViewFeatureGuideStep>,
    private val onRemembered: () -> Unit
) : FrameLayout(activity) {
    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(activity, R.color.game_menu_accent)
    private val ink = Color.rgb(76, 67, 70)
    private val mutedInk = Color.rgb(108, 96, 99)
    private val paper = Color.rgb(255, 248, 232)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(184, 0, 0, 0) }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val leaderPaint = Paint(borderPaint).apply {
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
    private val paperBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(216, 202, 188)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 151, 143)
        alpha = 232
    }
    private val tapeStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val highlightRect = RectF()
    private val highlightEchoRect = RectF()
    private val cardRect = RectF()
    private var cardSide = FeatureGuideCardSide.CENTER
    private var safeLeft = 0f
    private var safeTop = 0f
    private var safeRight = 0f
    private var safeBottom = 0f
    private val previousFocus = activity.currentFocus
    private val backCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OnBackInvokedCallback { dismiss(rememberChoice = true) }
    } else null
    private var currentIndex = 0
    private var dismissScheduled = false
    private var dismissed = false
    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (!dismissed && newFocus != null && !containsGuideFocus(newFocus)) {
            action.post { requestGuideFocusIfOutside() }
        }
    }

    private val card = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = false
    }
    private val contentColumn = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpInt(24f), dpInt(12f), dpInt(24f), dpInt(8f))
    }
    private val eyebrow = label(11f, accent, true).apply {
        letterSpacing = 0.06f
    }
    private val title = label(21f, ink, false).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.025f
        setLineSpacing(dp(1f), 1.04f)
    }
    private val body = label(16f, mutedInk, false).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.018f
        setLineSpacing(dp(2f), 1.12f)
    }
    private val scroll = ScrollView(activity).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            contentColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }
    private val skip = actionLabel(mutedInk) { dismiss(rememberChoice = true) }
    private val action = actionLabel(accent) { performPrimaryAction() }
    private val actions = LinearLayout(activity).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dpInt(12f), 0, dpInt(12f), dpInt(5f))
        addView(skip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(View(activity).apply { setBackgroundColor(Color.rgb(216, 202, 188)) },
            LinearLayout.LayoutParams(dpInt(1f), dpInt(24f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dpInt(2f)
                marginEnd = dpInt(2f)
            })
        addView(action, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                backCallback!!
            )
        }

        skip.id = View.generateViewId()
        action.id = View.generateViewId()
        skip.nextFocusLeftId = skip.id
        skip.nextFocusRightId = action.id
        skip.nextFocusUpId = skip.id
        skip.nextFocusDownId = skip.id
        action.nextFocusLeftId = skip.id
        action.nextFocusRightId = action.id
        action.nextFocusUpId = action.id
        action.nextFocusDownId = action.id

        contentColumn.addView(eyebrow)
        contentColumn.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpInt(3f)
        })
        contentColumn.addView(HandDrawnUnderline(activity, accent),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpInt(13f)))
        contentColumn.addView(body)

        card.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dpInt(ACTION_HEIGHT_DP)
        })
        card.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, dpInt(ACTION_HEIGHT_DP), Gravity.BOTTOM))
        addView(card)
        updateContent()
        action.post { action.requestFocus() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
        post { requestGuideFocusIfOutside() }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalFocusChangeListener(globalFocusListener)
        }
        super.onDetachedFromWindow()
    }

    private fun requestGuideFocusIfOutside() {
        if (!dismissed && isAttachedToWindow && !containsGuideFocus(activity.currentFocus)) {
            action.requestFocus()
        }
    }

    private fun containsGuideFocus(view: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val overlayWidth = MeasureSpec.getSize(widthMeasureSpec)
        val overlayHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(overlayWidth, overlayHeight)

        updateSafeInsets()
        val edge = dp(16f)
        val safeWidth = overlayWidth - safeLeft - safeRight - edge * 2f
        val cardWidth = min(dp(316f), safeWidth).toInt().coerceAtLeast(1)
        contentColumn.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val naturalHeight = contentColumn.measuredHeight + dpInt(ACTION_HEIGHT_DP)
        val availableHeight = (
            overlayHeight - safeTop - safeBottom - edge * 2f
        ).toInt().coerceAtLeast(1)
        val cardHeight = min(naturalHeight, availableHeight).coerceAtLeast(min(dpInt(150f), availableHeight))
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!updateTargetRect()) {
            scheduleDismiss()
            return
        }
        val cardWidth = card.measuredWidth.toFloat()
        val cardHeight = card.measuredHeight.toFloat()
        val placement = calculateFeatureGuideCardPlacement(
            overlayWidth = width.toFloat(),
            overlayHeight = height.toFloat(),
            targetLeft = highlightRect.left,
            targetTop = highlightRect.top,
            targetRight = highlightRect.right,
            targetBottom = highlightRect.bottom,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            edge = dp(16f),
            gap = dp(64f),
            safeLeft = safeLeft,
            safeTop = safeTop,
            safeRight = safeRight,
            safeBottom = safeBottom
        )
        cardSide = placement.side
        card.layout(
            placement.left.toInt(),
            placement.top.toInt(),
            (placement.left + cardWidth).toInt(),
            (placement.top + cardHeight).toInt()
        )
        cardRect.set(card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!updateTargetRect()) {
            scheduleDismiss()
            return
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(highlightRect, dp(14f), dp(14f), clearPaint)
        canvas.restoreToCount(layer)

        borderPaint.alpha = 225
        canvas.drawRoundRect(highlightRect, dp(14f), dp(14f), borderPaint)
        highlightEchoRect.set(highlightRect)
        highlightEchoRect.inset(-dp(3f), -dp(2f))
        borderPaint.alpha = 90
        canvas.drawRoundRect(highlightEchoRect, dp(16f), dp(16f), borderPaint)
        borderPaint.alpha = 255

        drawLeader(canvas)
        val paperPath = paperPath(cardRect)
        paperPaint.setShadowLayer(dp(5f), 0f, dp(2f), Color.argb(72, 0, 0, 0))
        canvas.drawPath(paperPath, paperPaint)
        paperPaint.clearShadowLayer()
        canvas.drawPath(paperPath, paperBorderPaint)
        drawTape(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (UiDismissKeyHandler.handle(event.action, event.keyCode) {
                dismiss(rememberChoice = true)
            }
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateTargetRect(): Boolean {
        val target = steps.getOrNull(currentIndex)?.targetProvider?.invoke() ?: return false
        if (!target.isShown) return false
        val targetBounds = Rect()
        val ownBounds = Rect()
        if (!target.getGlobalVisibleRect(targetBounds) || !getGlobalVisibleRect(ownBounds)) return false
        val margin = dp(8f)
        highlightRect.set(
            targetBounds.left - ownBounds.left - margin,
            targetBounds.top - ownBounds.top - margin,
            targetBounds.right - ownBounds.left + margin,
            targetBounds.bottom - ownBounds.top + margin
        )
        highlightRect.left = highlightRect.left.coerceAtLeast(dp(6f))
        highlightRect.top = highlightRect.top.coerceAtLeast(dp(6f))
        highlightRect.right = highlightRect.right.coerceAtMost(width - dp(6f))
        highlightRect.bottom = highlightRect.bottom.coerceAtMost(height - dp(6f))
        return true
    }

    private fun updateSafeInsets() {
        val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        safeLeft = insets?.left?.toFloat() ?: 0f
        safeTop = insets?.top?.toFloat() ?: 0f
        safeRight = insets?.right?.toFloat() ?: 0f
        safeBottom = insets?.bottom?.toFloat() ?: 0f
    }

    private fun performPrimaryAction() {
        if (currentIndex == steps.lastIndex) {
            dismiss(rememberChoice = true)
        } else {
            currentIndex++
            scroll.scrollTo(0, 0)
            updateContent()
            requestLayout()
            invalidate()
            action.post { action.requestFocus() }
        }
    }

    private fun updateContent() {
        val step = steps[currentIndex]
        eyebrow.text = activity.getString(R.string.feature_guide_step, currentIndex + 1, steps.size)
        title.text = step.title
        body.text = step.body
        skip.text = activity.getString(R.string.feature_guide_skip)
        action.text = activity.getString(
            if (currentIndex == steps.lastIndex) R.string.feature_guide_done else R.string.feature_guide_next
        )
    }

    private fun drawLeader(canvas: Canvas) {
        val cardInset = dp(24f)
        val (startX, startY, endX, endY, horizontal) = when (cardSide) {
            FeatureGuideCardSide.RIGHT -> LeaderGeometry(
                highlightRect.right + dp(3f),
                highlightRect.centerY(),
                cardRect.left - dp(7f),
                highlightRect.centerY().coerceIn(cardRect.top + cardInset, cardRect.bottom - cardInset),
                true
            )
            FeatureGuideCardSide.LEFT -> LeaderGeometry(
                highlightRect.left - dp(3f),
                highlightRect.centerY(),
                cardRect.right + dp(7f),
                highlightRect.centerY().coerceIn(cardRect.top + cardInset, cardRect.bottom - cardInset),
                true
            )
            FeatureGuideCardSide.BELOW -> LeaderGeometry(
                highlightRect.centerX(),
                highlightRect.bottom + dp(3f),
                highlightRect.centerX().coerceIn(cardRect.left + cardInset, cardRect.right - cardInset),
                cardRect.top - dp(7f),
                false
            )
            FeatureGuideCardSide.ABOVE -> LeaderGeometry(
                highlightRect.centerX(),
                highlightRect.top - dp(3f),
                highlightRect.centerX().coerceIn(cardRect.left + cardInset, cardRect.right - cardInset),
                cardRect.bottom + dp(7f),
                false
            )
            FeatureGuideCardSide.CENTER -> {
                val horizontalFallback = kotlin.math.abs(cardRect.centerX() - highlightRect.centerX()) >=
                    kotlin.math.abs(cardRect.centerY() - highlightRect.centerY())
                if (horizontalFallback && cardRect.centerX() >= highlightRect.centerX()) {
                    LeaderGeometry(highlightRect.right, highlightRect.centerY(), cardRect.left, cardRect.centerY(), true)
                } else if (horizontalFallback) {
                    LeaderGeometry(highlightRect.left, highlightRect.centerY(), cardRect.right, cardRect.centerY(), true)
                } else if (cardRect.centerY() >= highlightRect.centerY()) {
                    LeaderGeometry(highlightRect.centerX(), highlightRect.bottom, cardRect.centerX(), cardRect.top, false)
                } else {
                    LeaderGeometry(highlightRect.centerX(), highlightRect.top, cardRect.centerX(), cardRect.bottom, false)
                }
            }
        }
        val path = Path().apply {
            moveTo(startX, startY)
            if (horizontal) {
                val middleX = (startX + endX) / 2f
                cubicTo(middleX, startY, middleX, endY, endX, endY)
            } else {
                val middleY = (startY + endY) / 2f
                cubicTo(startX, middleY, endX, middleY, endX, endY)
            }
        }
        val pathMeasure = PathMeasure(path, false)
        val visibleLeader = Path()
        val sourceGap = dp(12f)
        val arrowGap = dp(21f)
        if (pathMeasure.length > sourceGap + arrowGap) {
            pathMeasure.getSegment(sourceGap, pathMeasure.length - arrowGap, visibleLeader, true)
        }
        leaderPaint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
        leaderPaint.style = Paint.Style.STROKE
        canvas.drawPath(visibleLeader, leaderPaint)
        leaderPaint.pathEffect = null

        val position = FloatArray(2)
        val tangent = FloatArray(2)
        pathMeasure.getPosTan(pathMeasure.length, position, tangent)
        val magnitude = hypot(tangent[0], tangent[1]).coerceAtLeast(0.001f)
        val backX = -tangent[0] / magnitude
        val backY = -tangent[1] / magnitude
        val sideX = -backY
        val sideY = backX
        val wingLength = dp(13f)
        val wingSpread = dp(5.5f)
        val arrow = Path().apply {
            moveTo(endX + backX * wingLength + sideX * wingSpread, endY + backY * wingLength + sideY * wingSpread)
            lineTo(endX, endY)
            lineTo(endX + backX * wingLength - sideX * wingSpread, endY + backY * wingLength - sideY * wingSpread)
        }
        canvas.drawPath(arrow, leaderPaint)
    }

    private data class LeaderGeometry(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val horizontal: Boolean
    )

    private fun paperPath(rect: RectF): Path {
        val wobble = dp(2f)
        return Path().apply {
            moveTo(rect.left + dp(7f), rect.top + wobble)
            lineTo(rect.left + rect.width() * 0.22f, rect.top)
            lineTo(rect.left + rect.width() * 0.48f, rect.top + wobble)
            lineTo(rect.left + rect.width() * 0.74f, rect.top - dp(1f))
            lineTo(rect.right - dp(7f), rect.top + wobble)
            quadTo(rect.right + dp(1f), rect.top + dp(8f), rect.right - dp(1f), rect.top + dp(16f))
            lineTo(rect.right + dp(1f), rect.bottom - dp(9f))
            quadTo(rect.right - dp(2f), rect.bottom + dp(2f), rect.right - dp(12f), rect.bottom)
            lineTo(rect.left + rect.width() * 0.72f, rect.bottom + dp(1f))
            lineTo(rect.left + rect.width() * 0.46f, rect.bottom - dp(1f))
            lineTo(rect.left + rect.width() * 0.20f, rect.bottom + dp(2f))
            lineTo(rect.left + dp(7f), rect.bottom)
            quadTo(rect.left - dp(1f), rect.bottom - dp(6f), rect.left + dp(1f), rect.bottom - dp(15f))
            lineTo(rect.left - dp(1f), rect.top + dp(11f))
            quadTo(rect.left + dp(1f), rect.top + dp(4f), rect.left + dp(7f), rect.top + wobble)
            close()
        }
    }

    private fun drawTape(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-9f, cardRect.left + dp(28f), cardRect.top + dp(5f))
        val tapeRect = RectF(
            cardRect.left + dp(4f), cardRect.top - dp(7f),
            cardRect.left + dp(54f), cardRect.top + dp(11f)
        )
        canvas.drawRoundRect(tapeRect, dp(2f), dp(2f), tapePaint)
        var x = tapeRect.left + dp(5f)
        while (x < tapeRect.right) {
            canvas.drawLine(x, tapeRect.top + dp(3f), x + dp(5f), tapeRect.bottom - dp(3f), tapeStripePaint)
            x += dp(8f)
        }
        canvas.restore()
    }

    private fun dismiss(rememberChoice: Boolean) {
        if (dismissed) return
        dismissed = true
        if (rememberChoice) onRemembered()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        (parent as? ViewGroup)?.removeView(this)
        previousFocus?.post {
            if (previousFocus.isShown) previousFocus.requestFocus()
        }
    }

    private fun scheduleDismiss() {
        if (dismissScheduled) return
        dismissScheduled = true
        post { dismiss(rememberChoice = false) }
    }

    private fun label(sp: Float, color: Int, bold: Boolean) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-rounded", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun actionLabel(color: Int, onClick: () -> Unit) = label(15f, color, true).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.035f
        gravity = Gravity.CENTER
        minHeight = dpInt(45f)
        setPadding(dpInt(12f), 0, dpInt(12f), 0)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        background = selectableBackground()
        setOnClickListener { onClick() }
        setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BUTTON_A) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_UP) onClick()
                true
            }
        }
    }

    private fun selectableBackground(): Drawable? {
        return ContextCompat.getDrawable(activity, R.drawable.feature_guide_action_bg)
    }

    private fun dp(value: Float): Float = value * density
    private fun dpInt(value: Float): Int = dp(value).toInt()

    private companion object {
        const val ACTION_HEIGHT_DP = 50f
    }
}

@SuppressLint("ViewConstructor")
private class HandDrawnUnderline(context: Context, color: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        alpha = 210
    }

    override fun onDraw(canvas: Canvas) {
        val y = height * 0.45f
        val right = width.toFloat()
        path.reset()
        path.moveTo(0f, y)
        path.cubicTo(right * 0.22f, y - 2f * density, right * 0.45f, y + 2f * density, right * 0.66f, y)
        path.cubicTo(right * 0.79f, y - 1.5f * density, right * 0.91f, y + 1.5f * density, right, y - 0.5f * density)
        canvas.drawPath(path, paint)
    }
}
