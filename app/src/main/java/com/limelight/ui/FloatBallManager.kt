package com.limelight.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.R
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.sqrt

internal fun floatBallViewport(
    screenWidth: Int,
    screenHeight: Int,
    ballSize: Int,
    enableEdgeSnap: Boolean,
    safeInsetLeft: Int,
    safeInsetTop: Int,
    safeInsetRight: Int,
    safeInsetBottom: Int
) = FloatingButtonViewport(
    containerWidth = screenWidth,
    containerHeight = screenHeight,
    buttonWidth = ballSize,
    buttonHeight = ballSize,
    edgeInset = 0,
    leftInset = if (enableEdgeSnap) 0 else safeInsetLeft,
    topInset = if (enableEdgeSnap) 0 else safeInsetTop,
    rightInset = if (enableEdgeSnap) 0 else safeInsetRight,
    bottomInset = if (enableEdgeSnap) 0 else safeInsetBottom
)

/** Manages the in-stream float ball view, gestures, and device-local position. */
class FloatBallManager constructor(
    context: Context,
    sizeInDp: Int = DEFAULT_BALL_SIZE,
    opacityPercent: Int = DEFAULT_OPACITY,
    autoHideDelayMs: Long = DEFAULT_AUTO_HIDE_DELAY,
    private val enableEdgeSnap: Boolean = true,
    private val presetPosition: String = FloatingButtonPlacement.DEFAULT_POSITION
) {
    private var contextRef = WeakReference(context)
    private var windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var floatBall: View? = null
    private val positionStore = FloatBallPositionStore(context)

    private var screenWidth = 0
    private var screenHeight = 0
    private var safeInsetLeft = 0
    private var safeInsetTop = 0
    private var safeInsetRight = 0
    private var safeInsetBottom = 0
    private val ballSize = dip2px(sizeInDp.toFloat())
    private val ballOpacity = opacityPercent
    private val autoHideDelay = autoHideDelayMs
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var listener: OnFloatBallInteractListener? = null
    private var isDragging = false
    private var isFlinging = false
    private var isHalfShown = false
    private var downX = 0f
    private var downY = 0f
    private var originalX = 0f
    private var originalY = 0f

    private var lastSavedX = 0
    private var lastSavedY = 0
    private var anchorPosition = FloatingButtonNormalizedPosition(1f, 0.5f)
    private var currentEdge = FloatBallEdge.RIGHT
    private var snapAnimator: ValueAnimator? = null
    private var released = false

    private val handler = Handler(context.mainLooper)
    private val autoHideRunnable = AutoHideRunnable()
    private val componentCallbacks: ComponentCallbacks
    private val gestureDetector: GestureDetector

    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    interface OnFloatBallInteractListener {
        fun onSingleClick()
        fun onDoubleClick()
        fun onLongClick()
        fun onSwipe(direction: SwipeDirection)
    }

    init {
        gestureDetector = initGestureDetector(context)
        componentCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                handleConfigurationChanged()
            }

            override fun onLowMemory() = Unit
        }
        context.registerComponentCallbacks(componentCallbacks)

        updateScreenSize()
        initFloatBallView()
        initLayoutParams()
        restoreStoredPosition()
    }

    private fun getContext(): Context? = contextRef.get()

    private fun initGestureDetector(context: Context): GestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onSingleClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                listener?.onDoubleClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) listener?.onLongClick()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || abs(e2.eventTime - e1.eventTime) > MAX_SWIPE_DURATION_MS) {
                    return false
                }

                val deltaX = e2.rawX - e1.rawX
                val deltaY = e2.rawY - e1.rawY
                val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                if (distance < dip2px(MIN_SWIPE_DISTANCE_DP) ||
                    (abs(velocityX) < MIN_SWIPE_VELOCITY && abs(velocityY) < MIN_SWIPE_VELOCITY)
                ) {
                    return false
                }

                isFlinging = true
                listener?.onSwipe(
                    if (abs(deltaX) > abs(deltaY)) {
                        if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                    } else {
                        if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                    }
                )
                return true
            }
        })

    private fun handleConfigurationChanged() {
        val view = floatBall?.takeIf { it.parent != null } ?: return
        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    cancelSnapAnimation()
                    updateScreenSize()
                    if (!isDragging) {
                        val wasHalfShown = isHalfShown
                        isHalfShown = false
                        applyAnchorPosition(updateView = view.parent != null)
                        if (wasHalfShown && enableEdgeSnap) {
                            applyHalfShowPosition(updateView = view.parent != null)
                        }
                    }
                }
            }
        )
    }

    private fun currentViewport() = floatBallViewport(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        ballSize = ballSize,
        enableEdgeSnap = enableEdgeSnap,
        safeInsetLeft = safeInsetLeft,
        safeInsetTop = safeInsetTop,
        safeInsetRight = safeInsetRight,
        safeInsetBottom = safeInsetBottom
    )

    private fun applyAnchorPosition(updateView: Boolean = true) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val viewport = currentViewport()
        var coordinates = FloatingButtonPlacement.resolve(
            FloatingButtonPlacement.POSITION_CUSTOM,
            anchorPosition,
            viewport
        )
        if (enableEdgeSnap && currentEdge != FloatBallEdge.FREE) {
            coordinates = FloatBallPlacement.applyEdge(coordinates, currentEdge, viewport)
        }
        coordinates = FloatingButtonPlacement.clampCustom(
            coordinates.x.toFloat(),
            coordinates.y.toFloat(),
            viewport
        )
        lastSavedX = coordinates.x
        lastSavedY = coordinates.y
        layoutParams.x = coordinates.x
        layoutParams.y = coordinates.y
        if (updateView) updateViewPosition()
    }

    private fun restoreStoredPosition() {
        val storedHalfShown = positionStore.isHalfShown()
        val customPosition = positionStore.customPosition()
        val legacyPosition = positionStore.legacyPosition()
        val restored = when {
            customPosition != null -> customPosition
            legacyPosition != null -> FloatBallPlacement.migrateLegacy(
                legacyPosition,
                currentViewport(),
                enableEdgeSnap
            ).also { positionStore.saveCustom(it, storedHalfShown) }
            else -> FloatBallPlacement.presetPosition(presetPosition)
        }

        anchorPosition = restored.normalized
        currentEdge = when {
            !enableEdgeSnap -> FloatBallEdge.FREE
            restored.edge != FloatBallEdge.FREE -> restored.edge
            else -> {
                val coordinates = FloatingButtonPlacement.resolve(
                    FloatingButtonPlacement.POSITION_CUSTOM,
                    restored.normalized,
                    currentViewport()
                )
                FloatBallPlacement.nearestEdge(coordinates, currentViewport())
            }
        }
        applyAnchorPosition(updateView = false)

        isHalfShown = storedHalfShown && enableEdgeSnap
        if (isHalfShown) {
            applyHalfShowPosition(updateView = false)
        } else if (storedHalfShown) {
            positionStore.setHalfShown(false)
        }
    }

    private fun saveCustomPosition(
        coordinates: FloatingButtonCoordinates,
        edge: FloatBallEdge,
        halfShown: Boolean
    ) {
        val clamped = FloatingButtonPlacement.clampCustom(
            coordinates.x.toFloat(),
            coordinates.y.toFloat(),
            currentViewport()
        )
        anchorPosition = FloatingButtonPlacement.normalize(clamped, currentViewport())
        currentEdge = edge
        lastSavedX = clamped.x
        lastSavedY = clamped.y
        isHalfShown = halfShown
        positionStore.saveCustom(FloatBallStoredPosition(anchorPosition, edge), halfShown)
    }

    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
            screenWidth = size.x
            screenHeight = size.y
        }

        val activity = getContext() as? Activity
        val rootInsets = activity?.window?.decorView?.let(ViewCompat::getRootWindowInsets)
        val safeInsets = rootInsets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        safeInsetLeft = safeInsets?.left ?: 0
        safeInsetTop = safeInsets?.top ?: 0
        safeInsetRight = safeInsets?.right ?: 0
        safeInsetBottom = safeInsets?.bottom ?: 0
    }

    private fun smoothScrollTo(targetX: Int, targetY: Int, onComplete: Runnable?) {
        cancelSnapAnimation()
        val startX = layoutParams.x
        val startY = layoutParams.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var canceled = false
            duration = SNAP_ANIMATION_DURATION_MS
            interpolator = OvershootInterpolator(1.0f)
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val coordinates = FloatingButtonPlacement.clampCustom(
                    startX + (targetX - startX) * fraction,
                    startY + (targetY - startY) * fraction,
                    currentViewport()
                )
                layoutParams.x = coordinates.x
                layoutParams.y = coordinates.y
                updateViewPosition()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (snapAnimator === animation) snapAnimator = null
                    if (!canceled && !released) onComplete?.run()
                }
            })
            start()
        }
    }

    private fun cancelSnapAnimation() {
        snapAnimator?.cancel()
        snapAnimator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatBallView() {
        val context = getContext() ?: return
        floatBall = LayoutInflater.from(context).inflate(R.layout.float_ball_layout, null)
        floatBall?.setOnTouchListener { _, event -> handleTouchEvent(event) }
    }

    @Suppress("DEPRECATION")
    private fun initLayoutParams() {
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            width = ballSize
            height = ballSize
        }
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSnapAnimation()
                isDragging = false
                isFlinging = false
                downX = event.rawX
                downY = event.rawY
                handler.removeCallbacks(autoHideRunnable)
                if (isHalfShown) restoreFullShowPosition()
                originalX = layoutParams.x.toFloat()
                originalY = layoutParams.y.toFloat()
            }

            MotionEvent.ACTION_MOVE -> {
                val moveX = event.rawX - downX
                val moveY = event.rawY - downY
                if (!isDragging && (abs(moveX) > touchSlop || abs(moveY) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    val coordinates = FloatingButtonPlacement.clampCustom(
                        originalX + moveX,
                        originalY + moveY,
                        currentViewport()
                    )
                    layoutParams.x = coordinates.x
                    layoutParams.y = coordinates.y
                    updateViewPosition()
                }
            }

            MotionEvent.ACTION_UP -> finishTouchGesture()

            MotionEvent.ACTION_CANCEL -> {
                val coordinates = FloatingButtonPlacement.clampCustom(
                    originalX,
                    originalY,
                    currentViewport()
                )
                layoutParams.x = coordinates.x
                layoutParams.y = coordinates.y
                updateViewPosition()
                resetTouchState()
                startAutoHideTimer()
            }
        }
        return true
    }

    private fun finishTouchGesture() {
        if (isFlinging) {
            smoothScrollTo(originalX.toInt(), originalY.toInt()) { startAutoHideTimer() }
        } else if (isDragging) {
            if (enableEdgeSnap) {
                attachToNearestEdge()
            } else {
                val coordinates = FloatingButtonPlacement.clampCustom(
                    layoutParams.x.toFloat(),
                    layoutParams.y.toFloat(),
                    currentViewport()
                )
                layoutParams.x = coordinates.x
                layoutParams.y = coordinates.y
                saveCustomPosition(coordinates, FloatBallEdge.FREE, false)
                startAutoHideTimer()
            }
        } else {
            startAutoHideTimer()
        }
        resetTouchState()
    }

    private fun resetTouchState() {
        isDragging = false
        isFlinging = false
    }

    private fun attachToNearestEdge() {
        val current = FloatingButtonPlacement.clampCustom(
            layoutParams.x.toFloat(),
            layoutParams.y.toFloat(),
            currentViewport()
        )
        val (target, edge) = FloatBallPlacement.snapToNearestEdge(current, currentViewport())
        saveCustomPosition(target, edge, false)
        smoothScrollTo(target.x, target.y) { startAutoHideTimer() }
    }

    private fun applyHalfShowPosition(updateView: Boolean = true) {
        if (!enableEdgeSnap || currentEdge == FloatBallEdge.FREE) return
        val halfShown = FloatBallPlacement.halfShownCoordinates(
            FloatingButtonCoordinates(lastSavedX, lastSavedY),
            currentEdge,
            ballSize
        )
        layoutParams.x = halfShown.x
        layoutParams.y = halfShown.y
        floatBall?.alpha = (ballOpacity / 100.0f) * HALF_SHOWN_ALPHA_MULTIPLIER
        isHalfShown = true
        positionStore.setHalfShown(true)
        if (updateView) updateViewPosition()
    }

    private fun restoreFullShowPosition() {
        if (!isHalfShown) return
        layoutParams.x = lastSavedX
        layoutParams.y = lastSavedY
        floatBall?.alpha = ballOpacity / 100.0f
        isHalfShown = false
        positionStore.setHalfShown(false)
        updateViewPosition()
    }

    private fun updateViewPosition() {
        if (released) return
        val context = getContext()
        val view = floatBall
        if (view == null || context == null) return
        try {
            if (view.parent == null) {
                windowManager.addView(view, layoutParams)
            } else {
                windowManager.updateViewLayout(view, layoutParams)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Update view failed: ${e.message}")
        }
    }

    private fun startAutoHideTimer() {
        if (released) return
        handler.removeCallbacks(autoHideRunnable)
        if (autoHideDelay > 0) {
            handler.postDelayed(autoHideRunnable, autoHideDelay)
        }
    }

    fun showFloatBall() {
        if (released) return
        updateScreenSize()
        val wasHalfShown = isHalfShown
        isHalfShown = false
        applyAnchorPosition(updateView = false)
        if (wasHalfShown && enableEdgeSnap) {
            applyHalfShowPosition(updateView = false)
        }
        updateViewPosition()
        startAutoHideTimer()
    }

    fun hideFloatBall() {
        cancelSnapAnimation()
        handler.removeCallbacksAndMessages(null)
        val view = floatBall ?: return
        try {
            if (view.parent != null) windowManager.removeView(view)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Hide failed: ${e.message}")
        }
    }

    fun release() {
        hideFloatBall()
        released = true
        getContext()?.unregisterComponentCallbacks(componentCallbacks)
        listener = null
        floatBall = null
        contextRef.clear()
        handler.removeCallbacksAndMessages(null)
    }

    fun setOnFloatBallInteractListener(listener: OnFloatBallInteractListener?) {
        this.listener = listener
    }

    private inner class AutoHideRunnable : Runnable {
        override fun run() {
            if (isDragging || isHalfShown || isFlinging) return
            applyHalfShowPosition()
        }
    }

    private fun dip2px(dpValue: Float): Int {
        val context = getContext() ?: return 0
        return (dpValue * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    companion object {
        private const val TAG = "FloatBallManager"
        private const val DEFAULT_AUTO_HIDE_DELAY = 2000L
        private const val DEFAULT_BALL_SIZE = 50
        private const val DEFAULT_OPACITY = 100
        private const val SNAP_ANIMATION_DURATION_MS = 300L
        private const val MAX_SWIPE_DURATION_MS = 300L
        private const val MIN_SWIPE_DISTANCE_DP = 30f
        private const val MIN_SWIPE_VELOCITY = 500f
        private const val HALF_SHOWN_ALPHA_MULTIPLIER = 0.5f
    }
}
