package com.limelight.binding.audio

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import kotlin.math.abs

internal class MicrophoneButtonPositionController(
    context: Context,
    private val button: View,
    private val container: ViewGroup
) : View.OnTouchListener, View.OnLayoutChangeListener {
    private val positionStore = MicrophoneButtonPositionStore(context)
    private val defaultPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val edgeInset = (EDGE_INSET_DP * context.resources.displayMetrics.density).toInt()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        button.setOnTouchListener(this)
        container.addOnLayoutChangeListener(this)
        button.post(::applyStoredPosition)
    }

    fun dispose() {
        button.setOnTouchListener(null)
        container.removeOnLayoutChangeListener(this)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = button.x
                startY = button.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val coordinates = MicrophoneButtonPlacement.clampCustom(
                        x = startX + deltaX,
                        y = startY + deltaY,
                        containerWidth = container.width,
                        containerHeight = container.height,
                        buttonWidth = button.width,
                        buttonHeight = button.height,
                        edgeInset = edgeInset
                    )
                    button.x = coordinates.x.toFloat()
                    button.y = coordinates.y.toFloat()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    saveCustomPosition()
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                applyStoredPosition()
                return true
            }
        }
        return false
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
            button.post(::applyStoredPosition)
        }
    }

    private fun applyStoredPosition() {
        if (dragging || container.width <= 0 || container.height <= 0 || button.width <= 0 || button.height <= 0) {
            return
        }
        val hasCustomPosition = positionStore.hasCustomPosition()
        val coordinates = MicrophoneButtonPlacement.resolve(
            position = if (hasCustomPosition) {
                MicrophoneButtonPlacement.POSITION_CUSTOM
            } else {
                defaultPreferences.getString(KEY_POSITION, MicrophoneButtonPlacement.DEFAULT_POSITION)
                    ?: MicrophoneButtonPlacement.DEFAULT_POSITION
            },
            normalizedX = positionStore.customX(),
            normalizedY = positionStore.customY(),
            containerWidth = container.width,
            containerHeight = container.height,
            buttonWidth = button.width,
            buttonHeight = button.height,
            edgeInset = edgeInset
        )
        button.x = coordinates.x.toFloat()
        button.y = coordinates.y.toFloat()
    }

    private fun saveCustomPosition() {
        val coordinates = MicrophoneButtonPlacement.clampCustom(
            x = button.x,
            y = button.y,
            containerWidth = container.width,
            containerHeight = container.height,
            buttonWidth = button.width,
            buttonHeight = button.height,
            edgeInset = edgeInset
        )
        val normalized = MicrophoneButtonPlacement.normalize(
            coordinates = coordinates,
            containerWidth = container.width,
            containerHeight = container.height,
            buttonWidth = button.width,
            buttonHeight = button.height,
            edgeInset = edgeInset
        )
        positionStore.saveCustom(normalized)
    }

    companion object {
        const val KEY_SHOW_BUTTON = "checkbox_show_mic_button"
        const val KEY_POSITION = MicrophoneButtonPositionStore.PREFERENCE_KEY
        private const val EDGE_INSET_DP = 10
    }
}
