package com.limelight.ui

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.grid.GenericGridAdapter

/**
 * Selection Indicator Animator
 * Manages the animation and position calculation of the selection indicator
 */
class SelectionIndicatorAnimator(
    private val selectionIndicator: View,
    private var recyclerView: RecyclerView?,
    private var adapter: GenericGridAdapter<*>?,
    private val rootView: View
) {

    private var positionProvider: PositionProvider? = null

    fun interface PositionProvider {
        fun getCurrentPosition(): Int
    }

    fun setPositionProvider(provider: PositionProvider) {
        this.positionProvider = provider
    }

    fun updateReferences(recyclerView: RecyclerView, adapter: GenericGridAdapter<*>) {
        this.recyclerView = recyclerView
        this.adapter = adapter
    }

    fun moveToPosition(position: Int, isFirstFocus: Boolean = false) {
        if (!isValidPosition(position)) return
        val rv = recyclerView ?: return

        val viewHolder = rv.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            if (isFirstFocus) {
                setIndicatorPosition(viewHolder.itemView, withAnimation = false)
            } else {
                animateToView(viewHolder.itemView)
            }
        } else {
            scrollToPositionAndAnimate(position)
        }
    }

    fun updatePosition(position: Int): Boolean {
        if (!isValidPosition(position)) return false
        val rv = recyclerView ?: return false

        val viewHolder = rv.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            setIndicatorPositionFast(viewHolder.itemView)
            return true
        }
        return false
    }

    fun hideIndicator() {
        selectionIndicator.visibility = View.INVISIBLE
    }

    fun showIndicator() {
        selectionIndicator.visibility = View.VISIBLE
    }

    /**
     * 用布局坐标(left/top 沿父链累加到 rootView)计算 item 相对焦点框容器的位置。
     * 不能用 getLocationInWindow(): 它包含 view 自身的 scale/translate 矩阵,
     * 而按压(0.97)或焦点缩放动画进行中该值是瞬态的,会把焦点框定位到错误位置。
     */
    private fun layoutPositionInRoot(v: View): IntArray {
        val loc = intArrayOf(v.left, v.top)
        var parent = v.parent
        while (parent is View && parent !== rootView) {
            loc[0] += parent.left - parent.scrollX
            loc[1] += parent.top - parent.scrollY
            parent = parent.parent
        }
        return loc
    }

    private fun setIndicatorPositionFast(targetView: View) {
        updateCornerRadius(targetView)

        val targetLoc = layoutPositionInRoot(targetView)

        selectionIndicator.translationX = targetLoc[0].toFloat()
        selectionIndicator.translationY = targetLoc[1].toFloat()
        selectionIndicator.visibility = View.VISIBLE
    }

    private fun isValidPosition(position: Int): Boolean =
        position >= 0 && (adapter?.let { position < it.count } ?: false)

    private fun animateToView(targetView: View) {
        val rv = recyclerView ?: return
        if (rv.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            rv.postDelayed({
                val viewHolder = rv.findViewHolderForAdapterPosition(getCurrentPosition())
                if (viewHolder != null) {
                    animateToView(viewHolder.itemView)
                }
            }, RETRY_DELAY.toLong())
        } else {
            setIndicatorPosition(targetView, withAnimation = true)
        }
    }

    private fun scrollToPositionAndAnimate(position: Int) {
        val rv = recyclerView ?: return
        selectionIndicator.visibility = View.INVISIBLE
        rv.smoothScrollToPosition(position)

        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    recyclerView.removeOnScrollListener(this)

                    recyclerView.postDelayed({
                        val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                        if (viewHolder != null) {
                            setIndicatorPosition(viewHolder.itemView, withAnimation = true)
                            addScaleAnimation()
                        }
                    }, SCROLL_WAIT_DELAY.toLong())
                }
            }
        }

        rv.addOnScrollListener(scrollListener)
    }

    private fun setIndicatorPosition(targetView: View, withAnimation: Boolean) {
        updateCornerRadius(targetView)

        val targetLoc = layoutPositionInRoot(targetView)
        val targetX = targetLoc[0].toFloat()
        val targetY = targetLoc[1].toFloat()

        val targetWidth = targetView.width
        val targetHeight = targetView.height

        val params = selectionIndicator.layoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            selectionIndicator.layoutParams = params
        }

        selectionIndicator.visibility = View.VISIBLE

        if (withAnimation) {
            selectionIndicator.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(NORMAL_ANIMATION_DURATION.toLong().coerceAtMost(120))
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } else {
            selectionIndicator.translationX = targetX
            selectionIndicator.translationY = targetY
        }
    }

    private fun addScaleAnimation() {
        selectionIndicator.scaleX = 0.8f
        selectionIndicator.scaleY = 0.8f
        selectionIndicator.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(SCALE_ANIMATION_DURATION.toLong())
            .start()
    }

    private fun updateCornerRadius(targetView: View) {
        // The indicator follows the item container, while the artwork is inset by
        // the container padding. Adding that inset keeps both corner arcs concentric.
        val artworkRadius = rootView.resources.getDimension(R.dimen.corner_radius_small)
        val outerRadius = artworkRadius + targetView.paddingLeft
        val background = selectionIndicator.background.mutate() as? LayerDrawable ?: return

        for (index in 0 until background.numberOfLayers) {
            (background.getDrawable(index) as? GradientDrawable)?.cornerRadius = outerRadius
        }
    }

    private fun getCurrentPosition(): Int = positionProvider?.getCurrentPosition() ?: -1

    companion object {
        private const val NORMAL_ANIMATION_DURATION = 200
        private const val SCALE_ANIMATION_DURATION = 150
        private const val SCROLL_WAIT_DELAY = 50
        private const val RETRY_DELAY = 100
    }
}
