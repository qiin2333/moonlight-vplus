package com.limelight.binding.input.advance_setting.element

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity

import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.utils.ColorPickerDialog

object CrownColorPickerBinder {
    fun interface ColorSupplier {
        fun get(): Int
    }

    fun interface ColorUpdater {
        fun accept(color: Int)
    }

    @JvmStatic
    fun bind(owner: Element, colorDisplay: ElementEditText, colorSupplier: ColorSupplier, colorUpdater: ColorUpdater) {
        colorDisplay.isFocusable = false
        colorDisplay.setCursorVisible(false)
        colorDisplay.setKeyListener(null)
        colorDisplay.setSingleLine(true)
        colorDisplay.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        colorDisplay.setPadding(dp(owner, 10), 0, dp(owner, 8), 0)
        colorDisplay.setCompoundDrawablePadding(dp(owner, 8))
        updateColorDisplay(colorDisplay, colorSupplier.get())
        colorDisplay.setOnClickListener {
            ColorPickerDialog(
                owner.context,
                colorSupplier.get(),
                true
            ) { newColor ->
                colorUpdater.accept(newColor)
                owner.save()
                updateColorDisplay(colorDisplay, newColor)
            }.show()
        }
    }

    @JvmStatic
    fun updateColorDisplay(colorDisplay: ElementEditText, color: Int) {
        val hex = if (Color.alpha(color) == 0xFF) {
            String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
        } else {
            String.format("#%08X", color)
        }
        colorDisplay.setTextWithNoTextChangedCallBack(hex)
        colorDisplay.setBackgroundResource(R.drawable.crown_color_input_bg)
        val swatch = ColorSwatchDrawable(colorDisplay.context, color)
        val arrow = ContextCompat.getDrawable(colorDisplay.context, R.drawable.ic_chevron_right)
        if (arrow != null) {
            DrawableCompat.setTint(arrow, ContextCompat.getColor(colorDisplay.context, R.color.crown_text_secondary))
        }
        colorDisplay.setCompoundDrawablesRelative(swatch, null, arrow, null)
        colorDisplay.setTextColor(ContextCompat.getColor(colorDisplay.context, R.color.crown_text_primary))
        colorDisplay.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private fun dp(owner: Element, value: Int): Int {
        return Math.round(value * owner.context.resources.displayMetrics.density)
    }

    private class ColorSwatchDrawable(context: Context, val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val density: Float

        init {
            density = context.resources.displayMetrics.density
            val size = Math.round(22 * density)
            setBounds(0, 0, size, size)
            border.style = Paint.Style.STROKE
            border.strokeWidth = Math.max(1f, density)
            border.color = ContextCompat.getColor(context, R.color.crown_input_border)
        }

        override fun draw(canvas: Canvas) {
            val bounds: Rect = bounds
            rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            val radius = 5 * density
            if (Color.alpha(color) < 0xFF) {
                paint.color = 0xFFE1E4EA.toInt()
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.color = 0xFFB6BBC4.toInt()
                val cell = 5 * density
                canvas.save()
                canvas.clipRect(rect)
                var y = rect.top
                while (y < rect.bottom) {
                    var x = rect.left
                    while (x < rect.right) {
                        if (((((x - rect.left) / cell).toInt() + ((y - rect.top) / cell).toInt()) and 1) != 0) {
                            canvas.drawRect(x, y, Math.min(x + cell, rect.right), Math.min(y + cell, rect.bottom), paint)
                        }
                        x += cell
                    }
                    y += cell
                }
                canvas.restore()
            }
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.drawRoundRect(rect, radius, radius, border)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int { return PixelFormat.TRANSLUCENT }
    }
}
