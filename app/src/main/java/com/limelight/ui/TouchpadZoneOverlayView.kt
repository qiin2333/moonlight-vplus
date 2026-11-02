package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import com.limelight.R
import com.limelight.binding.input.touchpad.TouchpadZoneGeometry
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.max

/**
 * Reusable overlay for a screen touchpad surface: draws the zone outline, pad
 * contact dots and the activation hint. The zone stays visible while idle so the
 * surface is always locatable, and brightens on contact, reveal and resize.
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

    private val contactFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val contactStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDC171923.toInt()
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCFFFFFFF.toInt()
        textSize = sp(11f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AB4F8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val zoneOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    // Subtitle-style under-stroke: keeps the outline legible on bright game content.
    private val zoneOutlineInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
    }

    private var introStartedAt = 0L
    private var touchpadPressed = false
    private var resizing = false

    /** Zone surface in stream-normalized coordinates; null when the pad is off. */
    private var zoneRect: TouchpadZoneGeometry.NormalizedRect? = null
    private var lastContactAt = 0L
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
     * the pad surface is right after enabling or resizing it; idle stays calm after.
     */
    fun showZoneReveal() {
        zoneRevealUntil = SystemClock.uptimeMillis() + ZONE_REVEAL_MS
        postInvalidateOnAnimation()
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

    /** Emphasize the outline while the user is resizing the zone. */
    fun setResizing(active: Boolean) {
        if (resizing == active) return
        resizing = active
        postInvalidateOnAnimation()
    }

    fun updateContact(eventType: Byte, pointerId: Int, normalizedX: Float, normalizedY: Float) {
        val now = SystemClock.uptimeMillis()
        when (eventType) {
            MoonBridge.LI_TOUCH_EVENT_DOWN,
            MoonBridge.LI_TOUCH_EVENT_MOVE -> {
                lastContactAt = now
                val contact = contacts.getOrPut(pointerId) { Contact(normalizedX, normalizedY) }
                contact.x = normalizedX
                contact.y = normalizedY
                contact.releasedAt = 0L
            }
            MoonBridge.LI_TOUCH_EVENT_UP,
            MoonBridge.LI_TOUCH_EVENT_CANCEL -> {
                val contact = contacts.getOrPut(pointerId) { Contact(normalizedX, normalizedY) }
                contact.x = normalizedX
                contact.y = normalizedY
                contact.releasedAt = now
            }
        }
        postInvalidateOnAnimation()
    }

    fun cancelAllContacts() {
        val now = SystemClock.uptimeMillis()
        contacts.values.forEach { it.releasedAt = now }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        var needsNextFrame = drawZoneChrome(canvas, now)
        needsNextFrame = drawContacts(canvas, now) || needsNextFrame
        needsNextFrame = drawActivatedHint(canvas, now) || needsNextFrame
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
     * Zone affordances: a faint double-stroked outline stays visible while idle so
     * the surface is always locatable; contact, reveal and resize brighten it.
     */
    private fun drawZoneChrome(canvas: Canvas, now: Long): Boolean {
        val rect = zoneRect ?: return false
        val point = FloatArray(2)
        mapNormalizedToOverlay(rect.left, rect.top, point)
        val left = point[0]
        val top = point[1]
        mapNormalizedToOverlay(rect.right, rect.bottom, point)
        val right = point[0]
        val bottom = point[1]

        val contactActive = now - lastContactAt < ZONE_ACTIVE_MS
        val revealing = now < zoneRevealUntil
        val outlineActive = resizing || touchpadPressed || contactActive ||
            introStartedAt != 0L || revealing

        // Subtitle-style double stroke: dark under-stroke first, bright line on top.
        zoneOutlineInkPaint.alpha = if (outlineActive) 220 else 200
        canvas.drawRoundRect(
            RectF(left, top, right, bottom),
            10f * density,
            10f * density,
            zoneOutlineInkPaint,
        )
        zoneOutlinePaint.alpha = if (outlineActive) 240 else 215
        zoneOutlinePaint.strokeWidth = if (resizing) 2.6f * density else 1.8f * density
        canvas.drawRoundRect(
            RectF(left, top, right, bottom),
            10f * density,
            10f * density,
            zoneOutlinePaint,
        )
        return contactActive || revealing
    }

    private fun drawContacts(canvas: Canvas, now: Long): Boolean {
        val radius = (if (touchpadPressed) 23f else 18f) * density
        var animating = false
        val iterator = contacts.iterator()
        while (iterator.hasNext()) {
            val (_, contact) = iterator.next()
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
            contactFillPaint.color = if (touchpadPressed) 0xFF8AB4F8.toInt() else Color.WHITE
            contactStrokePaint.color = if (touchpadPressed) 0xFF8AB4F8.toInt() else Color.WHITE
            contactFillPaint.alpha = ((if (touchpadPressed) 92 else 42) * alpha).toInt()
            contactStrokePaint.alpha = (220 * alpha).toInt()
            canvas.drawCircle(x, y, radius, contactFillPaint)
            canvas.drawCircle(x, y, radius, contactStrokePaint)
            if (touchpadPressed) {
                contactStrokePaint.alpha = (105 * alpha).toInt()
                canvas.drawCircle(x, y, radius + 6f * density, contactStrokePaint)
            }
            canvas.drawCircle(x, y, 3.5f * density, contactFillPaint.apply {
                this.alpha = (210 * alpha).toInt()
            })
        }
        return animating
    }

    private fun drawActivatedHint(canvas: Canvas, now: Long): Boolean {
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

        val title = resources.getString(R.string.ds5_touchpad_active_title)
        val subtitle = resources.getString(R.string.ds5_touchpad_active_subtitle_zone)
        val horizontalPadding = 18f * density
        val iconWidth = 30f * density
        val iconGap = 12f * density
        val textWidth = max(titlePaint.measureText(title), subtitlePaint.measureText(subtitle))
        val pillWidth = horizontalPadding * 2 + iconWidth + iconGap + textWidth
        val pillHeight = 58f * density
        val left = (width - pillWidth) / 2f
        val top = 24f * density

        val pillBounds = RectF(left, top, left + pillWidth, top + pillHeight)
        canvas.saveLayerAlpha(pillBounds, (255 * fade).toInt())
        canvas.drawRoundRect(
            pillBounds,
            18f * density,
            18f * density,
            pillPaint,
        )

        val iconLeft = left + horizontalPadding
        val iconTop = top + 19f * density
        canvas.drawRoundRect(
            RectF(iconLeft, iconTop, iconLeft + iconWidth, iconTop + 20f * density),
            5f * density,
            5f * density,
            iconPaint,
        )
        canvas.drawCircle(iconLeft + 10f * density, iconTop + 10f * density, 2f * density, iconPaint)
        canvas.drawCircle(iconLeft + 20f * density, iconTop + 10f * density, 2f * density, iconPaint)

        val textLeft = iconLeft + iconWidth + iconGap
        canvas.drawText(title, textLeft, top + 24f * density, titlePaint)
        canvas.drawText(subtitle, textLeft, top + 43f * density, subtitlePaint)
        canvas.restore()
        return true
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val CONTACT_FADE_MS = 150f
        const val INTRO_DURATION_MS = 2600L
        const val INTRO_FADE_MS = 240L
        const val ZONE_ACTIVE_MS = 600L
        const val ZONE_REVEAL_MS = 5000L
    }
}
