package com.limelight.binding.input.advance_setting

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar

import com.limelight.R

class KeyboardUIController(
    private val keyboardLayout: FrameLayout,
    private val controllerManager: ControllerManager,
    context: Context
) {
    private val opacitySeekbar: SeekBar = keyboardLayout.findViewById(R.id.float_keyboard_seekbar)
    private val keyboard: LinearLayout = keyboardLayout.findViewById(R.id.keyboard_drawing)

    init {
        opacitySeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress * 0.1f
                keyboardLayout.alpha = alpha
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val touchListener = View.OnTouchListener { v, event ->
            val keyString = v.tag as String
            val keyCode = keyString.substring(1).toInt()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundResource(R.drawable.confirm_square_border)
                    controllerManager.elementController.sendKeyEvent(true, keyCode.toShort())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.setBackgroundResource(R.drawable.square_border)
                    controllerManager.elementController.sendKeyEvent(false, keyCode.toShort())
                    true
                }
                else -> false
            }
        }
        for (i in 0 until keyboard.childCount) {
            val keyboardRow = keyboard.getChildAt(i) as LinearLayout
            for (j in 0 until keyboardRow.childCount) {
                keyboardRow.getChildAt(j).setOnTouchListener(touchListener)
            }
        }
    }

    fun toggle() {
        keyboardLayout.visibility = if (keyboardLayout.visibility == View.VISIBLE) {
            View.INVISIBLE
        } else {
            View.VISIBLE
        }
    }
}
