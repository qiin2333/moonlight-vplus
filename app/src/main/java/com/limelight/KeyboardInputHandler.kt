@file:Suppress("DEPRECATION")
package com.limelight

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.services.KeyboardAccessibilityService

/**
 * 键盘/按键事件处理器。
 * 从 Game.java 提取，处理 handleKeyDown/handleKeyUp/handleKeyMultiple、
 * 特殊组合键、ESC 自定义映射和修饰键状态追踪。
 */
class KeyboardInputHandler(private val game: Game) {

    lateinit var keyboardTranslator: KeyboardTranslator

    private var modifierFlags = 0
    private var waitingForAllModifiersUp = false
    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastEscPressTime = 0L
    private var hasShownEscHint = false

    private val pressedKeys = HashSet<Int>()
    private var escState = 0 // 0 = 空闲，1 = ESC已按下，2 = 已进入组合键
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var escConfirmRunnable: Runnable

    private val toggleGrab = Runnable { game.setInputGrabState(!game.grabbedInput) }

    companion object {
        private const val ESC_DOUBLE_PRESS_INTERVAL = 500L // 500毫秒内按第二次ESC才有效
    }

    // Returns true if the key stroke was consumed
    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_META.toInt()
            else -> nonModifierKeyCode = androidKeyCode
        }

        if (down) {
            modifierFlags = modifierFlags or modifierMask
        } else {
            modifierFlags = modifierFlags and modifierMask.inv()
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                return true
            } else if (modifierFlags != 0) {
                return down
            } else {
                when (specialKeyCode) {
                    // Toggle input grab
                    KeyEvent.KEYCODE_Z -> {
                        val h = game.window.decorView.handler
                        h?.postDelayed(toggleGrab, 250)
                    }
                    // Quit
                    KeyEvent.KEYCODE_Q -> game.finish()
                    // Toggle cursor visibility
                    KeyEvent.KEYCODE_C -> {
                        if (!game.grabbedInput) {
                            game.inputCaptureProvider.enableCapture()
                            game.grabbedInput = true
                        }
                        game.cursorVisible = !game.cursorVisible
                        if (game.cursorVisible) {
                            game.inputCaptureProvider.showCursor()
                        } else {
                            game.inputCaptureProvider.hideCursor()
                        }
                    }
                }

                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
            (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
            (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
        ) {
            when (androidKeyCode) {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                    specialKeyCode = androidKeyCode
                    waitingForAllModifiersUp = true
                    return true
                }
                else -> return false
            }
        }

        return false
    }

    private fun getModifierState(event: KeyEvent): Byte {
        var modifier = getModifierState().toInt()
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        return modifier.toByte()
    }

    private fun getModifierState(): Byte = modifierFlags.toByte()

    fun clearModifierState() {
        modifierFlags = 0
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER -> {
                // 系统导航键/音量键/电源键直接跳过去重逻辑
            }
            else -> {
                val device = event.device
                if (!game.isEventFromAccessibilityService &&
                    KeyboardAccessibilityService.instance != null &&
                    (device != null && !device.isVirtual)
                ) {
                    return true
                }
            }
        }

        // 自定义组合键，只能其它+esc，esc+其它时，esc抬起时其它才会down
        val keyCode = event.keyCode
        pressedKeys.add(keyCode)
        if (game.prefConfig.enableCustomKeyMap) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                escState = 1
                escConfirmRunnable = Runnable {
                    if (escState == 1) {
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                        game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        escState = 0
                    }
                }
                handler.postDelayed(escConfirmRunnable, 200)
                return true
            }

            if (escState == 1) {
                handler.removeCallbacks(escConfirmRunnable)

                if (keyCode == KeyEvent.KEYCODE_Q) {
                    escState = 2
                    return true
                } else if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    escState = 2
                    val fKeyCode = KeyEvent.KEYCODE_F1 + (keyCode - KeyEvent.KEYCODE_1)
                    val translated = keyboardTranslator.translate(fKeyCode, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_0) {
                    escState = 2
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F10, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_MINUS) {
                    escState = 2
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F11, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_EQUALS) {
                    escState = 2
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F12, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                } else {
                    // 非自定义组合键，不做处理
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    escState = 0
                }
            }
        }

        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!game.prefConfig.mouseNavButtons) {
                game.conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        // 鼠标中键（同时影响触摸返回）
        if (game.touchInputHandler.detectMouseMiddle && eventSource == InputDevice.SOURCE_KEYBOARD &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (android.os.SystemClock.uptimeMillis() - game.touchInputHandler.lastMouseHoverTime < 250) {
                game.touchInputHandler.detectMouseMiddleDown = true
                game.touchInputHandler.detectMouseMiddle = false
                game.conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                return true
            }
        }

        var handled = false

        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = game.controllerHandler?.handleButtonDown(event) == true
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }

            if (!game.grabbedInput) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                    (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
                ) {
                    game.conn?.sendUtf8Text("" + unicodeChar.toChar())
                    return true
                }
                return false
            }

            if (event.repeatCount > 0) {
                return true
            }

            game.conn?.sendKeyboardInput(
                translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    fun handleKeyUp(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> {
                // 系统导航键直接跳过去重逻辑
            }
            else -> {
                val device = event.device
                if (!game.isEventFromAccessibilityService &&
                    KeyboardAccessibilityService.instance != null &&
                    (device != null && !device.isVirtual)
                ) {
                    return true
                }
            }
        }

        if (isPhysicalKeyboardConnected()) {
            // ESC键双击逻辑
            if (event.keyCode == game.prefConfig.escMenuKey && game.prefConfig.enableEscMenu) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastEscPressTime <= ESC_DOUBLE_PRESS_INTERVAL && hasShownEscHint) {
                    game.onBackPressed()
                    lastEscPressTime = 0
                    hasShownEscHint = false
                    return true
                } else {
                    var keyName = KeyEvent.keyCodeToString(game.prefConfig.escMenuKey)
                    if (keyName.startsWith("KEYCODE_")) {
                        keyName = keyName.substring("KEYCODE_".length)
                    }
                    Toast.makeText(game, game.getString(R.string.toast_press_again_to_open_menu, keyName), Toast.LENGTH_SHORT).show()
                    lastEscPressTime = currentTime
                    hasShownEscHint = true
                }
            }
        }

        val keyCode = event.keyCode
        pressedKeys.remove(keyCode)

        if (game.prefConfig.enableCustomKeyMap) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                handler.removeCallbacks(escConfirmRunnable)
                if (escState == 1) {
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    handler.postDelayed({
                        game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    }, 50)
                    escState = 0
                } else if (escState == 2) {
                    escState = 0
                } else {
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    escState = 0
                }
                return true
            }
            if (escState == 2) {
                if (keyCode == KeyEvent.KEYCODE_Q) {
                    game.onBackPressed()
                    return true
                }
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val fKeyCode = KeyEvent.KEYCODE_F1 + (keyCode - KeyEvent.KEYCODE_1)
                    val translated = keyboardTranslator.translate(fKeyCode, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_0) {
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F10, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_MINUS) {
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F11, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_EQUALS) {
                    val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F12, event.deviceId)
                    game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                    return true
                }
            }
        }

        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!game.prefConfig.mouseNavButtons) {
                game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        // 鼠标中键
        if (game.touchInputHandler.detectMouseMiddleDown && eventSource == InputDevice.SOURCE_KEYBOARD &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            game.touchInputHandler.detectMouseMiddleDown = false
            game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = game.controllerHandler?.handleButtonUp(event) == true
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }

            if (!game.grabbedInput) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                        (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
            }

            game.conn?.sendKeyboardInput(
                translated, KeyboardPacket.KEY_UP, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    fun handleKeyMultiple(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || event.characters == null) {
            return false
        }
        game.conn?.sendUtf8Text(event.characters)
        return true
    }

    private fun isPhysicalKeyboardConnected(): Boolean {
        return game.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
    }

    /**
     * EvdevListener 键盘事件处理，从 Game.keyboardEvent() 委托。
     */
    fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        val keyMap = keyboardTranslator.translate(keyCode.toInt(), -1)
        if (keyMap.toInt() != 0) {
            if (handleSpecialKeys(keyCode.toInt(), buttonDown)) {
                return
            }
            if (buttonDown) {
                game.conn?.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), 0.toByte())
            } else {
                game.conn?.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), 0.toByte())
            }
        }
    }
}
