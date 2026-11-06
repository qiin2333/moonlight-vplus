package com.limelight.binding.input.advance_setting

import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.view.doOnLayout
import com.limelight.R

/** Persistent shortcuts to the device layouts in the horizontal picker. */
internal class DevicePickerNavigationController(root: View) {
    private val scroll: HorizontalScrollView = root.findViewById(R.id.device_scroll)
    private val destinations = listOf(
        R.id.device_nav_actions to R.id.device_actions,
        R.id.device_nav_keyboard to R.id.keyboard_drawing,
        R.id.device_nav_mouse to R.id.mouse_drawing,
        R.id.device_nav_gamepad to R.id.gamepad_drawing
    ).map { (buttonId, targetId) ->
        root.findViewById<View>(buttonId) to root.findViewById<View>(targetId)
    }

    val navigationViews: List<View> = destinations.map { it.first }
    private var selectedTarget: View? = null

    init {
        destinations.forEach { (button, target) ->
            button.setOnClickListener {
                select(target)
                // Positions are measured again after layout, including when a
                // picker variant hides one of the preceding device layouts.
                scroll.post {
                    if (target.visibility == View.VISIBLE) {
                        scroll.smoothScrollTo(target.left, 0)
                    }
                }
            }
        }
        scroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                selectedTarget?.let { target -> scroll.post { scroll.scrollTo(target.left, 0) } }
            }
        }
    }

    fun refresh() {
        destinations.forEach { (button, target) ->
            button.visibility = if (target.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        val initialTarget = destinations.drop(1).firstOrNull { it.second.visibility == View.VISIBLE }
            ?.second ?: destinations.first().second
        select(initialTarget)
        scroll.doOnLayout { scroll.scrollTo(initialTarget.left, 0) }
    }

    private fun select(target: View) {
        selectedTarget = target
        destinations.forEach { (button, destination) -> button.isSelected = destination === target }
    }
}
