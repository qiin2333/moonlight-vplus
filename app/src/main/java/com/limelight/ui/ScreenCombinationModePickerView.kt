package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.limelight.R

class ScreenCombinationModePickerView(
    context: Context,
    names: Array<String>,
    descriptions: Array<String>,
    values: Array<String>,
    checkedIndex: Int,
    private val onClose: () -> Unit,
    private val onModeSelected: (Int) -> Unit
) : ScrollView(context) {
    private var selectedOptionView: View? = null

    init {
        isFillViewport = true

        val primaryTextColor = ContextCompat.getColor(context, R.color.appview_text_primary)
        val secondaryTextColor = ContextCompat.getColor(context, R.color.appview_text_secondary)
        val accentColor = ContextCompat.getColor(context, R.color.theme_pink_primary)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(18))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.title_screen_combination_mode)
                setTextColor(primaryTextColor)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.screen_combination_mode_dialog_subtitle)
                setTextColor(secondaryTextColor)
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(context).apply {
            text = context.getString(R.string.screen_combination_mode_action_close)
            setTextColor(primaryTextColor)
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(44)
            minWidth = dp(76)
            background = roundedBackground(Color.argb(40, 255, 255, 255), Color.argb(46, 255, 255, 255), dp(18))
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { _, hasFocus ->
                background = roundedBackground(
                    Color.argb(if (hasFocus) 58 else 40, 255, 255, 255),
                    Color.argb(if (hasFocus) 96 else 46, 255, 255, 255),
                    dp(18)
                )
            }
            setOnClickListener { onClose() }
        })
        root.addView(header)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(24))
        }

        names.forEachIndexed { index, name ->
            val modeValue = values.getOrNull(index)?.toIntOrNull() ?: -1
            val selected = index == checkedIndex
            list.addView(createOption(
                title = name,
                description = descriptions.getOrNull(index).orEmpty(),
                modeValue = modeValue,
                selected = selected,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                accentColor = accentColor
            ))
        }
        root.addView(list)
        addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        post { selectedOptionView?.requestFocus() }
    }

    private fun createOption(
        title: String,
        description: String,
        modeValue: Int,
        selected: Boolean,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        accentColor: Int
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = optionBackground(selected, false)
            isClickable = true
            isFocusable = true
            setOnFocusChangeListener { view, hasFocus ->
                view.background = optionBackground(selected, hasFocus)
            }
            setOnClickListener { onModeSelected(modeValue) }
        }
        if (selected) {
            selectedOptionView = row
        }

        row.addView(ScreenCombinationPreviewView(context, modeValue, selected), LinearLayout.LayoutParams(
            dp(118),
            dp(78)
        ))

        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(context).apply {
                text = title
                setTextColor(primaryTextColor)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            })
            addView(TextView(context).apply {
                text = description
                setTextColor(secondaryTextColor)
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
            if (selected) {
                addView(TextView(context).apply {
                    text = context.getString(R.string.screen_combination_mode_selected)
                    setTextColor(accentColor)
                    textSize = 12f
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun optionBackground(selected: Boolean, focused: Boolean): GradientDrawable {
        return if (selected) {
            roundedBackground(
                Color.argb(if (focused) 64 else 42, 255, 107, 157),
                Color.argb(if (focused) 230 else 185, 255, 107, 157),
                dp(20)
            )
        } else {
            roundedBackground(
                Color.argb(if (focused) 56 else 42, 255, 255, 255),
                Color.argb(if (focused) 86 else 28, 255, 255, 255),
                dp(20)
            )
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private inner class ScreenCombinationPreviewView(
        context: Context,
        private val modeValue: Int,
        private val selected: Boolean
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val accentColor = ContextCompat.getColor(context, R.color.theme_pink_primary)
        private val activeFill = Color.argb(if (selected) 235 else 205, 255, 107, 157)
        private val activeStroke = Color.argb(235, 255, 180, 210)
        private val idleFill = Color.argb(36, 255, 255, 255)
        private val idleStroke = Color.argb(115, 255, 255, 255)
        private val mutedStroke = Color.argb(70, 255, 255, 255)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val screenW = w * 0.38f
            val screenH = h * 0.48f
            val left = RectF(w * 0.08f, h * 0.2f, w * 0.08f + screenW, h * 0.2f + screenH)
            val right = RectF(w * 0.54f, h * 0.2f, w * 0.54f + screenW, h * 0.2f + screenH)
            val center = RectF(w * 0.31f, h * 0.17f, w * 0.31f + screenW, h * 0.17f + screenH)

            when (modeValue) {
                -1 -> {
                    drawDisplay(canvas, left, idleFill, idleStroke)
                    drawDisplay(canvas, right, idleFill, idleStroke)
                    drawGearHint(canvas, left.centerX(), left.centerY(), h * 0.13f)
                    drawConnection(canvas, left, right, idleStroke)
                }
                0 -> {
                    drawDisplay(canvas, left, Color.TRANSPARENT, mutedStroke)
                    drawDisplay(canvas, right, Color.TRANSPARENT, mutedStroke)
                    drawPauseHint(canvas, w * 0.5f, h * 0.44f, h * 0.18f)
                }
                1 -> {
                    drawDisplay(canvas, left, idleFill, idleStroke)
                    drawArrow(canvas, left.right + w * 0.03f, h * 0.44f, right.left - w * 0.03f, h * 0.44f)
                    drawDisplay(canvas, right, activeFill, activeStroke)
                }
                2 -> {
                    drawDisplay(canvas, left, idleFill, idleStroke)
                    drawDisplay(canvas, right, activeFill, activeStroke)
                    drawPrimaryBadge(canvas, right.centerX(), right.top - h * 0.02f)
                }
                4 -> {
                    drawDisplay(canvas, left, Color.argb(58, 255, 255, 255), Color.argb(160, 255, 255, 255))
                    drawPrimaryBadge(canvas, left.centerX(), left.top - h * 0.02f, Color.argb(220, 255, 255, 255))
                    drawConnection(canvas, left, right, Color.argb(135, 255, 107, 157))
                    drawDisplay(canvas, right, Color.argb(60, 255, 107, 157), activeStroke)
                }
                3 -> {
                    val faintLeft = RectF(w * 0.05f, h * 0.28f, w * 0.28f, h * 0.63f)
                    val faintRight = RectF(w * 0.72f, h * 0.28f, w * 0.95f, h * 0.63f)
                    drawDisplay(canvas, faintLeft, Color.TRANSPARENT, mutedStroke)
                    drawDisplay(canvas, faintRight, Color.TRANSPARENT, mutedStroke)
                    drawDisabledSlash(canvas, faintLeft)
                    drawDisabledSlash(canvas, faintRight)
                    drawDisplay(canvas, center, activeFill, activeStroke)
                }
                else -> {
                    drawDisplay(canvas, left, idleFill, idleStroke)
                    drawDisplay(canvas, right, activeFill, activeStroke)
                }
            }
        }

        private fun drawDisplay(canvas: Canvas, bounds: RectF, fill: Int, stroke: Int) {
            rect.set(bounds)
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = stroke
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.style = Paint.Style.FILL
            paint.color = stroke
            canvas.drawRoundRect(
                bounds.centerX() - dp(10),
                bounds.bottom + dp(6).toFloat(),
                bounds.centerX() + dp(10),
                bounds.bottom + dp(9).toFloat(),
                dp(2).toFloat(),
                dp(2).toFloat(),
                paint
            )
        }

        private fun drawConnection(canvas: Canvas, from: RectF, to: RectF, color: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = color
            canvas.drawLine(from.right + dp(5), from.centerY(), to.left - dp(5), to.centerY(), paint)
        }

        private fun drawArrow(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = accentColor
            canvas.drawLine(startX, startY, endX, endY, paint)
            canvas.drawLine(endX, endY, endX - dp(8), endY - dp(6), paint)
            canvas.drawLine(endX, endY, endX - dp(8), endY + dp(6), paint)
        }

        private fun drawPrimaryBadge(canvas: Canvas, cx: Float, cy: Float, color: Int = activeStroke) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(cx, cy + dp(6), dp(6).toFloat(), paint)
            paint.color = Color.argb(220, 28, 29, 34)
            canvas.drawCircle(cx, cy + dp(6), dp(2).toFloat(), paint)
        }

        private fun drawPauseHint(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(150, 255, 255, 255)
            val barW = size * 0.18f
            val gap = size * 0.12f
            canvas.drawRoundRect(cx - gap - barW, cy - size * 0.4f, cx - gap, cy + size * 0.4f, dp(2).toFloat(), dp(2).toFloat(), paint)
            canvas.drawRoundRect(cx + gap, cy - size * 0.4f, cx + gap + barW, cy + size * 0.4f, dp(2).toFloat(), dp(2).toFloat(), paint)
        }

        private fun drawGearHint(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.argb(150, 255, 255, 255)
            canvas.drawCircle(cx, cy, radius * 0.55f, paint)
            for (i in 0 until 8) {
                val angle = Math.PI * 2.0 * i / 8.0
                val sx = cx + kotlin.math.cos(angle).toFloat() * radius * 0.75f
                val sy = cy + kotlin.math.sin(angle).toFloat() * radius * 0.75f
                val ex = cx + kotlin.math.cos(angle).toFloat() * radius
                val ey = cy + kotlin.math.sin(angle).toFloat() * radius
                canvas.drawLine(sx, sy, ex, ey, paint)
            }
        }

        private fun drawDisabledSlash(canvas: Canvas, bounds: RectF) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.argb(120, 255, 107, 157)
            canvas.drawLine(bounds.left + dp(5), bounds.bottom - dp(5), bounds.right - dp(5), bounds.top + dp(5), paint)
        }
    }
}
