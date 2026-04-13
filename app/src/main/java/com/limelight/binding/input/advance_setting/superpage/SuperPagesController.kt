package com.limelight.binding.input.advance_setting.superpage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout

import com.limelight.R

class SuperPagesController(
    private val superPagesBox: FrameLayout,
    private val context: Context
) {
    enum class BoxPosition {
        Right,
        Left
    }

    private var boxPosition = BoxPosition.Right
    private val rightListener: SuperPageLayout.DoubleFingerSwipeListener
    private val leftListener: SuperPageLayout.DoubleFingerSwipeListener
    val pageNull: SuperPageLayout
    var pageNow: SuperPageLayout
        private set

    private var openingPage: SuperPageLayout? = null
    private var closingPage: SuperPageLayout? = null

    init {
        rightListener = object : SuperPageLayout.DoubleFingerSwipeListener {
            override fun onRightSwipe() {}
            override fun onLeftSwipe() {
                setPosition(BoxPosition.Left)
            }
        }
        leftListener = object : SuperPageLayout.DoubleFingerSwipeListener {
            override fun onRightSwipe() {
                setPosition(BoxPosition.Right)
            }
            override fun onLeftSwipe() {}
        }
        pageNull = LayoutInflater.from(context).inflate(R.layout.page_null, null) as SuperPageLayout
        pageNow = pageNull
    }

    fun setPosition(position: BoxPosition) {
        openingPage?.endAnimator()
        closingPage?.endAnimator()

        val previousPosition = getVisiblePosition(pageNow)
        boxPosition = position
        val nextPosition = getVisiblePosition(pageNow)
        openingPage = pageNow
        pageNow.startAnimator(previousPosition.toFloat(), nextPosition.toFloat(), object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                pageNow.x = nextPosition.toFloat()
                val doubleFingerSwipeListener = if (boxPosition == BoxPosition.Right) rightListener else leftListener
                pageNow.setDoubleFingerSwipeListener(doubleFingerSwipeListener)
                openingPage = null
            }
        })
    }

    fun openNewPage(pageNew: SuperPageLayout) {
        if (pageNew === pageNow) return

        closingPage?.endAnimator()
        openingPage?.endAnimator()

        closingPage = pageNow
        val closingPagePreviousPosition = getVisiblePosition(closingPage!!)
        val closingPageNextPosition = getHidePosition(closingPage!!)
        closingPage!!.startAnimator(closingPagePreviousPosition.toFloat(), closingPageNextPosition.toFloat(), object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                closingPage!!.x = closingPageNextPosition.toFloat()
                superPagesBox.removeView(closingPage)
                closingPage = null
            }
        })

        openingPage = pageNew
        val layoutParams = FrameLayout.LayoutParams(
            dpToPx(openingPage!!.tag.toString().toInt()),
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = dpToPx(20)
            bottomMargin = dpToPx(20)
        }
        superPagesBox.addView(openingPage, layoutParams)
        openingPage!!.setDoubleFingerSwipeListener(if (boxPosition == BoxPosition.Right) rightListener else leftListener)
        val previousPosition = getHidePosition(openingPage!!)
        val nextPosition = getVisiblePosition(openingPage!!)
        openingPage!!.x = previousPosition.toFloat()

        openingPage!!.startAnimator(previousPosition.toFloat(), nextPosition.toFloat(), object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                openingPage!!.x = nextPosition.toFloat()
                openingPage = null
            }
        })

        pageNew.lastPage = pageNow
        pageNow = pageNew
    }

    fun returnOperation() {
        pageNow.pageReturn()
    }

    private fun getHidePosition(page: SuperPageLayout): Int {
        return if (boxPosition == BoxPosition.Right) {
            superPagesBox.width
        } else {
            -dpToPx(page.tag.toString().toInt())
        }
    }

    private fun getVisiblePosition(page: SuperPageLayout): Int {
        return if (boxPosition == BoxPosition.Right) {
            superPagesBox.width - dpToPx(20) - dpToPx(page.tag.toString().toInt())
        } else {
            dpToPx(20)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
