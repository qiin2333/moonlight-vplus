package com.limelight.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 一个无障碍服务，用于在系统级别拦截硬件键盘事件。
 * 主要目的是捕获像 Win 键、Alt+Tab 等被 Android 系统默认行为占用的按键，
 * 并将它们转发给应用（如 Moonlight 的 Game Activity），以提供完整的 PC 游戏体验。
 *
 * <p><b>重要：</b>此服务需要用户在系统设置中手动授权。</p>
 */
public class KeyboardAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeyboardService";

    // 使用静态实例，方便 Activity 在其生命周期内获取服务引用。
    private static KeyboardAccessibilityService instance;

    // 一个标志位，用于控制服务是否应该拦截按键事件。
    // 必须由外部（如 Game Activity）在 onResume/onPause 中进行控制。
    private static boolean interceptingEnabled = false;

    /**
     * 回调接口，用于将捕获到的按键事件发送给注册的监听者（通常是 Game Activity）。
     */
    public interface KeyEventCallback {
        /**
         * 当无障碍服务捕获到一个按键事件时调用。
         * @param event 被捕获的按键事件对象。
         */
        void onKeyEvent(KeyEvent event);
    }

    private KeyEventCallback keyEventCallback;

    /**
     * 获取当前正在运行的服务实例。
     * @return 如果服务正在运行，则返回服务实例；否则返回 null。
     */
    public static KeyboardAccessibilityService getInstance() {
        return instance;
    }

    /**
     * 设置一个监听器来接收按键事件回调。
     * @param callback 实现 KeyEventCallback 接口的对象。
     */
    public void setKeyEventCallback(KeyEventCallback callback) {
        this.keyEventCallback = callback;
    }

    /**
     * 控制是否开始或停止拦截键盘事件。
     * 这个方法应该由 Activity 在 onResume() 中设置为 true，在 onPause() 中设置为 false。
     * @param enabled true 表示开始拦截，false 表示停止拦截。
     */
    public static void setIntercepting(boolean enabled) {
        Log.d(TAG, "Setting interception to: " + enabled);
        interceptingEnabled = enabled;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility Service connected.");
    }

    /**
     * 核心方法！所有（可被过滤的）硬件键盘事件在被系统或其他应用处理之前，都会先到达这里。
     * @param event 按键事件对象。
     * @return 如果返回 true，表示事件已被“消费”，系统将不再处理它（Win键返回桌面的行为被阻止）。
     *         如果返回 false 或调用 super.onKeyEvent(event)，事件将继续传递给系统。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // 在进行任何拦截之前，我们首先检查这个按键是否是手机导航所必需的。
        // 如果是，我们必须立即返回 false，将事件交还给系统正常处理。
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                // 这些是系统级的关键按键，我们永远不应该拦截它们。
                // 返回 false 意味着：“这个事件我不处理，请系统继续执行默认操作”。
                return false;
        }
        // 仅在拦截标志位为 true 时处理事件
        if (interceptingEnabled) {
            // 如果有注册的回调监听器，则将事件传递出去
            if (keyEventCallback != null) {
                keyEventCallback.onKeyEvent(event);
            }

            // 返回 true，告诉系统我们已经处理了这个事件。
            // 这是阻止 Win 键、Alt+Tab 等系统级快捷键触发默认行为的关键。
            return true;
        }

        // 如果不处于拦截状态，则让系统正常处理按键
        return super.onKeyEvent(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 对于只过滤按键事件的场景，我们通常不需要在这里做什么。
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.");
        // 当服务被系统打断时调用（例如，弹出一个需要更高权限的窗口时）。
    }

    public KeyEventCallback getKeyEventCallback() {
        return this.keyEventCallback;
    }



    @Override
    public void onDestroy() {
        super.onDestroy();
        // 清理静态引用，防止内存泄漏。
        instance = null;
        interceptingEnabled = false; // 确保在服务销毁时停止拦截
        Log.i(TAG, "Accessibility Service destroyed.");
    }
}