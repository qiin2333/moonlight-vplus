package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import kotlin.math.roundToInt

/** Keeps device drawings independent of the enclosing sheet's aspect ratio. */
class DevicePickerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SuperPageLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val scroll = findViewById<View>(R.id.device_vertical_scroll)
        val viewportWidth = (MeasureSpec.getSize(widthMeasureSpec) -
            scroll.paddingLeft - scroll.paddingRight).coerceAtLeast(1)
        val contentTopMargin = (scroll.layoutParams as MarginLayoutParams).topMargin
        val viewportHeight = (MeasureSpec.getSize(heightMeasureSpec) - contentTopMargin -
            scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(1)
        val keyboard = findViewById<View>(R.id.keyboard_drawing)
        val keyboardPaddingHeight = keyboard.paddingTop + keyboard.paddingBottom
        val canvasViewportWidth = (viewportWidth - keyboard.paddingLeft - keyboard.paddingRight).coerceAtLeast(1)
        val tabsHeight = findViewById<View>(R.id.keyboard_picker_tabs).layoutParams.height
        // Fifteen key units across five rows. On narrow/short displays retain
        // usable key sizes and let the enclosing scroll views reveal the rest.
        val numpad = findViewById<View>(R.id.keyboard_picker_num)
        val isNumpad = numpad.visibility == View.VISIBLE
        val keyboardWidth = if (isNumpad) {
            canvasViewportWidth
        } else {
            minOf(canvasViewportWidth, (viewportHeight - tabsHeight - keyboardPaddingHeight) * 3)
                .coerceAtLeast(dp(600))
        }
        val numpadWidth = minOf(dp(360), keyboardWidth)
        keyboard.layoutParams.apply {
            width = viewportWidth
            height = keyboardPaddingHeight + tabsHeight +
                if (isNumpad) numpadWidth / 2 else (keyboardWidth / 3f).roundToInt()
        }
        findViewById<View>(R.id.device_keyboard_pages).layoutParams.width = keyboardWidth
        findViewById<View>(R.id.device_content).minimumHeight = viewportHeight
        // Illustrations remain centered at their native size inside the active page.
        for (id in intArrayOf(R.id.mouse_drawing, R.id.gamepad_drawing)) {
            findViewById<View>(id).layoutParams.width = viewportWidth
        }

        // The six-column, three-row numpad needs its own proportions inside
        // the wider keyboard panel, rather than stretching across that panel.
        (numpad.layoutParams as FrameLayout.LayoutParams).apply {
            width = numpadWidth
            height = numpadWidth / 2
            gravity = Gravity.CENTER
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
