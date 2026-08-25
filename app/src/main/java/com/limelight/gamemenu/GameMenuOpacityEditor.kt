package com.limelight.gamemenu

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.ComponentDialog
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.UiDismissKeyHandler

internal object GameMenuOpacityEditor {
    private const val OPACITY_STEP = 3
    private const val POPUP_WIDTH_DP = 48
    private const val POPUP_HEIGHT_DP = 148
    private const val POPUP_MARGIN_DP = 2

    @Suppress("DEPRECATION")
    fun show(
        context: Context,
        anchor: GameMenuOpacityAnchor,
        initialOpacity: Int,
        onOpacityChange: (Int) -> Unit,
        onOpacityChangeFinished: (Int) -> Unit
    ): ComponentDialog {
        val content = LayoutInflater.from(context).inflate(
            R.layout.dialog_game_menu_opacity,
            FrameLayout(context),
            false
        )
        val seekBar = content.findViewById<SeekBar>(R.id.game_menu_opacity_seekbar)
        val minimum = PreferenceConfiguration.MIN_GAME_MENU_OPACITY
        val maximum = PreferenceConfiguration.MAX_GAME_MENU_OPACITY
        val opacityStops = buildOpacityStops(minimum, maximum)

        fun currentOpacity(): Int = seekBar.progress + minimum
        fun nearestOpacity(opacity: Int): Int = opacityStops.minBy { stop ->
            kotlin.math.abs(stop - opacity)
        }

        seekBar.max = maximum - minimum
        seekBar.progress = nearestOpacity(
            initialOpacity.coerceIn(minimum, maximum)
        ) - minimum
        seekBar.keyProgressIncrement = OPACITY_STEP
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val snappedProgress = nearestOpacity(progress + minimum) - minimum
                if (snappedProgress != progress) {
                    seekBar.progress = snappedProgress
                    return
                }
                onOpacityChange(currentOpacity())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                onOpacityChangeFinished(currentOpacity())
            }
        })
        seekBar.setOnKeyListener { _, keyCode, event ->
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                else -> return@setOnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val currentIndex = opacityStops.indexOf(currentOpacity()).coerceAtLeast(0)
                    val nextIndex = (currentIndex + direction).coerceIn(opacityStops.indices)
                    seekBar.progress = opacityStops[nextIndex] - minimum
                    true
                }
                KeyEvent.ACTION_UP -> {
                    onOpacityChangeFinished(currentOpacity())
                    true
                }
                else -> false
            }
        }

        val dialog = ComponentDialog(context, R.style.GameMenuOpacityPopupStyle)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            UiDismissKeyHandler.handle(event.action, keyCode, dialog::cancel)
        }

        val window = requireNotNull(dialog.window)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        (context as? Activity)?.window?.let { hostWindow ->
            window.decorView.systemUiVisibility = hostWindow.decorView.systemUiVisibility
            if (hostWindow.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        dialog.show()
        positionPopup(context, window, anchor)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        seekBar.post { seekBar.requestFocus() }
        return dialog
    }

    private fun buildOpacityStops(minimum: Int, maximum: Int): List<Int> = buildSet {
        add(minimum)
        add(maximum)
        var opacity = PreferenceConfiguration.DEFAULT_GAME_MENU_OPACITY
        while (opacity >= minimum) {
            add(opacity)
            opacity -= OPACITY_STEP
        }
        opacity = PreferenceConfiguration.DEFAULT_GAME_MENU_OPACITY + OPACITY_STEP
        while (opacity <= maximum) {
            add(opacity)
            opacity += OPACITY_STEP
        }
    }.sorted()

    private fun positionPopup(
        context: Context,
        window: android.view.Window,
        anchor: GameMenuOpacityAnchor
    ) {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val popupWidth = (POPUP_WIDTH_DP * density + 0.5f).toInt()
        val popupHeight = (POPUP_HEIGHT_DP * density + 0.5f).toInt()
        val popupMargin = (POPUP_MARGIN_DP * density + 0.5f).toInt()
        window.attributes = window.attributes.apply {
            width = popupWidth
            height = popupHeight
            gravity = Gravity.TOP or Gravity.START
            x = (anchor.centerX - popupWidth / 2).coerceIn(
                0,
                (screenWidth - popupWidth).coerceAtLeast(0)
            )
            y = (anchor.bottomY + popupMargin).coerceIn(
                0,
                (screenHeight - popupHeight).coerceAtLeast(0)
            )
            dimAmount = 0f
        }
    }
}
