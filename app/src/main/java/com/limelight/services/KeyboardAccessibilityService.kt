package com.limelight.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.limelight.preferences.PreferenceConfiguration

/**
 * 一个无障碍服务，用于在系统级别拦截硬件键盘事件。
 * 主要目的是捕获像 Win 键、Alt+Tab 等被 Android 系统默认行为占用的按键，
 * 并将它们转发给应用（如 Moonlight 的 Game Activity），以提供完整的 PC 游戏体验。
 */
class KeyboardAccessibilityService : AccessibilityService() {

    interface KeyEventCallback {
        fun onKeyEvent(event: KeyEvent)
    }

    var keyEventCallback: KeyEventCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected.")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (interceptingEnabled && PreferenceConfiguration.readPreferences(this).enableCustomKeyMap) {
            var fixedKeyCode = event.keyCode
            when (fixedKeyCode) {
                KeyEvent.KEYCODE_HOME -> fixedKeyCode = KeyEvent.KEYCODE_ESCAPE
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> fixedKeyCode = KeyEvent.KEYCODE_F5
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> fixedKeyCode = KeyEvent.KEYCODE_F10
                KeyEvent.KEYCODE_MEDIA_NEXT -> fixedKeyCode = KeyEvent.KEYCODE_F11
            }
            if (fixedKeyCode == KeyEvent.KEYCODE_SYSRQ || fixedKeyCode != event.keyCode) {
                if (keyEventCallback != null) {
                    val fixedEvent = KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        fixedKeyCode,
                        event.repeatCount,
                        event.metaState,
                        event.deviceId,
                        event.scanCode,
                        event.flags,
                        event.source
                    )
                    keyEventCallback!!.onKeyEvent(fixedEvent)
                }
                return true
            }
        }

        // 小米平板将物理 ESC 键（ScanCode=1）映射为 Android 的 BACK 键（Code=4）。
        if (event.scanCode == 1) {
            if (interceptingEnabled) {
                if (keyEventCallback != null) {
                    val fixedEvent = KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        KeyEvent.KEYCODE_ESCAPE,
                        event.repeatCount,
                        event.metaState,
                        event.deviceId,
                        event.scanCode,
                        event.flags,
                        event.source
                    )
                    keyEventCallback!!.onKeyEvent(fixedEvent)
                }
                return true
            }
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return false
        }

        if (interceptingEnabled) {
            keyEventCallback?.onKeyEvent(event)
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 对于只过滤按键事件的场景，不需要在这里做什么。
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        interceptingEnabled = false
        Log.i(TAG, "Accessibility Service destroyed.")
    }

    companion object {
        private const val TAG = "KeyboardService"

        var instance: KeyboardAccessibilityService? = null
            private set

        var interceptingEnabled = false

        fun setIntercepting(enabled: Boolean) {
            Log.d(TAG, "Setting interception to: $enabled")
            interceptingEnabled = enabled
        }
    }
}
