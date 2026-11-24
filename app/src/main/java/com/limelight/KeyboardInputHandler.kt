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
import com.limelight.binding.input.InputDeviceContext
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
    private var ordinaryModifierFlags = 0
    private var accessibilityModifierFlags = 0
    private val ordinaryModifierKeys = HashSet<PhysicalKey>()
    private val accessibilityModifierKeys = HashSet<PhysicalKey>()
    private var waitingForAllModifiersUp = false
    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var specialKeyUsesAccessibility = false
    private var shortcutGeneration = 0L
    private var accessibilityShortcutGeneration = 0L
    private var lastEscPressTime = 0L
    private var hasShownEscHint = false
    private enum class KeyOrigin { ORDINARY, ACCESSIBILITY }
    private data class PhysicalKey(val device: Int, val code: Int, val scan: Int) {
        constructor(event: KeyEvent) : this(event.deviceId, event.keyCode, event.scanCode)
    }
    private val ordinaryPressedKeys = HashSet<PhysicalKey>()
    private val accessibilityPressedKeys = HashSet<PhysicalKey>()
    // A host key stays down until every input origin has released it.
    private val forwardedKeyOwners = HashMap<Int, MutableSet<KeyOrigin>>()

    private val pressedKeys = HashSet<Int>()
    private var rightMouseButtonDown = false
    private data class MiddleMouseKey(val deviceId: Int, val scanCode: Int)
    private var middleMouseButtonDown: MiddleMouseKey? = null
    private var releasedMiddleMouseButton: MiddleMouseKey? = null
    private data class ForwardedKey(val translated: Short, val flags: Byte)
    private val forwardedKeys = LinkedHashMap<Int, ForwardedKey>()
    private var escState = 0 // 0 = 空闲，1 = ESC已按下，2 = 已进入组合键
    private var escStateFromAccessibility = false
    private data class EscComboKey(val trackingKey: Int, val origin: KeyOrigin)
    private val escComboKeys = HashMap<PhysicalKey, EscComboKey>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var escConfirmRunnable: Runnable
    private var escTapRelease: Runnable? = null
    private var escTapReleaseFromAccessibility = false

    private data class AccessibilityControllerKey(val device: Int, val code: Int, val scan: Int) {
        constructor(event: KeyEvent) : this(event.deviceId, event.keyCode, event.scanCode)
    }
    private data class ForwardedControllerKey(val event: KeyEvent, val context: InputDeviceContext)
    private val forwardedControllerKeys = LinkedHashMap<AccessibilityControllerKey, ForwardedControllerKey>()
    private var accessibilityReleaseGeneration = 0L

    private val ordinaryToggleGrab = Runnable { game.setInputGrabState(!game.grabbedInput) }
    private val accessibilityToggleGrab = Runnable { ordinaryToggleGrab.run() }
    // A capture loss cancels only shortcuts that involved accessibility input.
    @get:Synchronized
    private val toggleGrab: Runnable
        get() = if (specialKeyUsesAccessibility) accessibilityToggleGrab else ordinaryToggleGrab

    private fun keyOrigin() = if (game.isEventFromAccessibilityService) KeyOrigin.ACCESSIBILITY else KeyOrigin.ORDINARY

    private fun sendOwnedKeyUp(trackingKey: Int, translated: Short, modifier: Byte, flags: Byte,
                               origin: KeyOrigin = keyOrigin()) {
        val owners = forwardedKeyOwners[trackingKey] ?: return
        if (!owners.remove(origin) || owners.isNotEmpty()) return
        forwardedKeyOwners.remove(trackingKey)
        sendTrackedKeyUp(trackingKey, translated, modifier, flags)
    }

    private fun releaseEscComboKey(event: KeyEvent, combo: EscComboKey): Boolean {
        if (game.grabbedInput) {
            val translated = keyboardTranslator.translate(combo.trackingKey, event.deviceId)
            sendOwnedKeyUp(combo.trackingKey, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED, combo.origin)
            val original = keyboardTranslator.translate(event.keyCode, event.deviceId)
            sendOwnedKeyUp(event.keyCode, original, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED,
                combo.origin)
        }
        return true
    }

    companion object {
        private const val ESC_DOUBLE_PRESS_INTERVAL = 500L // 500毫秒内按第二次ESC才有效
        private const val DIRECT_KEYBOARD_DEVICE_ID = Int.MIN_VALUE
    }

    private fun modifierMaskFor(androidKeyCode: Int): Int = when (androidKeyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyboardPacket.MODIFIER_CTRL.toInt()
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyboardPacket.MODIFIER_SHIFT.toInt()
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyboardPacket.MODIFIER_ALT.toInt()
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyboardPacket.MODIFIER_META.toInt()
        else -> 0
    }

    // Returns true if the key stroke was consumed
    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean, isRepeat: Boolean = false,
                                  physicalKey: PhysicalKey? = null, origin: KeyOrigin = keyOrigin()): Boolean {
        val modifierMask = modifierMaskFor(androidKeyCode)
        val nonModifierKeyCode = if (modifierMask == 0) androidKeyCode else KeyEvent.KEYCODE_UNKNOWN
        var completedShortcut = KeyEvent.KEYCODE_UNKNOWN
        var completedToggleGrab: Runnable? = null
        var shortcutWasAccessible = false
        var generation = 0L
        var accessibilityGeneration = 0L
        val consumed = synchronized(this) {
            if (modifierMask != 0) {
                val heldKeys = if (origin == KeyOrigin.ACCESSIBILITY) accessibilityModifierKeys else ordinaryModifierKeys
                val owner = physicalKey ?: PhysicalKey(DIRECT_KEYBOARD_DEVICE_ID, androidKeyCode, 0)
                if (down) heldKeys.add(owner) else heldKeys.remove(owner)
                val flags = heldKeys.fold(0) { current, key -> current or modifierMaskFor(key.code) }
                if (origin == KeyOrigin.ACCESSIBILITY) accessibilityModifierFlags = flags else ordinaryModifierFlags = flags
                modifierFlags = ordinaryModifierFlags or accessibilityModifierFlags
            }

            // Handle the special combos on the key up
            if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                if (origin == KeyOrigin.ACCESSIBILITY) specialKeyUsesAccessibility = true
                if (specialKeyCode == androidKeyCode) {
                    return@synchronized true
                } else if (modifierFlags != 0) {
                    return@synchronized down
                } else {
                    completedShortcut = specialKeyCode
                    completedToggleGrab = toggleGrab
                    shortcutWasAccessible = specialKeyUsesAccessibility
                    generation = shortcutGeneration
                    accessibilityGeneration = accessibilityShortcutGeneration
                    specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    waitingForAllModifiersUp = false
                    specialKeyUsesAccessibility = false
                }
            } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
                (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
            ) {
                when (androidKeyCode) {
                    KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_M -> {
                        // A repeat may belong to a key that was already forwarded to the
                        // host; activating the shortcut here would swallow its release.
                        if (isRepeat) return@synchronized false
                        // Let the host keep Ctrl+Alt+Shift+M when the local shortcut is unavailable.
                        if (androidKeyCode == KeyEvent.KEYCODE_M && !game.prefConfig.supportsDesktopMouseMode) return@synchronized false
                        specialKeyCode = androidKeyCode
                        waitingForAllModifiersUp = true
                        specialKeyUsesAccessibility = origin == KeyOrigin.ACCESSIBILITY || accessibilityModifierFlags != 0
                        return@synchronized true
                    }
                    else -> return@synchronized false
                }
            }
            false
        }
        if (completedShortcut != KeyEvent.KEYCODE_UNKNOWN) {
            val action = Runnable {
                val valid = synchronized(this) {
                    generation == shortcutGeneration &&
                        (!shortcutWasAccessible || accessibilityGeneration == accessibilityShortcutGeneration)
                }
                if (!valid) return@Runnable
                when (completedShortcut) {
                    KeyEvent.KEYCODE_Z -> completedToggleGrab?.let {
                        handler.removeCallbacks(it)
                        handler.postDelayed(it, 250)
                    }
                    KeyEvent.KEYCODE_Q -> game.finish()
                    KeyEvent.KEYCODE_C -> {
                        if (!game.grabbedInput) {
                            game.inputCaptureProvider.enableCapture()
                            game.grabbedInput = true
                            game.setMetaKeyCaptureState(game.connected)
                        }
                        game.cursorVisible = !game.cursorVisible
                        if (game.cursorVisible) game.inputCaptureProvider.showCursor()
                        else game.inputCaptureProvider.hideCursor()
                    }
                    KeyEvent.KEYCODE_M -> game.toggleExternalMouseDesktopMode()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
        }
        return consumed
    }

    private fun getModifierState(event: KeyEvent): Byte {
        var modifier = getModifierState().toInt()
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        return modifier.toByte()
    }

    @Synchronized
    private fun getModifierState(): Byte = modifierFlags.toByte()

    @Synchronized
    fun clearModifierState() {
        shortcutGeneration++
        ordinaryModifierKeys.clear()
        accessibilityModifierKeys.clear()
        ordinaryModifierFlags = 0
        accessibilityModifierFlags = 0
        modifierFlags = 0
        specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
        waitingForAllModifiersUp = false
        specialKeyUsesAccessibility = false
    }

    private fun cancelAccessibilityToggle() {
        handler.removeCallbacks(accessibilityToggleGrab)
        game.window.decorView.handler?.removeCallbacks(accessibilityToggleGrab)
    }

    fun releaseForwardedKeys() {
        handler.removeCallbacks(toggleGrab)
        if (::escConfirmRunnable.isInitialized) {
            handler.removeCallbacks(escConfirmRunnable)
        }
        pressedKeys.clear()
        lastEscPressTime = 0
        hasShownEscHint = false
        if (rightMouseButtonDown) {
            game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            rightMouseButtonDown = false
        }
        middleMouseButtonDown?.let { key ->
            game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
            releasedMiddleMouseButton = key
            middleMouseButtonDown = null
            game.touchInputHandler.detectMouseMiddleDown = false
        }
        escTapRelease?.let { handler.removeCallbacks(it) }
        escTapRelease = null
        escTapReleaseFromAccessibility = false
        escState = 0
        escStateFromAccessibility = false
        escComboKeys.clear()
        synchronized(this) {
            shortcutGeneration++
            specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
            waitingForAllModifiersUp = false
            specialKeyUsesAccessibility = false
        }
        cancelAccessibilityToggle()
        handler.removeCallbacks(ordinaryToggleGrab)
        game.window.decorView.handler?.removeCallbacks(ordinaryToggleGrab)
        val keys = forwardedKeys.values.toList()
        forwardedKeys.clear()
        forwardedKeyOwners.clear()
        for (key in keys) {
            game.conn?.sendKeyboardInput(
                key.translated, KeyboardPacket.KEY_UP, getModifierState(), key.flags
            )
        }
        pressedKeys.clear()
        ordinaryPressedKeys.clear()
        accessibilityPressedKeys.clear()
        clearModifierState()
    }

    fun releaseAccessibilityKeys() {
        accessibilityReleaseGeneration++
        val controllerKeys = forwardedControllerKeys.values.toList()
        forwardedControllerKeys.clear()
        val releasedKeys = mutableListOf<ForwardedKey>()
        val iterator = forwardedKeyOwners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.remove(KeyOrigin.ACCESSIBILITY) && entry.value.isEmpty()) {
                iterator.remove()
                forwardedKeys.remove(entry.key)?.let { releasedKeys.add(it) }
            }
        }
        accessibilityPressedKeys.clear()
        pressedKeys.retainAll(ordinaryPressedKeys.map { it.code }.toSet())
        synchronized(this) {
            accessibilityShortcutGeneration++
            accessibilityModifierKeys.clear()
            accessibilityModifierFlags = 0
            modifierFlags = ordinaryModifierFlags
            if (specialKeyUsesAccessibility) {
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
                specialKeyUsesAccessibility = false
            }
        }
        releasedKeys.forEach { key ->
            game.conn?.sendKeyboardInput(key.translated, KeyboardPacket.KEY_UP, getModifierState(), key.flags)
        }
        cancelAccessibilityToggle()
        if (escStateFromAccessibility) {
            if (::escConfirmRunnable.isInitialized) handler.removeCallbacks(escConfirmRunnable)
            escState = 0
            escStateFromAccessibility = false
        }
        if (escTapReleaseFromAccessibility) {
            escTapRelease?.let { handler.removeCallbacks(it) }
            escTapRelease = null
            escTapReleaseFromAccessibility = false
        }
        escComboKeys.entries.removeAll { it.value.origin == KeyOrigin.ACCESSIBILITY }
        controllerKeys.forEach { game.controllerHandler.cancelAccessibilityButton(it.context, it.event) }
    }

    private fun sendKeyDown(trackingKey: Int, translated: Short, modifier: Byte, flags: Byte,
                            origin: KeyOrigin = keyOrigin()) {
        forwardedKeyOwners.getOrPut(trackingKey) { mutableSetOf() }.add(origin)
        if (trackingKey !in forwardedKeys) forwardedKeys[trackingKey] = ForwardedKey(translated, flags)
        game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, modifier, flags)
    }

    private fun sendTrackedKeyUp(trackingKey: Int, translated: Short, modifier: Byte, flags: Byte) {
        if (forwardedKeys.remove(trackingKey) == null) return
        game.conn?.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, modifier, flags)
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        // 系统导航键、音量键和电源键未被无障碍服务消费时仍按原逻辑处理。
        if (!game.isEventFromAccessibilityService && KeyboardAccessibilityService.wasConsumed(event)) return true

        // 自定义组合键，只能其它+esc，esc+其它时，esc抬起时其它才会down
        val keyCode = event.keyCode
        val origin = keyOrigin()
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.repeatCount == 0 &&
            game.prefConfig.enableCustomKeyMap && game.grabbedInput &&
            escState == 1 && escStateFromAccessibility != (origin == KeyOrigin.ACCESSIBILITY) &&
            ::escConfirmRunnable.isInitialized
        ) {
            handler.removeCallbacks(escConfirmRunnable)
            // Preserve the first origin before replacing its pending confirmation.
            escConfirmRunnable.run()
        }
        val physicalKey = PhysicalKey(event)
        if (origin == KeyOrigin.ACCESSIBILITY) accessibilityPressedKeys.add(physicalKey)
        else ordinaryPressedKeys.add(physicalKey)
        pressedKeys.add(keyCode)
        if (game.prefConfig.enableCustomKeyMap && game.grabbedInput) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.repeatCount > 0) return true
                if (::escConfirmRunnable.isInitialized) handler.removeCallbacks(escConfirmRunnable)
                escState = 1
                escStateFromAccessibility = origin == KeyOrigin.ACCESSIBILITY
                escConfirmRunnable = Runnable {
                    if (escState == 1) {
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                        sendKeyDown(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED, origin)
                        escState = 0
                        escStateFromAccessibility = false
                    }
                }
                handler.postDelayed(escConfirmRunnable, 200)
                return true
            }

            if (escState == 1) {
                handler.removeCallbacks(escConfirmRunnable)

                when (keyCode) {
                    KeyEvent.KEYCODE_Q -> {
                        escState = 2
                        return true
                    }
                    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                        escState = 2
                        val fKeyCode = KeyEvent.KEYCODE_F1 + (keyCode - KeyEvent.KEYCODE_1)
                        escComboKeys[physicalKey] = EscComboKey(fKeyCode, origin)
                        val translated = keyboardTranslator.translate(fKeyCode, event.deviceId)
                        sendKeyDown(fKeyCode, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        return true
                    }
                    KeyEvent.KEYCODE_0 -> {
                        escState = 2
                        escComboKeys[physicalKey] = EscComboKey(KeyEvent.KEYCODE_F10, origin)
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F10, event.deviceId)
                        sendKeyDown(KeyEvent.KEYCODE_F10, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        return true
                    }
                    KeyEvent.KEYCODE_MINUS -> {
                        escState = 2
                        escComboKeys[physicalKey] = EscComboKey(KeyEvent.KEYCODE_F11, origin)
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F11, event.deviceId)
                        sendKeyDown(KeyEvent.KEYCODE_F11, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        return true
                    }
                    KeyEvent.KEYCODE_EQUALS -> {
                        escState = 2
                        escComboKeys[physicalKey] = EscComboKey(KeyEvent.KEYCODE_F12, origin)
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_F12, event.deviceId)
                        sendKeyDown(KeyEvent.KEYCODE_F12, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        return true
                    }
                    else -> {
                        // 非自定义组合键，不做处理
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                        val escOrigin = if (escStateFromAccessibility) KeyOrigin.ACCESSIBILITY else KeyOrigin.ORDINARY
                        sendKeyDown(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED, escOrigin)
                        escState = 0
                        escStateFromAccessibility = false
                    }
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
        if (eventSource == InputDevice.SOURCE_KEYBOARD && event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.repeatCount == 0 && releasedMiddleMouseButton == MiddleMouseKey(event.deviceId, event.scanCode)
        ) {
            releasedMiddleMouseButton = null
        }
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!game.prefConfig.mouseNavButtons) {
                rightMouseButtonDown = true
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
                middleMouseButtonDown = MiddleMouseKey(event.deviceId, event.scanCode)
                game.touchInputHandler.detectMouseMiddle = false
                game.conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                return true
            }
        }

        var handled = false

        if (ControllerHandler.isGameControllerDevice(event.device)) {
            val generation = accessibilityReleaseGeneration
            handled = game.controllerHandler.handleButtonDown(event) == true
            if (handled && game.isEventFromAccessibilityService && event.repeatCount == 0) {
                game.controllerHandler.existingContextForEvent(event)?.let { context ->
                    if (generation != accessibilityReleaseGeneration) {
                        game.controllerHandler.cancelAccessibilityButton(context, event)
                    } else {
                        forwardedControllerKeys[AccessibilityControllerKey(event)] = ForwardedControllerKey(KeyEvent(event), context)
                    }
                }
            }
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true, event.repeatCount > 0, physicalKey, origin)) {
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

            sendKeyDown(
                event.keyCode, translated, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    fun handleKeyUp(event: KeyEvent): Boolean {
        if (game.isEventFromAccessibilityService) forwardedControllerKeys.remove(AccessibilityControllerKey(event))
        // 未被无障碍服务消费的系统导航键仍按原逻辑处理。
        if (!game.isEventFromAccessibilityService && KeyboardAccessibilityService.wasConsumed(event)) return true

        val origin = keyOrigin()
        val physicalKey = PhysicalKey(event)
        val sourcePressedKeys = if (origin == KeyOrigin.ACCESSIBILITY) accessibilityPressedKeys else ordinaryPressedKeys
        if (game.prefConfig.enableCustomKeyMap && game.grabbedInput &&
            event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
            (event.keyCode !in pressedKeys || physicalKey !in sourcePressedKeys)
        ) return true

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

        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && game.prefConfig.enableCustomKeyMap && game.grabbedInput &&
            escState != 0 && escStateFromAccessibility != (origin == KeyOrigin.ACCESSIBILITY)
        ) {
            sourcePressedKeys.remove(physicalKey)
            if (ordinaryPressedKeys.none { it.code == event.keyCode } && accessibilityPressedKeys.none { it.code == event.keyCode }) {
                pressedKeys.remove(event.keyCode)
            }
            val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
            sendOwnedKeyUp(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED, origin)
            return true
        }

        val combo = escComboKeys.remove(physicalKey)
        val keyCode = event.keyCode
        pressedKeys.remove(keyCode)
        sourcePressedKeys.remove(physicalKey)
        if (ordinaryPressedKeys.any { it.code == keyCode } || accessibilityPressedKeys.any { it.code == keyCode }) {
            pressedKeys.add(keyCode)
        }

        if (game.prefConfig.enableCustomKeyMap && game.grabbedInput) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (::escConfirmRunnable.isInitialized) handler.removeCallbacks(escConfirmRunnable)
                when (escState) {
                    1 -> {
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                        sendKeyDown(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        escTapReleaseFromAccessibility = origin == KeyOrigin.ACCESSIBILITY
                        val release = Runnable {
                            escTapRelease = null
                            escTapReleaseFromAccessibility = false
                            sendOwnedKeyUp(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED, origin)
                        }
                        escTapRelease = release
                        handler.postDelayed(release, 50)
                        escState = 0
                        escStateFromAccessibility = false
                    }
                    2 -> {
                        escState = 0
                        escStateFromAccessibility = false
                    }
                    else -> {
                        val translated = keyboardTranslator.translate(KeyEvent.KEYCODE_ESCAPE, event.deviceId)
                        sendOwnedKeyUp(KeyEvent.KEYCODE_ESCAPE, translated, 0.toByte(), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
                        escState = 0
                        escStateFromAccessibility = false
                    }
                }
                return true
            }
            if (escState == 2) {
                if (keyCode == KeyEvent.KEYCODE_Q) {
                    game.onBackPressed()
                    return true
                }
            }
        }

        if (combo != null) return releaseEscComboKey(event, combo)

        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (rightMouseButtonDown) {
                rightMouseButtonDown = false
                game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        // 鼠标中键
        if (eventSource == InputDevice.SOURCE_KEYBOARD && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val key = MiddleMouseKey(event.deviceId, event.scanCode)
            if (game.touchInputHandler.detectMouseMiddleDown && middleMouseButtonDown == key) {
                game.touchInputHandler.detectMouseMiddleDown = false
                middleMouseButtonDown = null
                game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                return true
            }
            if (releasedMiddleMouseButton == key) {
                releasedMiddleMouseButton = null
                return true
            }
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = game.controllerHandler.handleButtonUp(event) == true
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false, physicalKey = physicalKey, origin = origin)) {
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

            sendOwnedKeyUp(
                event.keyCode, translated, getModifierState(event),
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
    fun keyboardEvent(buttonDown: Boolean, keyCode: Short, isRepeat: Boolean) {
        val keyMap = keyboardTranslator.translate(keyCode.toInt(), -1)
        if (keyMap.toInt() != 0) {
            if (handleSpecialKeys(keyCode.toInt(), buttonDown, isRepeat, origin = KeyOrigin.ORDINARY)) {
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
