package com.limelight.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator

import com.limelight.R

import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 悬浮球管理器核心类
 */
class FloatBallManager constructor(
        context: Context,
        sizeInDp: Int = DEFAULT_BALL_SIZE,
        opacityPercent: Int = DEFAULT_OPACITY,
        autoHideDelayMs: Long = DEFAULT_AUTO_HIDE_DELAY,
        private val enableEdgeSnap: Boolean = true
) {
    private var mContextRef: WeakReference<Context> = WeakReference(context)
    private var mWindowManager: WindowManager? = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var mLayoutParams: WindowManager.LayoutParams
    private var mFloatBall: View? = null
    private val mSharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var screenWidth = 0
    private var screenHeight = 0
    private val ballSize: Int = dip2px(sizeInDp.toFloat())
    private val ballOpacity: Int = opacityPercent
    private val autoHideDelay: Long = autoHideDelayMs

    private var mListener: OnFloatBallInteractListener? = null

    private var isDragging = false
    private var isFlinging = false
    private var isHalfShow = false
    private var downX = 0f
    private var downY = 0f
    private var originalX = 0f
    private var originalY = 0f

    // 核心锚点：永远记录球体完全显示时的安全坐标
    private var lastSavedX = 0
    private var lastSavedY = 0

    private val mHandler = Handler(context.mainLooper)
    private val mAutoHideRunnable = AutoHideRunnable()
    private val mComponentCallbacks: ComponentCallbacks

    // 手势检测器
    private val mGestureDetector: GestureDetector

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }
    private var currentSide = Side.RIGHT

    // 相对位置比例，用于屏幕旋转和非吸附模式下的位置记忆
    private var relativeX = 1.0f
    private var relativeY = 0.5f

    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    interface OnFloatBallInteractListener {
        fun onSingleClick()
        fun onDoubleClick()
        fun onLongClick()
        fun onSwipe(direction: SwipeDirection)
    }

    init {
        mGestureDetector = initGestureDetector(context)

        mComponentCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                handleConfigurationChanged()
            }
            override fun onLowMemory() {}
        }
        context.registerComponentCallbacks(mComponentCallbacks)

        updateScreenSize()
        initFloatBallView()
        initLayoutParams()

        // 放在最后一步，确保所有参数初始化完毕后再恢复位置
        restoreLastPosition()
    }

    private fun getContext(): Context? = mContextRef.get()

    /**
     * 初始化手势检测器
     */
    private fun initGestureDetector(context: Context): GestureDetector {
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                mListener?.onSingleClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mListener?.onDoubleClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) mListener?.onLongClick()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                if (abs(e2.eventTime - e1.eventTime) > 300) return false

                val deltaX = e2.rawX - e1.rawX
                val deltaY = e2.rawY - e1.rawY
                val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()

                // 如果滑动距离小于 30dp，或者是速度不够，则认为是普通拖拽时的手抖，拒绝识别
                if (distance < dip2px(30f) || (abs(velocityX) < 500 && abs(velocityY) < 500)) {
                    return false
                }

                isFlinging = true
                if (mListener != null) {
                    // 判断是横向还是纵向为主
                    if (abs(deltaX) > abs(deltaY)) {
                        mListener?.onSwipe(if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT)
                    } else {
                        mListener?.onSwipe(if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP)
                    }
                }
                return true
            }
        })
    }

    private fun handleConfigurationChanged() {
        if (mFloatBall == null) return
        mFloatBall?.viewTreeObserver?.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        mFloatBall?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                        updateScreenSize()
                        if (!isDragging) {
                            val wasHalfShow = isHalfShow

                            // 1. 先强制恢复到完全显示，根据 relativeX/Y 计算新坐标
                            isHalfShow = false
                            applyRelativePosition()

                            // 2. 立即保存这个新的安全坐标
                            savePosition(mLayoutParams.x, mLayoutParams.y, wasHalfShow)

                            // 3. 如果之前是半隐藏，重新应用半隐藏
                            if (wasHalfShow) {
                                applyHalfShowPosition()
                            }
                        }
                    }
                })
    }

    /**
     * 计算当前坐标在屏幕中的相对比例 (0.0 - 1.0)
     * 同时也负责更新 currentSide
     */
    private fun calculateRelativePosition(x: Int, y: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return

        // 更新 Side
        currentSide = when {
            x <= 0 -> Side.LEFT
            x >= screenWidth - ballSize -> Side.RIGHT
            y <= 0 -> Side.TOP
            y >= screenHeight - ballSize -> Side.BOTTOM
            else -> currentSide
        }

        // 计算相对比例
        relativeX = x.toFloat() / (screenWidth - ballSize)
        relativeY = y.toFloat() / (screenHeight - ballSize)

        // 限制范围防止溢出
        relativeX = max(0f, min(1f, relativeX))
        relativeY = max(0f, min(1f, relativeY))
    }

    /**
     * 根据 relativeX/Y 和 屏幕尺寸，将球放到正确的位置
     */
    private fun applyRelativePosition() {
        if (enableEdgeSnap) {
            // 启用吸附：强制贴边
            when (currentSide) {
                Side.LEFT -> { mLayoutParams.x = 0; mLayoutParams.y = (relativeY * (screenHeight - ballSize)).toInt() }
                Side.RIGHT -> { mLayoutParams.x = screenWidth - ballSize; mLayoutParams.y = (relativeY * (screenHeight - ballSize)).toInt() }
                Side.TOP -> { mLayoutParams.y = 0; mLayoutParams.x = (relativeX * (screenWidth - ballSize)).toInt() }
                Side.BOTTOM -> { mLayoutParams.y = screenHeight - ballSize; mLayoutParams.x = (relativeX * (screenWidth - ballSize)).toInt() }
            }
        } else {
            // 不启用吸附：根据比例自由恢复
            mLayoutParams.x = (relativeX * (screenWidth - ballSize)).toInt()
            mLayoutParams.y = (relativeY * (screenHeight - ballSize)).toInt()
        }

        checkAndFixBounds() // 再次确保不越界
        updateViewPosition()
    }

    private fun checkAndFixBounds() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        // 如果不是半隐藏状态，必须限制在屏幕内
        if (mLayoutParams.x > screenWidth - ballSize) mLayoutParams.x = screenWidth - ballSize
        if (mLayoutParams.y > screenHeight - ballSize) mLayoutParams.y = screenHeight - ballSize
        if (mLayoutParams.x < 0 && !isHalfShow) mLayoutParams.x = 0
        if (mLayoutParams.y < 0 && !isHalfShow) mLayoutParams.y = 0
    }

    /**
     * 更新屏幕尺寸信息
     */
    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val context = getContext() ?: return
        val size = Point()
        mWindowManager?.defaultDisplay?.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y
    }

    private fun smoothScrollTo(targetX: Int, targetY: Int, onComplete: Runnable?) {
        val startX = mLayoutParams.x
        val startY = mLayoutParams.y
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 300
        animator.interpolator = OvershootInterpolator(1.0f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            mLayoutParams.x = (startX + (targetX - startX) * fraction).toInt()
            mLayoutParams.y = (startY + (targetY - startY) * fraction).toInt()
            updateViewPosition()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onComplete?.run()
            }
        })
        animator.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatBallView() {
        val context = getContext() ?: return
        mFloatBall = LayoutInflater.from(context).inflate(R.layout.float_ball_layout, null)
        mFloatBall?.setOnTouchListener { _, event -> handleTouchEvent(event) }
    }

    /**
     * 初始化悬浮球布局参数
     * 设置悬浮球类型、大小、透明度等窗口属性
     */
    @Suppress("DEPRECATION")
    private fun initLayoutParams() {
        mLayoutParams = WindowManager.LayoutParams().apply {
            // 关键：TYPE_APPLICATION 确保它作为 Activity 窗口的一部分，跟随 Activity 生命周期和旋转
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            // 关键：设置窗口格式为透明
            format = PixelFormat.TRANSLUCENT
            // 关键：FLAG_LAYOUT_IN_SCREEN 强制以屏幕物理左上角为 (0,0)
            // FLAG_NOT_FOCUSABLE 允许触摸穿透到后面，FLAG_LAYOUT_NO_LIMITS 允许超出屏幕边缘（用于半隐藏）
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

            // 适配刘海屏，确保在剪裁区域也能正常显示
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // 设置布局对齐方式为左上角
            gravity = Gravity.TOP or Gravity.START
            // 设置悬浮球宽高
            width = ballSize
            height = ballSize
        }
    }

    /**
     * 核心：恢复上次位置
     */
    private fun restoreLastPosition() {
        // 1. 读取保存的"锚点"坐标（完全显示时的坐标）
        lastSavedX = mSharedPreferences.getInt(KEY_LAST_X, screenWidth - ballSize)
        lastSavedY = mSharedPreferences.getInt(KEY_LAST_Y, screenHeight / 2)
        isHalfShow = mSharedPreferences.getBoolean(KEY_IS_HALF_SHOW, false)

        // 2. 赋值给 LayoutParams
        mLayoutParams.x = lastSavedX
        mLayoutParams.y = lastSavedY

        // 3. 关键：确保读取的坐标在当前屏幕范围内（防止屏幕尺寸变化导致的越界）
        checkAndFixBounds()

        // 4. 关键：根据恢复的坐标，更新 relativeX/Y，保证下次旋转屏幕时位置正确
        // 如果不更新，relativeX 可能是默认的 0.5，一旋转球就跑中间去了
        calculateRelativePosition(mLayoutParams.x, mLayoutParams.y)

        // 5. 如果是半隐藏，推到屏幕外
        if (isHalfShow) {
            applyHalfShowPosition()
        }

        updateViewPosition()
        Log.d(TAG, "恢复位置: x=${mLayoutParams.x}, y=${mLayoutParams.y}, isHalf=$isHalfShow")
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        mGestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                isFlinging = false
                downX = event.rawX
                downY = event.rawY

                mHandler.removeCallbacks(mAutoHideRunnable)
                if (isHalfShow) {
                    restoreFullShowPosition()
                }
                originalX = mLayoutParams.x.toFloat()
                originalY = mLayoutParams.y.toFloat()
            }

            MotionEvent.ACTION_MOVE -> {
                val moveX = event.rawX - downX
                val moveY = event.rawY - downY
                // 移动距离大于10像素视为拖拽或滑动过程
                if (abs(moveX) > 10 || abs(moveY) > 10) {
                    isDragging = true
                    mLayoutParams.x = (originalX + moveX).toInt()
                    mLayoutParams.y = (originalY + moveY).toInt()
                    updateViewPosition()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isFlinging) {
                    // 快速滑动：回弹原位
                    savePosition(originalX.toInt(), originalY.toInt(), false)
                    smoothScrollTo(originalX.toInt(), originalY.toInt()) { startAutoHideTimer() }
                } else if (isDragging) {
                    if (enableEdgeSnap) {
                        // 吸附模式：吸附并保存目标位置
                        attachToNearestEdge()
                    } else {
                        // 自由模式：保存当前位置，并同步更新相对比例
                        checkAndFixBounds() // 确保拖出屏幕外时被拉回
                        savePosition(mLayoutParams.x, mLayoutParams.y, false)
                        calculateRelativePosition(mLayoutParams.x, mLayoutParams.y) // 必须更新比例
                        startAutoHideTimer()
                    }
                } else {
                    // 单纯点击
                    startAutoHideTimer()
                }
                isDragging = false
                isFlinging = false
            }
        }
        return true
    }

    /**
     * 吸附到最近的边缘
     * 计算当前位置到上下左右四个边缘的距离，吸附到最近的边缘
     */
    private fun attachToNearestEdge() {
        val distanceToLeft = mLayoutParams.x
        val distanceToRight = screenWidth - (mLayoutParams.x + ballSize)
        val distanceToTop = mLayoutParams.y
        val distanceToBottom = screenHeight - (mLayoutParams.y + ballSize)

        val minDistance = min(min(distanceToLeft, distanceToRight), min(distanceToTop, distanceToBottom))

        var targetX = mLayoutParams.x
        var targetY = mLayoutParams.y

        when (minDistance) {
            distanceToLeft -> targetX = 0
            distanceToRight -> targetX = screenWidth - ballSize
            distanceToTop -> targetY = 0
            else -> targetY = screenHeight - ballSize
        }

        // 关键：动画开始前立即保存目标坐标，确保数据安全
        savePosition(targetX, targetY, false)
        calculateRelativePosition(targetX, targetY)

        smoothScrollTo(targetX, targetY) { startAutoHideTimer() }
    }

    /**
     * 应用半显示位置
     * 根据当前吸附的边缘，将悬浮球一半隐藏到屏幕外
     */
    private fun applyHalfShowPosition() {
        // 根据当前位置计算半隐藏坐标，但不保存到SharedPreferences
        when {
            mLayoutParams.x <= 0 -> mLayoutParams.x = -ballSize / 2
            mLayoutParams.x >= screenWidth - ballSize -> mLayoutParams.x = screenWidth - ballSize / 2
            mLayoutParams.y <= 0 -> mLayoutParams.y = -ballSize / 2
            mLayoutParams.y >= screenHeight - ballSize -> mLayoutParams.y = screenHeight - ballSize / 2
        }

        mFloatBall?.alpha = (ballOpacity / 100.0f) * 0.5f

        isHalfShow = true
        updateViewPosition()

        // 只更新状态标记，不更新坐标！
        mSharedPreferences.edit().putBoolean(KEY_IS_HALF_SHOW, true).apply()
    }

    /**
     * 恢复完全显示位置
     * 将半隐藏的悬浮球恢复到屏幕内完全显示
     */
    private fun restoreFullShowPosition() {
        if (!isHalfShow) return

        // 恢复到锚点坐标
        mLayoutParams.x = lastSavedX
        mLayoutParams.y = lastSavedY

        mFloatBall?.alpha = ballOpacity / 100.0f

        isHalfShow = false
        mSharedPreferences.edit().putBoolean(KEY_IS_HALF_SHOW, false).apply()
        updateViewPosition()
    }

    private fun updateViewPosition() {
        val context = getContext()
        if (mWindowManager == null || mFloatBall == null || context == null) return
        try {
            if (mFloatBall?.parent == null) {
                mWindowManager?.addView(mFloatBall, mLayoutParams)
            } else {
                mWindowManager?.updateViewLayout(mFloatBall, mLayoutParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update view failed: ${e.message}")
        }
    }

    /**
     * 保存位置的唯一入口
     * 只有在完全显示（Anchor）状态下才允许调用此方法更新坐标
     */
    private fun savePosition(x: Int, y: Int, isHalf: Boolean) {
        mSharedPreferences.edit()
                .putInt(KEY_LAST_X, x)
                .putInt(KEY_LAST_Y, y)
                .putBoolean(KEY_IS_HALF_SHOW, isHalf)
                .apply()

        // 同步内存数据
        lastSavedX = x
        lastSavedY = y
        isHalfShow = isHalf
    }

    private fun startAutoHideTimer() {
        mHandler.removeCallbacks(mAutoHideRunnable)
        if (autoHideDelay > 0) {
            mHandler.postDelayed(mAutoHideRunnable, autoHideDelay)
        }
    }

    fun showFloatBall() {
        updateViewPosition()
        startAutoHideTimer()
    }

    fun hideFloatBall() {
        try {
            mHandler.removeCallbacksAndMessages(null)
            if (mFloatBall != null && mFloatBall?.parent != null) {
                mWindowManager?.removeView(mFloatBall)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hide failed: ${e.message}")
        }
    }

    fun release() {
        val context = getContext()
        context?.unregisterComponentCallbacks(mComponentCallbacks)
        mContextRef.clear()
        mWindowManager = null
        mFloatBall = null
        mHandler.removeCallbacksAndMessages(null)
    }

    fun setOnFloatBallInteractListener(listener: OnFloatBallInteractListener?) {
        this.mListener = listener
    }

    private inner class AutoHideRunnable : Runnable {
        override fun run() {
            if (isDragging || isHalfShow || isFlinging) return
            applyHalfShowPosition()
        }
    }

    private fun dip2px(dpValue: Float): Int {
        val context = getContext() ?: return 0
        return (dpValue * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val TAG = "FloatBallManager"
        private const val PREFS_NAME = "FloatBallPrefs"
        private const val KEY_LAST_X = "lastX"
        private const val KEY_LAST_Y = "lastY"
        private const val KEY_IS_HALF_SHOW = "isHalfShow"

        // 默认配置
        private const val DEFAULT_AUTO_HIDE_DELAY = 2000L
        private const val DEFAULT_BALL_SIZE = 50
        private const val DEFAULT_OPACITY = 100
    }
}
