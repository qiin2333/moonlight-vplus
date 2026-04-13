package com.limelight.binding.input

import android.annotation.TargetApi
import android.hardware.input.InputManager
import android.os.Build
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent

/**
 * Class to translate a Android key code into the codes GFE is expecting
 * @author Diego Waxemberg
 * @author Cameron Gutman
 */
class KeyboardTranslator : InputManager.InputDeviceListener {

    /**
     * GFE's prefix for every key code
     */
    private class KeyboardMapping @TargetApi(33) constructor(private val device: InputDevice) {
        private val deviceKeyCodeToQwertyKeyCode: IntArray

        init {
            val maxKeyCode = KeyEvent.getMaxKeyCode()
            deviceKeyCodeToQwertyKeyCode = IntArray(maxKeyCode + 1)

            // Any unmatched keycodes are treated as unknown
            deviceKeyCodeToQwertyKeyCode.fill(KeyEvent.KEYCODE_UNKNOWN)

            for (i in 0..maxKeyCode) {
                val deviceKeyCode = device.getKeyCodeForKeyLocation(i)
                if (deviceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    deviceKeyCodeToQwertyKeyCode[deviceKeyCode] = i
                }
            }
        }

        @TargetApi(33)
        fun getDeviceKeyCodeForQwertyKeyCode(qwertyKeyCode: Int): Int {
            return device.getKeyCodeForKeyLocation(qwertyKeyCode)
        }

        fun getQwertyKeyCodeForDeviceKeyCode(deviceKeyCode: Int): Int {
            if (deviceKeyCode > KeyEvent.getMaxKeyCode()) {
                return KeyEvent.KEYCODE_UNKNOWN
            }
            return deviceKeyCodeToQwertyKeyCode[deviceKeyCode]
        }
    }

    private val keyboardMappings = SparseArray<KeyboardMapping>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (deviceId in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(deviceId)
                if (device != null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    keyboardMappings.set(deviceId, KeyboardMapping(device))
                }
            }
        }
    }

    fun hasNormalizedMapping(keycode: Int, deviceId: Int): Boolean {
        if (deviceId >= 0) {
            val mapping = keyboardMappings.get(deviceId)
            if (mapping != null) {
                // Try to map this device-specific keycode onto a QWERTY layout.
                // GFE assumes incoming keycodes are from a QWERTY keyboard.
                val qwertyKeyCode = mapping.getQwertyKeyCodeForDeviceKeyCode(keycode)
                if (qwertyKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Translates the given keycode and returns the GFE keycode
     * @param keycode the code to be translated
     * @param deviceId InputDevice.getId() or -1 if unknown
     * @return a GFE keycode for the given keycode
     */
    fun translate(keycode: Int, deviceId: Int): Short {
        @Suppress("NAME_SHADOWING")
        var keycode = keycode
        val translated: Int

        // If a device ID was provided, look up the keyboard mapping
        if (deviceId >= 0) {
            val mapping = keyboardMappings.get(deviceId)
            if (mapping != null) {
                // Try to map this device-specific keycode onto a QWERTY layout.
                // GFE assumes incoming keycodes are from a QWERTY keyboard.
                val qwertyKeyCode = mapping.getQwertyKeyCodeForDeviceKeyCode(keycode)
                if (qwertyKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    keycode = qwertyKeyCode
                }
            }
        }

        // This is a poor man's mapping between Android key codes
        // and Windows VK_* codes. For all defined VK_ codes, see:
        // https://msdn.microsoft.com/en-us/library/windows/desktop/dd375731(v=vs.85).aspx
        if (keycode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            translated = (keycode - KeyEvent.KEYCODE_0) + VK_0
        } else if (keycode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            translated = (keycode - KeyEvent.KEYCODE_A) + VK_A
        } else if (keycode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            translated = (keycode - KeyEvent.KEYCODE_NUMPAD_0) + VK_NUMPAD0
        } else if (keycode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            translated = (keycode - KeyEvent.KEYCODE_F1) + VK_F1
        } else {
            translated = when (keycode) {
                KeyEvent.KEYCODE_ALT_LEFT -> 0xA4
                KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5
                KeyEvent.KEYCODE_BACKSLASH -> 0xdc
                KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPS_LOCK
                KeyEvent.KEYCODE_CLEAR -> VK_CLEAR
                KeyEvent.KEYCODE_COMMA -> 0xbc
                KeyEvent.KEYCODE_CTRL_LEFT -> VK_LCONTROL
                KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3
                KeyEvent.KEYCODE_DEL -> VK_BACK_SPACE
                KeyEvent.KEYCODE_ENTER -> 0x0d
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 0xbb
                KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
                KeyEvent.KEYCODE_FORWARD_DEL -> 0x2e
                KeyEvent.KEYCODE_INSERT -> 0x2d
                KeyEvent.KEYCODE_LEFT_BRACKET -> 0xdb
                KeyEvent.KEYCODE_META_LEFT -> VK_LWIN
                KeyEvent.KEYCODE_META_RIGHT -> 0x5c
                KeyEvent.KEYCODE_MENU -> 0x5d
                KeyEvent.KEYCODE_MINUS -> 0xbd
                KeyEvent.KEYCODE_MOVE_END -> VK_END
                KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
                KeyEvent.KEYCODE_NUM_LOCK -> VK_NUM_LOCK
                KeyEvent.KEYCODE_PAGE_DOWN -> VK_PAGE_DOWN
                KeyEvent.KEYCODE_PAGE_UP -> VK_PAGE_UP
                KeyEvent.KEYCODE_PERIOD -> 0xbe
                KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xdd
                KeyEvent.KEYCODE_SCROLL_LOCK -> VK_SCROLL_LOCK
                KeyEvent.KEYCODE_SEMICOLON -> 0xba
                KeyEvent.KEYCODE_SHIFT_LEFT -> VK_LSHIFT
                KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1
                KeyEvent.KEYCODE_SLASH -> 0xbf
                KeyEvent.KEYCODE_SPACE -> VK_SPACE
                // Android defines this as SysRq/PrntScrn
                KeyEvent.KEYCODE_SYSRQ -> VK_PRINTSCREEN
                KeyEvent.KEYCODE_TAB -> VK_TAB
                KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> VK_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
                KeyEvent.KEYCODE_GRAVE -> VK_BACK_QUOTE
                KeyEvent.KEYCODE_APOSTROPHE -> 0xde
                KeyEvent.KEYCODE_BREAK -> VK_PAUSE
                KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D
                KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B
                KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E
                else -> return 0
            }
        }

        return ((KEY_PREFIX.toInt() shl 8) or translated).toShort()
    }

    override fun onInputDeviceAdded(index: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = InputDevice.getDevice(index)
            if (device != null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboardMappings.put(index, KeyboardMapping(device))
            }
        }
    }

    override fun onInputDeviceRemoved(index: Int) {
        keyboardMappings.remove(index)
    }

    override fun onInputDeviceChanged(index: Int) {
        keyboardMappings.remove(index)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = InputDevice.getDevice(index)
            if (device != null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboardMappings.set(index, KeyboardMapping(device))
            }
        }
    }

    companion object {
        private const val KEY_PREFIX: Short = 0x80.toShort()

        const val VK_0 = 48
        const val VK_9 = 57
        const val VK_A = 65
        const val VK_B = 66
        const val VK_C = 67
        const val VK_D = 68
        const val VK_G = 71
        const val VK_L = 76
        const val VK_N = 78
        const val VK_O = 79
        const val VK_V = 86
        const val VK_Z = 90
        const val VK_NUMPAD0 = 96
        const val VK_BACK_SLASH = 92
        const val VK_CAPS_LOCK = 20
        const val VK_CLEAR = 12
        const val VK_COMMA = 44
        const val VK_BACK_SPACE = 8
        const val VK_EQUALS = 61
        const val VK_ESCAPE = 27
        const val VK_F1 = 112
        const val VK_F11 = 122
        const val VK_END = 35
        const val VK_HOME = 36
        const val VK_MENU = 18
        const val VK_NUM_LOCK = 144
        const val VK_PAGE_UP = 33
        const val VK_PAGE_DOWN = 34
        const val VK_PLUS = 521
        const val VK_CLOSE_BRACKET = 93
        const val VK_SCROLL_LOCK = 145
        const val VK_SEMICOLON = 59
        const val VK_SLASH = 47
        const val VK_SPACE = 32
        //    const val VK_PRINTSCREEN = 154
        const val VK_PRINTSCREEN = 44
        const val VK_TAB = 9
        const val VK_LEFT = 37
        const val VK_RIGHT = 39
        const val VK_UP = 38
        const val VK_DOWN = 40
        const val VK_BACK_QUOTE = 192
        const val VK_QUOTE = 222
        const val VK_PAUSE = 19
        const val VK_LWIN = 91
        const val VK_LSHIFT = 160
        const val VK_LCONTROL = 162
    }
}
