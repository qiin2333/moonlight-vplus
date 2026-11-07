package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** HorizontalScrollView measures children with an unconstrained width. */
class DeviceKeyboardPagesLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    /** Enforce explicit canvas widths; leave standard layout widths to FrameLayout. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Honor the canvas width recalculated by DevicePickerLayout, including
        // shrinking after rotation; do not reuse the previous content width.
        val canvasWidth = layoutParams?.width
        val canvasWidthSpec = if (canvasWidth != null && canvasWidth > 0) {
            MeasureSpec.makeMeasureSpec(canvasWidth, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(canvasWidthSpec, heightMeasureSpec)
    }
}
