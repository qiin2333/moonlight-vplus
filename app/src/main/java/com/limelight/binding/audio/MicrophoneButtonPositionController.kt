package com.limelight.binding.audio

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

internal class MicrophoneButtonPositionController private constructor(
    context: Context,
    private val button: View,
    private val container: ViewGroup
) : View.OnTouchListener, View.OnLayoutChangeListener {
    private val positionStore = MicrophoneButtonPositionStore(context)
    private val buttonPreferences = MicrophoneButtonPreferences(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val edgeInset = (EDGE_INSET_DP * context.resources.displayMetrics.density).roundToInt()
    private val applyPositionRunnable = Runnable(::applyStoredPosition)

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var trackingTouch = false
    private var gestureViewport: MicrophoneButtonViewport? = null
    private var disposed = false

    init {
        button.setOnTouchListener(this)
        button.addOnLayoutChangeListener(this)
        container.addOnLayoutChangeListener(this)
        scheduleApplyStoredPosition()
    }

    fun dispose() {
        disposed = true
        resetTouchState()
        button.removeCallbacks(applyPositionRunnable)
        button.setOnTouchListener(null)
        button.removeOnLayoutChangeListener(this)
        container.removeOnLayoutChangeListener(this)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (disposed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = button.x
                startY = button.y
                dragging = false
                trackingTouch = true
                gestureViewport = currentViewport()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val coordinates = MicrophoneButtonPlacement.clampCustom(
                        x = startX + deltaX,
                        y = startY + deltaY,
                        viewport = gestureViewport ?: currentViewport()
                    )
                    button.x = coordinates.x.toFloat()
                    button.y = coordinates.y.toFloat()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false
                if (dragging) {
                    saveCustomPosition(gestureViewport ?: currentViewport())
                } else {
                    view.performClick()
                }
                resetTouchState()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetTouchState()
                scheduleApplyStoredPosition()
                return true
            }
        }
        return trackingTouch
    }

    override fun onLayoutChange(
        view: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            scheduleApplyStoredPosition()
        }
    }

    private fun scheduleApplyStoredPosition() {
        if (disposed) return
        button.removeCallbacks(applyPositionRunnable)
        button.post(applyPositionRunnable)
    }

    private fun applyStoredPosition() {
        if (disposed || dragging ||
            container.width <= 0 || container.height <= 0 || button.width <= 0 || button.height <= 0
        ) {
            return
        }
        val customPosition = positionStore.customPosition()
        val viewport = currentViewport()
        val coordinates = MicrophoneButtonPlacement.resolve(
            position = if (customPosition != null) {
                MicrophoneButtonPlacement.POSITION_CUSTOM
            } else {
                buttonPreferences.presetPosition()
            },
            customPosition = customPosition ?: defaultCustomPosition,
            viewport = viewport
        )
        button.x = coordinates.x.toFloat()
        button.y = coordinates.y.toFloat()
    }

    private fun saveCustomPosition(viewport: MicrophoneButtonViewport) {
        val coordinates = MicrophoneButtonPlacement.clampCustom(
            x = button.x,
            y = button.y,
            viewport = viewport
        )
        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = coordinates,
            viewport = viewport
        )
        positionStore.saveCustom(normalized)
    }

    private fun resetTouchState() {
        dragging = false
        trackingTouch = false
        gestureViewport = null
    }

    private fun currentViewport() = MicrophoneButtonViewport(
        containerWidth = container.width,
        containerHeight = container.height,
        buttonWidth = button.width,
        buttonHeight = button.height,
        edgeInset = edgeInset
    )

    companion object {
        private val defaultCustomPosition = MicrophoneButtonNormalizedPosition(1f, 0.5f)
        private const val EDGE_INSET_DP = 10

        fun attach(context: Context, button: View): MicrophoneButtonPositionController? {
            val container = button.parent as? ViewGroup ?: return null
            return MicrophoneButtonPositionController(context, button, container)
        }
    }
}
