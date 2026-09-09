package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/** Keeps horizontal key-row drags inside the row instead of moving the outer Crown page. */
class CrownKeyHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
