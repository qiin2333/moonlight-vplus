package com.limelight.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.limelight.preferences.PreferenceConfiguration
import java.util.ArrayDeque

/**
 * 一个无障碍服务，用于在系统级别拦截硬件键盘事件。
 * 主要目的是捕获像 Win 键、Alt+Tab 等被 Android 系统默认行为占用的按键，
 * 并将它们转发给应用（如 Moonlight 的 Game Activity），以提供完整的 PC 游戏体验。
 */
@SuppressLint("AccessibilityPolicy")
class KeyboardAccessibilityService : AccessibilityService() {
    interface KeyEventCallback {
        fun onKeyEvent(event: KeyEvent)
        fun onKeyboardCaptureLost()
    }

    private val ownership = KeyboardInterceptionState()
    private data class EventId(
        val device: Int, val code: Int, val scan: Int, val action: Int,
        val eventTime: Long, val downTime: Long, val repeat: Int, val source: Int, val flags: Int
    ) {
        constructor(event: KeyEvent) : this(event.deviceId, event.keyCode, event.scanCode,
            event.action, event.eventTime, event.downTime, event.repeatCount, event.source, event.flags)
    }
    private val consumedEvents = ArrayDeque<EventId>()

    var keyEventCallback: KeyEventCallback? = null
        set(value) {
            if (field !== value) pauseInterception()
            field = value
        }

    private fun pauseInterception() {
        ownership.pause()
        keyEventCallback?.onKeyboardCaptureLost()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected.")
        connectionListener?.onKeyboardAccessibilityConnected(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val callback = keyEventCallback
        val mappedKey = if (interceptingEnabled && callback != null) mappedKeyCode(event) else null
        val decision = ownership.dispatch(
            KeyboardInterceptionState.Key(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount, mappedKey
        )
        if (decision.route == KeyboardInterceptionState.Route.PASS) return false

        // downTime is used only to identify an exact duplicate event, never key ownership.
        consumedEvents.addLast(EventId(event))
        while (consumedEvents.size > 64) consumedEvents.removeFirst()
        if (decision.route == KeyboardInterceptionState.Route.FORWARD && callback != null) {
            val forwarded = if (decision.keyCode == event.keyCode) event else KeyEvent(
                event.downTime, event.eventTime, event.action, decision.keyCode,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode,
                event.flags, event.source
            )
            callback.onKeyEvent(forwarded)
        }
        return true
    }

    private fun mappedKeyCode(event: KeyEvent): Int? {
        if (PreferenceConfiguration.readPreferences(this).enableCustomKeyMap) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME -> return KeyEvent.KEYCODE_ESCAPE
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return KeyEvent.KEYCODE_F5
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return KeyEvent.KEYCODE_F10
                KeyEvent.KEYCODE_MEDIA_NEXT -> return KeyEvent.KEYCODE_F11
            }
        }
        // 小米平板将物理 ESC 键（ScanCode=1）映射为 Android 的 BACK 键（Code=4）。
        if (event.scanCode == 1) return KeyEvent.KEYCODE_ESCAPE
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER -> null
            else -> event.keyCode
        }
    }

    // 对于只过滤按键事件的场景，不需要在这里做什么。
    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() {
        setIntercepting(false)
    }

    override fun onDestroy() {
        setIntercepting(false)
        keyEventCallback = null
        ownership.clear()
        consumedEvents.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeyboardService"
        var instance: KeyboardAccessibilityService? = null
            private set
        var interceptingEnabled = false
            private set

        fun setIntercepting(enabled: Boolean) {
            if (interceptingEnabled && !enabled) instance?.pauseInterception()
            interceptingEnabled = enabled
        }

        fun wasConsumed(event: KeyEvent): Boolean =
            instance?.consumedEvents?.contains(EventId(event)) == true

        var connectionListener: ConnectionListener? = null
    }

    interface ConnectionListener {
        fun onKeyboardAccessibilityConnected(service: KeyboardAccessibilityService)
    }
}
