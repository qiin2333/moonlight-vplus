package com.limelight.utils;

import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * 翻译核心：将 Android KeyCode 映射到 Windows Virtual-Key Code 和显示名称。
 */
public class KeyCodeMapper {

    // Map: Android KeyCode (Integer) -> Windows Virtual-Key Code (String)
    private static final Map<Integer, String> windowsKeyMap = new HashMap<>();

    // Map: Android KeyCode (Integer) -> Display Name (String)
    private static final Map<Integer, String> displayNameMap = new HashMap<>();

    static {
        // --- 字母 (A-Z) ---
        for (int i = 0; i < 26; i++) {
            int androidKeyCode = KeyEvent.KEYCODE_A + i;
            String letter = String.valueOf((char) ('A' + i));
            String windowsKeyCode = String.format("0x%02X", 0x41 + i);
            windowsKeyMap.put(androidKeyCode, windowsKeyCode);
            displayNameMap.put(androidKeyCode, letter);
        }

        // --- 数字 (0-9) ---
        for (int i = 0; i < 10; i++) {
            int androidKeyCode = KeyEvent.KEYCODE_0 + i;
            String number = String.valueOf((char) ('0' + i));
            String windowsKeyCode = String.format("0x%02X", 0x30 + i);
            windowsKeyMap.put(androidKeyCode, windowsKeyCode);
            displayNameMap.put(androidKeyCode, number);
        }

        // --- 功能键 (F1-F12) ---
        for (int i = 0; i < 12; i++) {
            int androidKeyCode = KeyEvent.KEYCODE_F1 + i;
            String fKey = "F" + (i + 1);
            String windowsKeyCode = String.format("0x%02X", 0x70 + i);
            windowsKeyMap.put(androidKeyCode, windowsKeyCode);
            displayNameMap.put(androidKeyCode, fKey);
        }

        // --- 修饰键 ---
        addMapping(KeyEvent.KEYCODE_SHIFT_LEFT, "L-Shift", "0xA0");   // VK_LSHIFT
        addMapping(KeyEvent.KEYCODE_SHIFT_RIGHT, "R-Shift", "0xA1");  // VK_RSHIFT
        addMapping(KeyEvent.KEYCODE_CTRL_LEFT, "L-Ctrl", "0xA2");    // VK_LCONTROL
        addMapping(KeyEvent.KEYCODE_CTRL_RIGHT, "R-Ctrl", "0xA3");   // VK_RCONTROL
        addMapping(KeyEvent.KEYCODE_ALT_LEFT, "L-Alt", "0xA4");      // VK_LMENU
        addMapping(KeyEvent.KEYCODE_ALT_RIGHT, "R-Alt", "0xA5");     // VK_RMENU
        addMapping(KeyEvent.KEYCODE_META_LEFT, "L-Win", "0x5B");      // VK_LWIN
        addMapping(KeyEvent.KEYCODE_META_RIGHT, "R-Win", "0x5C");     // VK_RWIN
        addMapping(KeyEvent.KEYCODE_CAPS_LOCK, "Cap", "0x14");       // VK_CAPITAL

        // --- 控制与导航键 ---
        addMapping(KeyEvent.KEYCODE_ESCAPE, "ESC", "0x1B");
        addMapping(KeyEvent.KEYCODE_ENTER, "Enter", "0x0D");
        addMapping(KeyEvent.KEYCODE_TAB, "Tab", "0x09");
        addMapping(KeyEvent.KEYCODE_DEL, "Back", "0x08"); // Backspace
        addMapping(KeyEvent.KEYCODE_SPACE, "Space", "0x20");
        addMapping(KeyEvent.KEYCODE_PAGE_UP, "PgUp", "0x21");
        addMapping(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn", "0x22");
        addMapping(KeyEvent.KEYCODE_MOVE_END, "End", "0x23");
        addMapping(KeyEvent.KEYCODE_MOVE_HOME, "Home", "0x24");
        addMapping(KeyEvent.KEYCODE_DPAD_LEFT, "←", "0x25");
        addMapping(KeyEvent.KEYCODE_DPAD_UP, "↑", "0x26");
        addMapping(KeyEvent.KEYCODE_DPAD_RIGHT, "→", "0x27");
        addMapping(KeyEvent.KEYCODE_DPAD_DOWN, "↓", "0x28");
        addMapping(KeyEvent.KEYCODE_INSERT, "Ins", "0x2D");
        addMapping(KeyEvent.KEYCODE_FORWARD_DEL, "Del", "0x2E"); // Delete key

        // --- 标点符号 ---
        addMapping(KeyEvent.KEYCODE_GRAVE, "`", "0xC0");
        addMapping(KeyEvent.KEYCODE_MINUS, "-", "0xBD");
        addMapping(KeyEvent.KEYCODE_EQUALS, "=", "0xBB");
        addMapping(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "0xDB");
        addMapping(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "0xDD");
        addMapping(KeyEvent.KEYCODE_BACKSLASH, "\\", "0xDC");
        addMapping(KeyEvent.KEYCODE_SEMICOLON, ";", "0xBA");
        addMapping(KeyEvent.KEYCODE_APOSTROPHE, "'", "0xDE");
        addMapping(KeyEvent.KEYCODE_COMMA, ",", "0xBC");
        addMapping(KeyEvent.KEYCODE_PERIOD, ".", "0xBE");
        addMapping(KeyEvent.KEYCODE_SLASH, "/", "0xBF");
    }

    // 辅助方法，简化添加映射的过程
    private static void addMapping(int androidKeyCode, String name, String windowsKeyCode) {
        windowsKeyMap.put(androidKeyCode, windowsKeyCode);
        displayNameMap.put(androidKeyCode, name);
    }

    /**
     * 根据 Android KeyCode 获取对应的 Windows Virtual-Key Code。
     * @param androidKeyCode Android 的 KeyEvent 常量。
     * @return 对应的 Windows 十六进制码字符串，如果找不到则返回 null。
     */
    public static String getWindowsKeyCode(int androidKeyCode) {
        return windowsKeyMap.get(androidKeyCode);
    }

    /**
     * 根据 Android KeyCode 获取用于在UI上显示的友好名称。
     * @param androidKeyCode Android 的 KeyEvent 常量。
     * @return 对应的显示名称，如果找不到则返回 "Unknown"。
     */
    public static String getDisplayName(int androidKeyCode) {
        return displayNameMap.get(androidKeyCode);
    }
}