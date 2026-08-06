package com.limelight.ui

import android.app.Activity
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
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.limelight.R
import kotlin.math.max
import kotlin.math.min

data class ViewFeatureGuideStep(
    val targetProvider: () -> View?,
    val title: String,
    val body: String
) {
    constructor(target: View, title: String, body: String) : this({ target }, title, body)
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
            onCompleted = { store.markCompleted(spec) }
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

    /**
     * Waits for real, visible targets instead of relying on a device-specific startup delay.
     * A snoozed guide is not retried until its page schedules it again on a future visit.
     */
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

            override fun onViewDetachedFromWindow(view: View) {
                stop()
            }

            private fun stop() {
                content.removeCallbacks(this)
                content.removeOnAttachStateChangeListener(this)
            }
        }
        content.addOnAttachStateChangeListener(attempt)
        content.post(attempt)
    }
}

private class FeatureGuideOverlay(
    private val activity: Activity,
    private val steps: List<ViewFeatureGuideStep>,
    private val onCompleted: () -> Unit
) : View(activity) {
    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(activity, R.color.game_menu_accent)
    private val ink = Color.rgb(64, 58, 58)
    private val mutedInk = Color.rgb(92, 82, 82)
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
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
    private val paperBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(216, 202, 188)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val eyebrowPaint = textPaint(12f, accent, true)
    private val titlePaint = textPaint(22f, ink, true)
    private val bodyPaint = textPaint(17f, mutedInk, false)
    private val actionPaint = textPaint(16f, accent, true)
    private val skipPaint = textPaint(16f, mutedInk, true)
    private val highlightRect = RectF()
    private val cardRect = RectF()
    private val contentViewport = RectF()
    private val skipRect = RectF()
    private val actionRect = RectF()
    private var currentIndex = 0
    private var contentScrollOffset = 0f
    private var contentScrollMax = 0f
    private var lastTouchY = 0f
    private var touchStartedInContent = false
    private var isDraggingContent = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = when {
            skipRect.contains(x, y) -> VIRTUAL_SKIP
            actionRect.contains(x, y) -> VIRTUAL_ACTION
            else -> INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds += VIRTUAL_SKIP
            virtualViewIds += VIRTUAL_ACTION
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            event.contentDescription = labelFor(virtualViewId)
        }

        @Suppress("DEPRECATION")
        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            node.className = Button::class.java.name
            node.contentDescription = labelFor(virtualViewId)
            node.isClickable = true
            node.isFocusable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val bounds = if (virtualViewId == VIRTUAL_SKIP) skipRect else actionRect
            node.setBoundsInParent(
                Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt()
                )
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            return when (virtualViewId) {
                VIRTUAL_SKIP -> {
                    dismiss(completed = false)
                    true
                }
                VIRTUAL_ACTION -> {
                    performPrimaryAction()
                    true
                }
                else -> false
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        contentDescription = steps.first().title
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!updateTargetRect()) {
            dismiss(completed = false)
            return
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRoundRect(highlightRect, dp(14f), dp(14f), clearPaint)
        canvas.restoreToCount(layer)

        borderPaint.alpha = 225
        canvas.drawRoundRect(highlightRect, dp(14f), dp(14f), borderPaint)
        val echo = RectF(highlightRect).apply { inset(-dp(3f), -dp(2f)) }
        borderPaint.alpha = 90
        canvas.drawRoundRect(echo, dp(16f), dp(16f), borderPaint)
        borderPaint.alpha = 255

        drawCard(canvas)
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

    private fun drawCard(canvas: Canvas) {
        val step = steps[currentIndex]
        val edge = dp(16f)
        val cardWidth = min(dp(316f), width - edge * 2f)
        val innerWidth = (cardWidth - dp(48f)).toInt()
        val bodyLayout = staticLayout(step.body, bodyPaint, innerWidth)
        val titleLayout = staticLayout(step.title, titlePaint, innerWidth)
        val naturalCardHeight = dp(40f) + titleLayout.height + dp(13f) +
            bodyLayout.height + dp(22f) + dp(46f)
        val availableCardHeight = (height - edge * 2f).coerceAtLeast(1f)
        val cardHeight = min(naturalCardHeight, availableCardHeight)

        val preferredLeft = highlightRect.centerX() - cardWidth / 2f
        val left = preferredLeft.coerceIn(edge, max(edge, width - edge - cardWidth))
        val belowTop = highlightRect.bottom + dp(64f)
        val aboveTop = highlightRect.top - dp(64f) - cardHeight
        val unconstrainedTop = when {
            belowTop + cardHeight <= height - edge -> belowTop
            aboveTop >= edge -> aboveTop
            else -> ((height - cardHeight) / 2f).coerceAtLeast(edge)
        }
        val top = unconstrainedTop.coerceIn(
            edge,
            (height - edge - cardHeight).coerceAtLeast(edge)
        )
        cardRect.set(left, top, left + cardWidth, top + cardHeight)

        canvas.save()
        drawLeader(canvas)
        val paperPath = paperPath(cardRect)
        paperPaint.setShadowLayer(dp(5f), 0f, dp(2f), Color.argb(72, 0, 0, 0))
        canvas.drawPath(paperPath, paperPaint)
        paperPaint.clearShadowLayer()
        canvas.drawPath(paperPath, paperBorderPaint)
        drawTape(canvas)

        val textLeft = cardRect.left + dp(24f)
        val buttonTop = cardRect.bottom - dp(50f)
        contentViewport.set(
            textLeft,
            cardRect.top + dp(18f),
            cardRect.right - dp(24f),
            (buttonTop - dp(4f)).coerceAtLeast(cardRect.top + dp(18f))
        )
        val contentHeight = dp(19f) + titleLayout.height + dp(13f) + bodyLayout.height
        contentScrollMax = (contentHeight - contentViewport.height()).coerceAtLeast(0f)
        contentScrollOffset = contentScrollOffset.coerceIn(0f, contentScrollMax)

        canvas.save()
        canvas.clipRect(contentViewport)
        var y = cardRect.top + dp(30f) - contentScrollOffset
        val eyebrow = activity.getString(R.string.feature_guide_step, currentIndex + 1, steps.size)
        canvas.drawText(eyebrow, textLeft, y, eyebrowPaint)
        y += dp(19f)

        canvas.save()
        canvas.translate(textLeft, y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height
        drawUnderline(canvas, textLeft, y + dp(5f), cardRect.right - dp(24f))
        y += dp(13f)

        canvas.save()
        canvas.translate(textLeft, y)
        bodyLayout.draw(canvas)
        canvas.restore()
        canvas.restore()

        val actionLabel = activity.getString(
            if (currentIndex == steps.lastIndex) R.string.feature_guide_done else R.string.feature_guide_next
        )
        val skipLabel = activity.getString(R.string.feature_guide_skip)
        val actionWidth = actionPaint.measureText(actionLabel) + dp(24f)
        val skipWidth = skipPaint.measureText(skipLabel) + dp(24f)
        actionRect.set(cardRect.right - dp(12f) - actionWidth, buttonTop, cardRect.right - dp(12f), cardRect.bottom - dp(5f))
        skipRect.set(actionRect.left - skipWidth, buttonTop, actionRect.left, cardRect.bottom - dp(5f))
        val dividerX = actionRect.left - dp(2f)
        canvas.drawLine(dividerX, buttonTop + dp(10f), dividerX, buttonTop + dp(34f), paperBorderPaint)
        canvas.drawText(skipLabel, skipRect.centerX() - skipPaint.measureText(skipLabel) / 2f, buttonTop + dp(28f), skipPaint)
        canvas.drawText(actionLabel, actionRect.centerX() - actionPaint.measureText(actionLabel) / 2f, buttonTop + dp(28f), actionPaint)
        canvas.restore()
    }

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

    private fun drawLeader(canvas: Canvas) {
        val cardBelow = cardRect.top > highlightRect.bottom
        val startX = highlightRect.centerX()
        val startY = if (cardBelow) highlightRect.bottom + dp(3f) else highlightRect.top - dp(3f)
        val endX = cardRect.left + dp(76f)
        val endY = if (cardBelow) cardRect.top - dp(7f) else cardRect.bottom + dp(7f)
        val direction = if (cardBelow) 1f else -1f
        val verticalDistance = kotlin.math.abs(endY - startY)
        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(
                startX, startY + max(dp(28f), verticalDistance * 0.58f) * direction,
                endX + dp(42f), endY - dp(22f) * direction,
                endX, endY
            )
        }
        val leaderPaint = Paint(borderPaint).apply {
            strokeWidth = dp(2.1f)
            pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
            alpha = 235
        }
        val pathMeasure = PathMeasure(path, false)
        val visibleLeader = Path()
        val sourceGap = dp(12f)
        // The arrow wings extend backwards along the curve. Trim far enough
        // that a visible pocket of air remains between the last dash and them.
        val arrowGap = dp(21f)
        if (pathMeasure.length > sourceGap + arrowGap) {
            pathMeasure.getSegment(sourceGap, pathMeasure.length - arrowGap, visibleLeader, true)
        }
        canvas.drawPath(visibleLeader, leaderPaint)
        leaderPaint.pathEffect = null
        leaderPaint.style = Paint.Style.FILL
        // Keep both wings symmetrical around the final curve tangent so the
        // arrow reads as the natural end of the dashed leader, not a tick mark.
        val backX = 0.886f
        val backY = -0.464f * direction
        val sideX = 0.464f * direction
        val sideY = 0.886f
        val wingLength = dp(13f)
        val wingSpread = dp(5.5f)
        val arrow = Path().apply {
            moveTo(endX + backX * wingLength + sideX * wingSpread, endY + backY * wingLength + sideY * wingSpread)
            lineTo(endX, endY)
            lineTo(endX + backX * wingLength - sideX * wingSpread, endY + backY * wingLength - sideY * wingSpread)
        }
        leaderPaint.style = Paint.Style.STROKE
        leaderPaint.strokeCap = Paint.Cap.ROUND
        leaderPaint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrow, leaderPaint)
    }

    private fun drawTape(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-9f, cardRect.left + dp(28f), cardRect.top + dp(5f))
        val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 151, 143)
            alpha = 232
        }
        val tapeRect = RectF(
            cardRect.left + dp(4f),
            cardRect.top - dp(7f),
            cardRect.left + dp(54f),
            cardRect.top + dp(11f)
        )
        canvas.drawRoundRect(tapeRect, dp(2f), dp(2f), tape)
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 255, 255, 255)
            strokeWidth = dp(1f)
        }
        var x = tapeRect.left + dp(5f)
        while (x < tapeRect.right) {
            canvas.drawLine(x, tapeRect.top + dp(3f), x + dp(5f), tapeRect.bottom - dp(3f), stripe)
            x += dp(8f)
        }
        canvas.restore()
    }

    private fun drawUnderline(canvas: Canvas, left: Float, top: Float, right: Float) {
        val paint = Paint(borderPaint).apply { strokeWidth = dp(1.8f); alpha = 210 }
        val lineWidth = right - left
        val path = Path().apply {
            moveTo(left, top)
            cubicTo(left + lineWidth * 0.22f, top - dp(2f), left + lineWidth * 0.45f, top + dp(2f), left + lineWidth * 0.66f, top)
            cubicTo(left + lineWidth * 0.79f, top - dp(1.5f), left + lineWidth * 0.91f, top + dp(1.5f), right, top - dp(0.5f))
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 110
        canvas.drawCircle(right - dp(6f), top + dp(4f), dp(1.4f), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                touchStartedInContent = contentViewport.contains(event.x, event.y)
                isDraggingContent = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchStartedInContent && contentScrollMax > 0f) {
                    val delta = lastTouchY - event.y
                    if (kotlin.math.abs(delta) >= touchSlop || isDraggingContent) {
                        isDraggingContent = true
                        contentScrollOffset = (contentScrollOffset + delta)
                            .coerceIn(0f, contentScrollMax)
                        invalidate()
                    }
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDraggingContent) {
                    when {
                        actionRect.contains(event.x, event.y) -> performPrimaryAction()
                        skipRect.contains(event.x, event.y) -> dismiss(completed = false)
                    }
                }
                touchStartedInContent = false
                isDraggingContent = false
            }
            MotionEvent.ACTION_CANCEL -> {
                touchStartedInContent = false
                isDraggingContent = false
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun performPrimaryAction() {
        if (currentIndex == steps.lastIndex) {
            dismiss(completed = true)
        } else {
            currentIndex++
            contentScrollOffset = 0f
            contentDescription = steps[currentIndex].title
            accessibilityHelper.invalidateRoot()
            invalidate()
        }
    }

    private fun labelFor(virtualViewId: Int): String = when (virtualViewId) {
        VIRTUAL_SKIP -> activity.getString(R.string.feature_guide_skip)
        else -> activity.getString(
            if (currentIndex == steps.lastIndex) R.string.feature_guide_done
            else R.string.feature_guide_next
        )
    }

    private fun dismiss(completed: Boolean) {
        if (completed) onCompleted()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun textPaint(sp: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        this.color = color
        typeface = android.graphics.Typeface.create(
            "sans-serif-rounded",
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
    }

    @Suppress("DEPRECATION")
    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.08f)
                .build()
        } else {
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.08f,
                0f,
                false
            )
        }
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val VIRTUAL_SKIP = 1
        const val VIRTUAL_ACTION = 2
    }
}
