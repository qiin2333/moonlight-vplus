package com.limelight.ui

import android.view.InputDevice
import android.view.KeyEvent

/** 手柄/摇杆/方向键代表控制器导航;普通键盘输入不算。 */
fun KeyEvent.isGamepadNavigationSource(): Boolean =
    // Button sources share class bits with keyboards; match each complete source.
    isFromSource(InputDevice.SOURCE_GAMEPAD) ||
        isFromSource(InputDevice.SOURCE_DPAD) ||
        isFromSource(InputDevice.SOURCE_JOYSTICK) ||
        isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)

private val MENU_CONFIRM_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_BUTTON_A
)

/** 菜单/对话框统一的确认键集合。 */
fun isMenuConfirmKey(keyCode: Int): Boolean = keyCode in MENU_CONFIRM_KEYS
