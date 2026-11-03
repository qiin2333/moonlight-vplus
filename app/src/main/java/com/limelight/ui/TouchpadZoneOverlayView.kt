package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.binding.input.touchpad.TouchpadZoneGeometry
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.UiHelper
import kotlin.math.max

/**
 * Reusable overlay for a screen touchpad surface: draws the zone outline, pad
 * contact dots and the activation hint. Idle is fully transparent; screen interaction,
 * explicit reveal and resizing make the surface visible temporarily.
 * Non-interactive — gesture routing lives in the caller (TouchInputHandler), which
 * pushes state in through the setters. Coordinates are normalized to the stream
 * content; [coordinateViewProvider] must return the view holding that content so
 * contacts map onto the (possibly letterboxed or zoomed) stream.
 */
class TouchpadZoneOverlayView(
    context: Context,
    private val coordinateViewProvider: () -> View?,
) : View(context) {

    private data class Contact(
        var x: Float,
        var y: Float,
        var releasedAt: Long = 0L,
    )

    private val density = resources.displayMetrics.density
    private val contacts = LinkedHashMap<Int, Contact>(2)

    private val contactFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.game_menu_card_background)
        alpha = 245
        style = Paint.Style.FILL
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.game_menu_text_primary)
        textSize = sp(13f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.game_menu_text_secondary)
        textSize = sp(11f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val zoneOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    // A narrow, translucent backing keeps the fine border visible on bright content.
    private val zoneOutlineInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
    }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val zoneBounds = RectF()

    private var introStartedAt = 0L
    private var touchpadPressed = false
    private var singleTapToClick = false
    private var resizing = false
    private var screenTouchActive = false

    /** Zone surface in stream-normalized coordinates; null when the pad is off. */
    private var zoneRect: TouchpadZoneGeometry.NormalizedRect? = null
    private var lastInteractionAt = 0L
    private var zoneRevealUntil = 0L
    private val scratchPoint = FloatArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        elevation = 6f * density
    }

    fun showActivatedHint() {
        introStartedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /**
     * Draw the zone outline prominently for a few seconds so the user can see where
     * the pad surface is right after enabling or resizing it; idle hides it afterward.
     */
    fun showZoneReveal() {
        zoneRevealUntil = SystemClock.uptimeMillis() + ZONE_REVEAL_MS
        postInvalidateOnAnimation()
    }

    /** Check before routing DOWN: this touch must not reveal its own resize target. */
    fun canStartResize(): Boolean = zoneRect != null && zoneFade(SystemClock.uptimeMillis()) > 0f

    fun setScreenTouchActive(active: Boolean) {
        if (screenTouchActive == active) return
        screenTouchActive = active
        if (!active) lastInteractionAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun zoneFade(now: Long): Float {
        val interactionFade = if (lastInteractionAt == 0L) 0f else
            (1f - (now - lastInteractionAt).toFloat() / CONTACT_FADE_MS).coerceIn(0f, 1f)
        val revealFade = ((zoneRevealUntil - now).toFloat() / CONTACT_FADE_MS).coerceIn(0f, 1f)
        return max(interactionFade, revealFade)
    }

    /** Show zone chrome for the given zone; pass null when the pad is disabled. */
    fun setZoneRect(rect: TouchpadZoneGeometry.NormalizedRect?) {
        if (zoneRect == rect) return
        zoneRect = rect
        postInvalidateOnAnimation()
    }

    fun setPadPressed(pressed: Boolean) {
        if (touchpadPressed == pressed) return
        touchpadPressed = pressed
        postInvalidateOnAnimation()
    }

    fun setSingleTapToClick(enabled: Boolean) {
        singleTapToClick = enabled
        invalidate()
    }

    /** Emphasize the outline while the user is resizing the zone. */
    fun setResizing(active: Boolean) {
        if (resizing == active) return
        resizing = active
        postInvalidateOnAnimation()
    }

    fun updateContact(eventType: Byte, pointerId: Int, normalizedX: Float, normalizedY: Float) {
        val now = SystemClock.uptimeMillis()
        val releasedAt = when (eventType) {
            MoonBridge.LI_TOUCH_EVENT_DOWN,
            MoonBridge.LI_TOUCH_EVENT_MOVE -> 0L
            MoonBridge.LI_TOUCH_EVENT_UP,
            MoonBridge.LI_TOUCH_EVENT_CANCEL -> now
            else -> return
        }
        lastInteractionAt = now
        contacts.getOrPut(pointerId) { Contact(normalizedX, normalizedY) }.apply {
            x = normalizedX
            y = normalizedY
            this.releasedAt = releasedAt
        }
        postInvalidateOnAnimation()
    }

    fun cancelAllContacts() {
        val now = SystemClock.uptimeMillis()
        if (screenTouchActive || contacts.values.any { it.releasedAt == 0L }) lastInteractionAt = now
        screenTouchActive = false
        contacts.values.forEach { it.releasedAt = now }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val accent = UiHelper.accentColor(context)
        var needsNextFrame = drawZoneChrome(canvas, now, accent)
        needsNextFrame = drawContacts(canvas, now, accent) || needsNextFrame
        needsNextFrame = drawActivatedHint(canvas, now, accent) || needsNextFrame
        if (needsNextFrame) postInvalidateOnAnimation()
    }

    /** Map stream-normalized coordinates onto this overlay through the content transform. */
    private fun mapNormalizedToOverlay(x: Float, y: Float, out: FloatArray) {
        val coordinateView = coordinateViewProvider()
        if (coordinateView != null &&
            coordinateView.width > 0 && coordinateView.height > 0 &&
            coordinateView.scaleX > 0f && coordinateView.scaleY > 0f
        ) {
            // The stream content is laid out inside this overlay's parent via
            // x/y and scale, so map normalized coordinates through that transform.
            out[0] = coordinateView.x + x.coerceIn(0f, 1f) *
                coordinateView.width * coordinateView.scaleX
            out[1] = coordinateView.y + y.coerceIn(0f, 1f) *
                coordinateView.height * coordinateView.scaleY
        } else {
            out[0] = x.coerceIn(0f, 1f) * width
            out[1] = y.coerceIn(0f, 1f) * height
        }
    }

    /**
     * Idle chrome is fully hidden. Touch reveals the surface, while an
     * actual button press or resize uses the same accent as the rest of the app.
     */
    private fun drawZoneChrome(canvas: Canvas, now: Long, accent: Int): Boolean {
        val rect = zoneRect ?: return false
        mapNormalizedToOverlay(rect.left, rect.top, scratchPoint)
        zoneBounds.left = scratchPoint[0]
        zoneBounds.top = scratchPoint[1]
        mapNormalizedToOverlay(rect.right, rect.bottom, scratchPoint)
        zoneBounds.right = scratchPoint[0]
        zoneBounds.bottom = scratchPoint[1]

        val active = screenTouchActive || resizing || touchpadPressed || contacts.values.any { it.releasedAt == 0L }
        val emphasis = if (active) 1f else zoneFade(now)
        if (emphasis <= 0f) return false
        val corner = 12f * density

        zoneFillPaint.color = Color.BLACK
        zoneFillPaint.alpha = (16 * emphasis).toInt()
        canvas.drawRoundRect(zoneBounds, corner, corner, zoneFillPaint)
        if (touchpadPressed) {
            zoneFillPaint.color = accent
            zoneFillPaint.alpha = 12
            canvas.drawRoundRect(zoneBounds, corner, corner, zoneFillPaint)
        }
        zoneOutlinePaint.color = if (touchpadPressed || resizing) accent else Color.WHITE
        zoneOutlinePaint.alpha = ((if (resizing || touchpadPressed) 220 else 172) * emphasis).toInt()
        zoneOutlinePaint.strokeWidth = (if (resizing || touchpadPressed) 1.5f else 1f) * density
        zoneOutlineInkPaint.alpha = (85 * emphasis).toInt()
        zoneOutlineInkPaint.strokeWidth = zoneOutlinePaint.strokeWidth + 1.25f * density
        canvas.drawRoundRect(zoneBounds, corner, corner, zoneOutlineInkPaint)
        canvas.drawRoundRect(zoneBounds, corner, corner, zoneOutlinePaint)
        if (resizing) {
            resizeHandlePaint.color = accent
            val cx = zoneBounds.centerX()
            val cy = zoneBounds.centerY()
            val handle = 8f * density
            canvas.drawLine(cx - handle, zoneBounds.top, cx + handle, zoneBounds.top, resizeHandlePaint)
            canvas.drawLine(cx - handle, zoneBounds.bottom, cx + handle, zoneBounds.bottom, resizeHandlePaint)
            canvas.drawLine(zoneBounds.left, cy - handle, zoneBounds.left, cy + handle, resizeHandlePaint)
            canvas.drawLine(zoneBounds.right, cy - handle, zoneBounds.right, cy + handle, resizeHandlePaint)
        }
        return !active && emphasis > 0f
    }

    private fun drawContacts(canvas: Canvas, now: Long, accent: Int): Boolean {
        val radius = (if (touchpadPressed) 11f else 9f) * density
        var animating = false
        val iterator = contacts.values.iterator()
        while (iterator.hasNext()) {
            val contact = iterator.next()
            val alpha = if (contact.releasedAt == 0L) {
                1f
            } else {
                val fraction = (now - contact.releasedAt).toFloat() / CONTACT_FADE_MS
                if (fraction >= 1f) {
                    iterator.remove()
                    continue
                }
                animating = true
                1f - fraction
            }
            mapNormalizedToOverlay(contact.x, contact.y, scratchPoint)
            val x = scratchPoint[0]
            val y = scratchPoint[1]
            contactFillPaint.color = if (touchpadPressed) accent else Color.WHITE
            contactStrokePaint.color = if (touchpadPressed) accent else Color.WHITE
            contactFillPaint.alpha = ((if (touchpadPressed) 64 else 24) * alpha).toInt()
            contactStrokePaint.alpha = ((if (touchpadPressed) 230 else 170) * alpha).toInt()
            contactStrokePaint.strokeWidth = (if (touchpadPressed) 1.4f else 1f) * density
            canvas.drawCircle(x, y, radius, contactFillPaint)
            canvas.drawCircle(x, y, radius, contactStrokePaint)
            contactFillPaint.alpha = (190 * alpha).toInt()
            canvas.drawCircle(x, y, 2f * density, contactFillPaint)
        }
        return animating
    }

    private fun drawActivatedHint(canvas: Canvas, now: Long, accent: Int): Boolean {
        if (introStartedAt == 0L) return false
        val elapsed = now - introStartedAt
        if (elapsed >= INTRO_DURATION_MS) {
            introStartedAt = 0L
            return false
        }

        val fade = when {
            elapsed < INTRO_FADE_MS -> elapsed.toFloat() / INTRO_FADE_MS
            elapsed > INTRO_DURATION_MS - INTRO_FADE_MS ->
                (INTRO_DURATION_MS - elapsed).toFloat() / INTRO_FADE_MS
            else -> 1f
        }.coerceIn(0f, 1f)

        val horizontalPadding = 16f * density
        val iconWidth = 24f * density
        val iconGap = 10f * density
        val textLimit = (width - 32f * density - horizontalPadding * 2 - iconWidth - iconGap)
            .coerceAtLeast(0f)
        val title = TextUtils.ellipsize(resources.getString(R.string.ds5_touchpad_active_title),
            titlePaint, textLimit, TextUtils.TruncateAt.END).toString()
        val subtitle = TextUtils.ellipsize(resources.getString(
            if (singleTapToClick) R.string.ds5_touchpad_active_subtitle_single_tap
            else R.string.ds5_touchpad_active_subtitle_zone
        ), subtitlePaint, textLimit, TextUtils.TruncateAt.END).toString()
        val textWidth = max(titlePaint.measureText(title), subtitlePaint.measureText(subtitle))
        val pillWidth = horizontalPadding * 2 + iconWidth + iconGap + textWidth
        val titleMetrics = titlePaint.fontMetrics
        val subtitleMetrics = subtitlePaint.fontMetrics
        val titleHeight = titleMetrics.descent - titleMetrics.ascent
        val subtitleHeight = subtitleMetrics.descent - subtitleMetrics.ascent
        val pillHeight = max(54f * density, titleHeight + subtitleHeight + 22f * density)
        val left = (width - pillWidth) / 2f
        val top = 24f * density

        val pillBounds = RectF(left, top, left + pillWidth, top + pillHeight)
        canvas.saveLayerAlpha(pillBounds, (255 * fade).toInt())
        canvas.drawRoundRect(
            pillBounds,
            16f * density,
            16f * density,
            pillPaint,
        )

        val iconLeft = left + horizontalPadding
        val iconTop = top + (pillHeight - 18f * density) / 2f
        iconPaint.color = accent
        canvas.drawRoundRect(
            RectF(iconLeft, iconTop, iconLeft + iconWidth, iconTop + 18f * density),
            5f * density,
            5f * density,
            iconPaint,
        )
        canvas.drawCircle(iconLeft + 8f * density, iconTop + 9f * density, 1.5f * density, iconPaint)
        canvas.drawCircle(iconLeft + 16f * density, iconTop + 9f * density, 1.5f * density, iconPaint)

        val textLeft = iconLeft + iconWidth + iconGap
        val textTop = top + (pillHeight - titleHeight - subtitleHeight - 2f * density) / 2f
        canvas.drawText(title, textLeft, textTop - titleMetrics.ascent, titlePaint)
        canvas.drawText(subtitle, textLeft,
            textTop + titleHeight + 2f * density - subtitleMetrics.ascent, subtitlePaint)
        canvas.restore()
        return true
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val CONTACT_FADE_MS = 250f
        const val INTRO_DURATION_MS = 2600L
        const val INTRO_FADE_MS = 240L
        const val ZONE_REVEAL_MS = 5000L
    }
}
