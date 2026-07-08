package com.limelight

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout

import com.limelight.binding.video.JitterMonitor
import com.limelight.binding.video.JitterMonitorView
import com.limelight.preferences.PreferenceConfiguration

/**
 * 抖动监控浮层的生命周期管理器。
 *
 * 仅当 `prefConfig.enableJitterMonitor` 开启时才创建 View、置位 [JitterMonitor.enabled]、
 * 并启动 ~300ms 的重绘 tick；关闭时不建 View、不排 Handler、[JitterMonitor.enabled] 保持 false，
 * 对串流零开销。
 */
class JitterMonitorManager(
    private val activity: Activity,
    private val prefConfig: PreferenceConfiguration
) {
    private var monitorView: JitterMonitorView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var hasMonitorData = false
    private var isDragging = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragDeltaX = 0f
    private var dragDeltaY = 0f
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private val overlayPrefs: SharedPreferences by lazy {
        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    }

    private val refreshIntervalMs = 300L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            val view = monitorView
            if (view != null) {
                val snapshot = JitterMonitor.snapshot()
                hasMonitorData = snapshot != null
                if (snapshot != null) {
                    view.update(snapshot)
                }
                applyViewVisibility(view)
            }
            handler.postDelayed(this, refreshIntervalMs)
        }
    }

    /** 串流启动时调用。开启则挂载浮层并开始采集/重绘。 */
    fun initialize() {
        if (!prefConfig.enableJitterMonitor) {
            JitterMonitor.enabled = false
            return
        }
        JitterMonitor.enabled = true
        ensureViewAttached()
        applyVisibility()
    }

    private fun ensureViewAttached() {
        if (monitorView != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view = JitterMonitorView(activity)
        val lp = FrameLayout.LayoutParams(dp(244f), dp(178f)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(12f)
            topMargin = dp(48f)
        }
        view.layoutParams = lp
        view.elevation = dp(15f).toFloat()
        view.visibility = View.GONE
        setupDragging(view)
        root.addView(view)
        monitorView = view
        view.post { applySavedPosition(view) }
    }

    /** 依据当前偏好显隐浮层（配置变化/退出 PiP 后调用）。 */
    fun applyVisibility() {
        val show = prefConfig.enableJitterMonitor
        monitorView?.let {
            clampViewWithinParent(it)
            applyViewVisibility(it)
        }
        // 可见时确保 tick 在跑，隐藏时停 tick，避免空耗主线程
        if (show && monitorView != null) startTicking() else stopTicking()
    }

    /** 进入 PiP 时立即隐藏并停止重绘 tick。 */
    fun hideImmediate() {
        monitorView?.visibility = View.GONE
        stopTicking()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tickRunnable, refreshIntervalMs)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    /** 串流结束/销毁时调用：停止 tick、移除 View、关闭采集。 */
    fun destroy() {
        stopTicking()
        JitterMonitor.enabled = false
        monitorView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        monitorView = null
    }

    private fun applyViewVisibility(view: JitterMonitorView) {
        view.visibility = if (prefConfig.enableJitterMonitor && hasMonitorData) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging(view: JitterMonitorView) {
        view.isClickable = true
        view.isFocusable = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    convertGravityToMargins(v, lp)
                    dragDeltaX = event.rawX - lp.leftMargin
                    dragDeltaY = event.rawY - lp.topMargin
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedFarEnough =
                        kotlin.math.abs(event.rawX - dragStartRawX) > touchSlop ||
                                kotlin.math.abs(event.rawY - dragStartRawY) > touchSlop
                    if (movedFarEnough) {
                        isDragging = true
                    }
                    if (isDragging) {
                        moveViewWithinParent(v, event.rawX - dragDeltaX, event.rawY - dragDeltaY)
                        v.alpha = DRAG_ALPHA
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.alpha = 1f
                    if (isDragging) {
                        savePosition(v)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun convertGravityToMargins(view: View, lp: FrameLayout.LayoutParams) {
        if (lp.gravity == Gravity.NO_GRAVITY) return

        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationInWindow(viewLocation)
        (view.parent as? View)?.getLocationInWindow(parentLocation)

        lp.leftMargin = viewLocation[0] - parentLocation[0]
        lp.topMargin = viewLocation[1] - parentLocation[1]
        lp.gravity = Gravity.NO_GRAVITY
        view.layoutParams = lp
    }

    private fun moveViewWithinParent(view: View, requestedLeft: Float, requestedTop: Float) {
        val parent = view.parent as? View ?: return
        val maxLeft = (parent.width - view.width).coerceAtLeast(0)
        val maxTop = (parent.height - view.height).coerceAtLeast(0)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = requestedLeft.toInt().coerceIn(0, maxLeft)
        lp.topMargin = requestedTop.toInt().coerceIn(0, maxTop)
        lp.gravity = Gravity.NO_GRAVITY
        view.layoutParams = lp
    }

    private fun clampViewWithinParent(view: View) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity != Gravity.NO_GRAVITY) return
        moveViewWithinParent(view, lp.leftMargin.toFloat(), lp.topMargin.toFloat())
    }

    private fun applySavedPosition(view: View) {
        if (!overlayPrefs.getBoolean(KEY_HAS_CUSTOM_POSITION, false)) return

        moveViewWithinParent(
            view,
            overlayPrefs.getInt(KEY_LEFT_MARGIN, dp(12f)).toFloat(),
            overlayPrefs.getInt(KEY_TOP_MARGIN, dp(48f)).toFloat()
        )
    }

    private fun savePosition(view: View) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        overlayPrefs.edit()
            .putBoolean(KEY_HAS_CUSTOM_POSITION, true)
            .putInt(KEY_LEFT_MARGIN, lp.leftMargin)
            .putInt(KEY_TOP_MARGIN, lp.topMargin)
            .apply()
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics
    ).toInt()

    companion object {
        private const val PREFS_NAME = "jitter_monitor_overlay"
        private const val KEY_HAS_CUSTOM_POSITION = "has_custom_position"
        private const val KEY_LEFT_MARGIN = "left_margin"
        private const val KEY_TOP_MARGIN = "top_margin"
        private const val DRAG_ALPHA = 0.72f
    }
}
