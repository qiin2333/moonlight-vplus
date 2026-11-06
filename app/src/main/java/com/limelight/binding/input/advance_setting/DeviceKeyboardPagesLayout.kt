package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** HorizontalScrollView measures children with an unconstrained width. */
class DeviceKeyboardPagesLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Honor the canvas width recalculated by DevicePickerLayout, including
        // shrinking after rotation; do not reuse the previous content width.
        val canvasWidth = MeasureSpec.makeMeasureSpec(layoutParams.width.coerceAtLeast(1), MeasureSpec.EXACTLY)
        super.onMeasure(canvasWidth, heightMeasureSpec)
    }
}
