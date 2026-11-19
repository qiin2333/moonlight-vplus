package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.min

/** Keeps the keyboard picker inside the visible display on oversized parents. */
class KeyboardKeyPickerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requestedWidth = MeasureSpec.getSize(widthMeasureSpec)
        val displayWidth = resources.displayMetrics.widthPixels
        val boundedWidth = when {
            requestedWidth > 0 -> min(requestedWidth, displayWidth)
            else -> displayWidth
        }.coerceAtLeast(1)
        val boundedWidthSpec = MeasureSpec.makeMeasureSpec(
            boundedWidth,
            MeasureSpec.EXACTLY
        )
        super.onMeasure(boundedWidthSpec, heightMeasureSpec)
        setMeasuredDimension(boundedWidth, measuredHeight)
    }
}
