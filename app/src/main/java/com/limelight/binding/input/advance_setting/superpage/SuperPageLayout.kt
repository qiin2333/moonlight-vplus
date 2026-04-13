package com.limelight.binding.input.advance_setting.superpage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

open class SuperPageLayout : FrameLayout {

    interface DoubleFingerSwipeListener {
        fun onRightSwipe()
        fun onLeftSwipe()
    }

    interface ReturnListener {
        fun returnCallBack()
    }

    private var startX = 0f
    private var isTwoFingerSwipe = false
    private var isSwipeActionDone = false
    private var doubleFingerSwipeListener: DoubleFingerSwipeListener? = null
    private var returnListener: ReturnListener? = null
    private var disableTouch = false
    private var animator: ObjectAnimator? = null
    var lastPage: SuperPageLayout? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (disableTouch) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                isTwoFingerSwipe = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    isTwoFingerSwipe = true
                    startX = ev.getX(1)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerSwipe && ev.pointerCount == 2) {
                    val diffX = ev.getX(1) - startX
                    if (Math.abs(diffX) > SWIPE_THRESHOLD) {
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount == 2) {
                    isTwoFingerSwipe = false
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTwoFingerSwipe && doubleFingerSwipeListener != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val diffX = event.getX(1) - startX
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && !isSwipeActionDone) {
                        if (diffX > 0) {
                            doubleFingerSwipeListener!!.onRightSwipe()
                        } else {
                            doubleFingerSwipeListener!!.onLeftSwipe()
                        }
                        isSwipeActionDone = true
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isTwoFingerSwipe = false
                    isSwipeActionDone = false
                }
            }
        } else {
            return super.onTouchEvent(event)
        }
        return true
    }

    fun setDoubleFingerSwipeListener(listener: DoubleFingerSwipeListener?) {
        this.doubleFingerSwipeListener = listener
    }

    fun pageReturn() {
        returnListener?.returnCallBack()
    }

    fun setPageReturnListener(listener: ReturnListener?) {
        this.returnListener = listener
    }

    fun startAnimator(startX: Float, endX: Float, animatorListenerAdapter: AnimatorListenerAdapter) {
        animator = ObjectAnimator.ofFloat(this, "translationX", startX, endX).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            addListener(animatorListenerAdapter)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    disableTouch = false
                }
            })
        }
        disableTouch = true
        animator!!.start()
    }

    fun endAnimator() {
        animator?.end()
        animator = null
    }

    companion object {
        private const val SWIPE_THRESHOLD = 70
    }
}
