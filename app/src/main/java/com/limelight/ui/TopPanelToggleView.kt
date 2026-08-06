package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** API 22-compatible TextView that renders one drawable at its visual center. */
class TopPanelToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    var centerDrawable: Drawable? = null
        set(value) {
            field?.callback = null
            field = value
            value?.callback = this
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = centerDrawable ?: return
        val drawableWidth = drawable.intrinsicWidth.coerceAtLeast(0)
        val drawableHeight = drawable.intrinsicHeight.coerceAtLeast(0)
        val left = (width - drawableWidth) / 2
        val top = (height - drawableHeight) / 2
        drawable.setBounds(left, top, left + drawableWidth, top + drawableHeight)
        drawable.draw(canvas)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        who === centerDrawable || super.verifyDrawable(who)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        centerDrawable?.takeIf { it.isStateful }?.state = drawableState
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        centerDrawable?.jumpToCurrentState()
    }
}
