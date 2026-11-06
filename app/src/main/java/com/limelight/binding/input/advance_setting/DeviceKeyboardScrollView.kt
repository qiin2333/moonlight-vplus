package com.limelight.binding.input.advance_setting

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

/** Keep canvas pans inside the keyboard instead of moving the outer device strip. */
class DeviceKeyboardScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Still allow the enclosing vertical scroller on short screens.
                val dy = abs(event.y - downY)
                if (dy > touchSlop && dy > abs(event.x - downX)) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
