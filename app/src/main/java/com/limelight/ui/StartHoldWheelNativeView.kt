package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.limelight.R
import com.limelight.binding.input.StartWheelAction
import kotlin.math.roundToInt

/**
 * Plain-view implementation for the start-hold wheel.
 *
 * Some TV firmware exposes an unusable clipboard backend while the rest of Compose can
 * still attach. The wheel is required during stream startup, so it uses this regular View
 * when the clipboard backend cannot be probed successfully.
 */
internal class StartHoldWheelNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val scrimPaint = Paint().apply { color = Color.argb(97, 0, 0, 0) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 247, 236)
    }
    private val satellite = drawable(R.drawable.start_wheel_satellite)
    private val satelliteSelected = drawable(R.drawable.start_wheel_satellite_selected)
    private val center = drawable(R.drawable.start_wheel_center)
    private val continueIcon = drawable(R.drawable.ic_start_wheel_continue)
    private val optionIcons = mapOf(
        StartWheelAction.MENU to drawable(R.drawable.ic_start_wheel_menu),
        StartWheelAction.MOUSE to drawable(R.drawable.ic_start_wheel_mouse),
        StartWheelAction.KEYBOARD to drawable(R.drawable.ic_start_wheel_keyboard),
        StartWheelAction.PERFORMANCE to drawable(R.drawable.ic_start_wheel_performance)
    )

    private var selectedAction = StartWheelAction.CONTINUE

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setSelectedAction(action: StartWheelAction) {
        if (selectedAction == action) return
        selectedAction = action
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale
        val clusterSize = minOf(width * 0.64f, height * 0.82f, 520f * density)
        val satelliteSize = clusterSize * 0.292f
        val centerSize = clusterSize * 0.335f
        val orbit = clusterSize * 0.30f
        val compact = clusterSize < 360f * density
        val centerX = width / 2f
        val centerY = height / 2f

        drawSatellite(
            canvas, centerX, centerY - orbit, satelliteSize,
            StartWheelAction.MENU,
            R.string.start_hold_wheel_menu,
            density, fontScale, compact
        )
        drawSatellite(
            canvas, centerX + orbit, centerY, satelliteSize,
            StartWheelAction.MOUSE,
            R.string.start_hold_wheel_mouse,
            density, fontScale, compact
        )
        drawSatellite(
            canvas, centerX, centerY + orbit, satelliteSize,
            StartWheelAction.KEYBOARD,
            R.string.start_hold_wheel_keyboard,
            density, fontScale, compact
        )
        drawSatellite(
            canvas, centerX - orbit, centerY, satelliteSize,
            StartWheelAction.PERFORMANCE,
            R.string.start_hold_wheel_performance,
            density, fontScale, compact
        )

        val selected = selectedAction == StartWheelAction.CONTINUE
        canvas.save()
        canvas.scale(if (selected) 1.05f else 1f, if (selected) 1.05f else 1f, centerX, centerY)
        drawDrawable(
            center,
            canvas,
            centerX,
            centerY,
            centerSize,
            alpha = if (selected) 0.96f else 0.82f
        )
        drawOptionContent(
            canvas = canvas,
            icon = continueIcon,
            iconSize = (if (compact) 35f else 46f) * density,
            label = context.getString(R.string.start_hold_wheel_continue),
            labelSize = (if (compact) 11f else 15f) * density * fontScale,
            lineHeight = (if (compact) 11f else 14f) * density * fontScale,
            spacing = (if (compact) 1f else 3f) * density,
            maxWidth = centerSize,
            centerX = centerX,
            centerY = centerY + (if (compact) 1f else 3f) * density,
            bold = true
        )
        canvas.restore()
    }

    private fun drawSatellite(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        action: StartWheelAction,
        labelRes: Int,
        density: Float,
        fontScale: Float,
        compact: Boolean
    ) {
        val selected = selectedAction == action
        canvas.save()
        canvas.scale(if (selected) 1.06f else 1f, if (selected) 1.06f else 1f, centerX, centerY)
        drawDrawable(
            if (selected) satelliteSelected else satellite,
            canvas,
            centerX,
            centerY,
            size,
            alpha = if (selected) 0.92f else 0.38f
        )
        drawOptionContent(
            canvas = canvas,
            icon = optionIcons.getValue(action),
            iconSize = (if (compact) 30f else 42f) * density,
            label = context.getString(labelRes),
            labelSize = (if (compact) 10f else 13f) * density * fontScale,
            lineHeight = (if (compact) 11f else 14f) * density * fontScale,
            spacing = (if (compact) 1f else 3f) * density,
            maxWidth = size,
            centerX = centerX,
            centerY = centerY - (if (compact) 2f else 4f) * density,
            bold = false
        )
        canvas.restore()
    }

    private fun drawOptionContent(
        canvas: Canvas,
        icon: Drawable,
        iconSize: Float,
        label: String,
        labelSize: Float,
        lineHeight: Float,
        spacing: Float,
        maxWidth: Float,
        centerX: Float,
        centerY: Float,
        bold: Boolean
    ) {
        textPaint.textSize = labelSize
        textPaint.typeface = Typeface.create(
            if (bold) Typeface.SANS_SERIF else Typeface.create("sans-serif-medium", Typeface.NORMAL),
            if (bold) Typeface.BOLD else Typeface.NORMAL
        )
        val layoutWidth = maxWidth.roundToInt().coerceAtLeast(1)
        val spacingMultiplier = (lineHeight / textPaint.fontSpacing).coerceAtLeast(1f)
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            createStaticLayout(label, layoutWidth, spacingMultiplier)
        } else {
            createLegacyStaticLayout(label, layoutWidth, spacingMultiplier)
        }
        val visibleLineCount = minOf(layout.lineCount, 2)
        val textHeight = if (visibleLineCount == 0) {
            0f
        } else {
            layout.getLineBottom(visibleLineCount - 1).toFloat()
        }
        var top = centerY - (iconSize + spacing + textHeight) / 2f
        drawDrawable(icon, canvas, centerX, top + iconSize / 2f, iconSize, alpha = 1f)
        top += iconSize + spacing
        canvas.save()
        canvas.clipRect(0f, 0f, layout.width.toFloat(), textHeight)
        canvas.translate(centerX - layout.width / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawDrawable(
        target: Drawable,
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float = 1f
    ) {
        target.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        target.setBounds(
            (centerX - size / 2f).roundToInt(),
            (centerY - size / 2f).roundToInt(),
            (centerX + size / 2f).roundToInt(),
            (centerY + size / 2f).roundToInt()
        )
        target.draw(canvas)
        target.alpha = 255
    }

    private fun drawable(resourceId: Int): Drawable {
        return requireNotNull(ResourcesCompat.getDrawable(resources, resourceId, context.theme))
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createStaticLayout(
        label: String,
        width: Int,
        spacingMultiplier: Float
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(label, 0, label.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, spacingMultiplier)
            .setIncludePad(false)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun createLegacyStaticLayout(
        label: String,
        width: Int,
        spacingMultiplier: Float
    ): StaticLayout {
        return StaticLayout(
            label,
            textPaint,
            width,
            Layout.Alignment.ALIGN_CENTER,
            spacingMultiplier,
            0f,
            false
        )
    }
}
