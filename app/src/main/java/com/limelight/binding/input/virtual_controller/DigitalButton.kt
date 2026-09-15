/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.RectF
import android.view.MotionEvent

open class DigitalButton(controller: VirtualController, elementId: Int, private val layer: Int, context: Context) :
    VirtualControllerElement(controller, context, elementId) {
    companion object {
        private var controllerTypeface: Typeface? = null
        private fun typeface(context: Context): Typeface = controllerTypeface
            ?: Typeface.createFromAsset(context.assets, "fonts/osc_medium.ttf").also { controllerTypeface = it }
    }

    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners = mutableListOf<DigitalButtonListener>()
    private var text = ""
    private var icon = -1
    var labelColor: Int? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (!classic) typeface = typeface(context)
    }
    private val rect = RectF()
    private val textBounds = Rect()
    private val symbol = Path()
    private val surface = ControllerSurface()
    // Multiple owners prevent one finger releasing a button held by another finger.
    private val owners = mutableSetOf<DigitalButton>()
    private var touching = false
    private val longClick = Runnable { if (isPressed) listeners.forEach { it.onLongClick() } }

    fun addDigitalButtonListener(listener: DigitalButtonListener) { listeners.add(listener) }
    fun setText(text: String) {
        this.text = text
        contentDescription = text
        invalidate()
    }
    fun setIcon(id: Int) { icon = id; invalidate() }

    private fun contains(px: Float, py: Float) = px >= x && px < x + width && py >= y && py < y + height

    private fun own(source: DigitalButton, down: Boolean) {
        val previous = isPressed
        if (down) owners.add(source) else owners.remove(source)
        isPressed = owners.isNotEmpty()
        if (previous == isPressed) return
        if (isPressed) {
            listeners.forEach { it.onClick() }
            virtualController.handler.postDelayed(longClick, 3000)
        } else {
            virtualController.handler.removeCallbacks(longClick)
            listeners.forEach { it.onRelease() }
        }
        invalidate()
    }

    fun checkMovement(x: Float, y: Float, movingButton: DigitalButton): Boolean {
        if (layer != movingButton.layer) return false
        val before = isPressed
        own(movingButton, movingButton.touching && contains(x, y))
        return before != isPressed
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                own(this, true)
            }
            MotionEvent.ACTION_MOVE -> {
                val px = x + event.x
                val py = y + event.y
                virtualController.elements.filterIsInstance<DigitalButton>().forEach { button ->
                    // Classic retains slide-to-combine; modern face keys slide to switch.
                    if (button !== this || !classic) button.checkMovement(px, py, this)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                virtualController.elements.filterIsInstance<DigitalButton>().forEach { it.own(this, false) }
                own(this, false)
            }
        }
        return true
    }

    override fun onElementDraw(canvas: Canvas) {
        paint.strokeWidth = defaultStrokeWidth
        paint.style = Paint.Style.STROKE
        paint.color = if (isPressed) pressedColor else defaultColor
        // The view is the hit target; inset the visible outline without overlapping neighbours.
        val inset = if (classic) defaultStrokeWidth else minOf(width, height) * 0.13f
        rect.set(inset, inset, width - inset, height - inset)
        val pill = !classic && width > height * 1.2f
        if (classic) canvas.drawOval(rect, paint)
        else surface.draw(canvas, rect, pill, paint.color, defaultStrokeWidth)
        if (!classic && isPressed) {
            paint.style = Paint.Style.FILL
            paint.alpha = paint.alpha / 5
            if (pill) canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            else canvas.drawOval(rect, paint)
        }
        if (icon != -1) {
            context.getDrawable(icon)?.apply {
                setBounds(inset.toInt(), inset.toInt(), (width - inset).toInt(), (height - inset).toInt())
                alpha = android.graphics.Color.alpha(defaultColor)
                draw(canvas)
            }
        } else {
            paint.style = if (classic) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = if (classic) defaultStrokeWidth / 2 else 0f
            paint.color = if (isPressed) pressedColor else defaultColor
            if (!classic && !isPressed && virtualController.controllerMode == VirtualController.ControllerMode.Active) {
                labelColor?.let { paint.color = (defaultColor and 0xFF000000.toInt()) or (it and 0xFFFFFF) }
            }
            if (virtualController.layoutStyle == VirtualControllerLayout.DS && (text == "×" || text == "○" || text == "□" || text == "△")) {
                drawFaceSymbol(canvas)
                return
            }
            if (!classic && drawUtilitySymbol(canvas)) return
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (classic) width * 0.25f else minOf(width, height) * if (text.length > 2) 0.22f else 0.32f
            if (classic) {
                canvas.drawText(text, width / 2f, height * 0.63f, paint)
            } else {
                // Centre visible ink, including side bearings and fallback-font symbols.
                // Font ascent/descent centre the line box, leaving e.g. Home visibly too low.
                paint.textAlign = Paint.Align.LEFT
                paint.getTextBounds(text, 0, text.length, textBounds)
                canvas.drawText(text, rect.centerX() - textBounds.exactCenterX(),
                    rect.centerY() - textBounds.exactCenterY(), paint)
            }
        }
    }

    private fun drawUtilitySymbol(canvas: Canvas): Boolean {
        if (text != "⌂" && text != "⋯" && text != "≡" && text != "+" && text != "−") return false
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = minOf(rect.width(), rect.height()) * .18f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = defaultStrokeWidth * 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (text) {
            "⌂" -> {
                symbol.reset()
                symbol.moveTo(cx, cy - r)
                symbol.lineTo(cx + r, cy - r * .1f)
                symbol.lineTo(cx + r, cy + r)
                symbol.lineTo(cx - r, cy + r)
                symbol.lineTo(cx - r, cy - r * .1f)
                symbol.close()
                canvas.drawPath(symbol, paint)
            }
            "⋯" -> {
                paint.style = Paint.Style.FILL
                for (i in -1..1) canvas.drawCircle(cx + i * r, cy, paint.strokeWidth * .65f, paint)
            }
            "≡" -> {
                symbol.reset()
                for (i in -1..1) {
                    symbol.moveTo(cx - r, cy + i * r * .7f)
                    symbol.lineTo(cx + r, cy + i * r * .7f)
                }
                canvas.drawPath(symbol, paint)
            }
            else -> {
                symbol.reset()
                symbol.moveTo(cx - r, cy)
                symbol.lineTo(cx + r, cy)
                if (text == "+") {
                    symbol.moveTo(cx, cy - r)
                    symbol.lineTo(cx, cy + r)
                }
                canvas.drawPath(symbol, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        return true
    }

    private fun drawFaceSymbol(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(rect.width(), rect.height()) * 0.22f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = defaultStrokeWidth * 1.3f
        when (text) {
            "○" -> canvas.drawCircle(cx, cy, radius, paint)
            "□" -> canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, paint)
            "×" -> {
                canvas.drawLine(cx - radius, cy - radius, cx + radius, cy + radius, paint)
                canvas.drawLine(cx - radius, cy + radius, cx + radius, cy - radius, paint)
            }
            "△" -> {
                symbol.reset()
                symbol.moveTo(cx, cy - radius)
                symbol.lineTo(cx + radius, cy + radius)
                symbol.lineTo(cx - radius, cy + radius)
                symbol.close()
                canvas.drawPath(symbol, paint)
            }
        }
    }

}
