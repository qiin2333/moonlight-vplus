package com.limelight.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.limelight.preferences.PreferenceConfiguration

/**
 * 一个无障碍服务，用于在系统级别拦截硬件键盘事件。
 * 主要目的是捕获像 Win 键、Alt+Tab 等被 Android 系统默认行为占用的按键，
 * 并将它们转发给应用（如 Moonlight 的 Game Activity），以提供完整的 PC 游戏体验。
 */
@SuppressLint("AccessibilityPolicy")
class KeyboardAccessibilityService : AccessibilityService() {

    interface KeyEventCallback {
        fun onKeyEvent(event: KeyEvent)
    }

    var keyEventCallback: KeyEventCallback? = null
    private val interceptionDrain = KeyboardInterceptionDrain()
    private data class EventIdentity(
        val deviceId: Int, val keyCode: Int, val scanCode: Int,
        val downTime: Long, val eventTime: Long, val action: Int, val repeatCount: Int
    )
    private val forwardedEvents = LinkedHashSet<EventIdentity>()

    private fun eventIdentity(event: KeyEvent) = EventIdentity(
        event.deviceId, event.keyCode, event.scanCode, event.downTime,
        event.eventTime, event.action, event.repeatCount
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected.")
        connectionListener?.onKeyboardAccessibilityConnected(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (interceptionDrain.consumeDraining(
                event.deviceId, event.keyCode, event.scanCode, event.downTime, event.action
            )
        ) return true
        if (interceptionDrain.shouldPassThrough(
                event.deviceId, event.keyCode, event.scanCode, event.downTime, event.action
            )
        ) return false

        val callback = keyEventCallback
        if (!interceptingEnabled || callback == null) return passThrough(event)

        if (PreferenceConfiguration.readPreferences(this).enableCustomKeyMap) {
            var fixedKeyCode = event.keyCode
            when (fixedKeyCode) {
                KeyEvent.KEYCODE_HOME -> fixedKeyCode = KeyEvent.KEYCODE_ESCAPE
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> fixedKeyCode = KeyEvent.KEYCODE_F5
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> fixedKeyCode = KeyEvent.KEYCODE_F10
                KeyEvent.KEYCODE_MEDIA_NEXT -> fixedKeyCode = KeyEvent.KEYCODE_F11
            }
            if (fixedKeyCode == KeyEvent.KEYCODE_SYSRQ || fixedKeyCode != event.keyCode) {
                return consume(event, callback, fixedKeyCode)
            }
        }

        // 小米平板将物理 ESC 键（ScanCode=1）映射为 Android 的 BACK 键（Code=4）。
        if (event.scanCode == 1) {
            return consume(event, callback, KeyEvent.KEYCODE_ESCAPE)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return passThrough(event)
        }

        return consume(event, callback, event.keyCode)
    }

    private fun consume(event: KeyEvent, callback: KeyEventCallback, keyCode: Int): Boolean {
        // Record before invoking Game, which may synchronously pause interception.
        interceptionDrain.recordConsumed(
            event.deviceId, event.keyCode, event.scanCode, event.downTime, event.action
        )
        forwardedEvents.add(eventIdentity(event))
        if (forwardedEvents.size > 256) forwardedEvents.remove(forwardedEvents.first())
        if (keyCode == event.keyCode) {
            callback.onKeyEvent(event)
        } else {
            callback.onKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, keyCode, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source
                )
            )
        }
        return true
    }

    private fun passThrough(event: KeyEvent): Boolean {
        interceptionDrain.recordPassed(
            event.deviceId, event.keyCode, event.scanCode, event.downTime, event.action
        )
        return false
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

        fun wasForwarded(event: KeyEvent): Boolean =
            instance?.let { it.forwardedEvents.remove(it.eventIdentity(event)) } == true

        fun setIntercepting(enabled: Boolean) {
            Log.d(TAG, "Setting interception to: $enabled")
            if (interceptingEnabled && !enabled) {
                instance?.interceptionDrain?.pause()
            }
            interceptingEnabled = enabled
        }

        var connectionListener: ConnectionListener? = null
    }

    interface ConnectionListener {
        fun onKeyboardAccessibilityConnected(service: KeyboardAccessibilityService)
    }
}
