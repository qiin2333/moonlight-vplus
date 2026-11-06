package com.limelight.binding.input.advance_setting

import android.view.View
import com.limelight.R

/** One active category drives both content visibility and the navigation highlight. */
internal class DevicePickerNavigationController(root: View) {
    private enum class Category(val buttonId: Int, val contentId: Int) {
        ACTIONS(R.id.device_nav_actions, R.id.device_actions),
        KEYBOARD(R.id.device_nav_keyboard, R.id.keyboard_drawing),
        MOUSE(R.id.device_nav_mouse, R.id.mouse_drawing),
        GAMEPAD(R.id.device_nav_gamepad, R.id.gamepad_drawing)
    }

    private val buttons = Category.values().associateWith { root.findViewById<View>(it.buttonId) }
    private val contents = Category.values().associateWith { root.findViewById<View>(it.contentId) }
    private var availableCategories = Category.values().toSet()
    private var selectedCategory = Category.KEYBOARD

    val navigationViews: List<View> = buttons.values.toList()
    var onCategoryChanged: (() -> Unit)? = null

    init {
        buttons.forEach { (category, button) -> button.setOnClickListener { select(category) } }
        select(selectedCategory)
    }

    fun setAvailableDevices(keyboard: Boolean, mouse: Boolean, gamepad: Boolean) {
        availableCategories = buildSet {
            add(Category.ACTIONS)
            if (keyboard) add(Category.KEYBOARD)
            if (mouse) add(Category.MOUSE)
            if (gamepad) add(Category.GAMEPAD)
        }
        buttons.forEach { (category, button) ->
            button.visibility = if (category in availableCategories) View.VISIBLE else View.GONE
        }
        select(availableCategories.firstOrNull { it != Category.ACTIONS } ?: Category.ACTIONS)
    }

    private fun select(category: Category) {
        if (category !in availableCategories) return
        selectedCategory = category
        buttons.forEach { (destination, button) -> button.isSelected = destination == selectedCategory }
        contents.forEach { (destination, content) ->
            content.visibility = if (destination == selectedCategory) View.VISIBLE else View.GONE
        }
        onCategoryChanged?.invoke()
    }
}
