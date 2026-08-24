package com.limelight.binding.audio

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlin.math.abs

internal class MicrophoneButtonPositionController(
    context: Context,
    private val button: View,
    private val container: ViewGroup
) : View.OnTouchListener, View.OnLayoutChangeListener {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
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
        val coordinates = MicrophoneButtonPlacement.resolve(
            position = preferences.getString(KEY_POSITION, MicrophoneButtonPlacement.DEFAULT_POSITION)
                ?: MicrophoneButtonPlacement.DEFAULT_POSITION,
            normalizedX = preferences.getFloat(KEY_CUSTOM_X, DEFAULT_CUSTOM_X),
            normalizedY = preferences.getFloat(KEY_CUSTOM_Y, DEFAULT_CUSTOM_Y),
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
        preferences.edit {
            putString(KEY_POSITION, MicrophoneButtonPlacement.POSITION_CUSTOM)
            putFloat(KEY_CUSTOM_X, normalized.x)
            putFloat(KEY_CUSTOM_Y, normalized.y)
        }
    }

    companion object {
        const val KEY_SHOW_BUTTON = "checkbox_show_mic_button"
        const val KEY_POSITION = "list_mic_button_position"
        private const val KEY_CUSTOM_X = "mic_button_custom_x"
        private const val KEY_CUSTOM_Y = "mic_button_custom_y"
        private const val DEFAULT_CUSTOM_X = 1f
        private const val DEFAULT_CUSTOM_Y = 0.5f
        private const val EDGE_INSET_DP = 10
    }
}
