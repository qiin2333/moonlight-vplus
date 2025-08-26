package com.limelight.utils;

import java.util.HashMap;
import java.util.Map;


/**
 * 工具类，将虚拟键盘上的按键名称映射到 Microsoft Windows 官方虚拟键码 (Virtual-Key Codes)。
 */
public class KeyCodeMapper {

    private static final Map<String, String> keyMap = new HashMap<>();

    static {
        // --- 字母 (A-Z) ---
        keyMap.put("A", "0x41");
        keyMap.put("B", "0x42");
        keyMap.put("C", "0x43");
        keyMap.put("D", "0x44");
        keyMap.put("E", "0x45");
        keyMap.put("F", "0x46");
        keyMap.put("G", "0x47");
        keyMap.put("H", "0x48");
        keyMap.put("I", "0x49");
        keyMap.put("J", "0x4A");
        keyMap.put("K", "0x4B");
        keyMap.put("L", "0x4C");
        keyMap.put("M", "0x4D");
        keyMap.put("N", "0x4E");
        keyMap.put("O", "0x4F");
        keyMap.put("P", "0x50");
        keyMap.put("Q", "0x51");
        keyMap.put("R", "0x52");
        keyMap.put("S", "0x53");
        keyMap.put("T", "0x54");
        keyMap.put("U", "0x55");
        keyMap.put("V", "0x56");
        keyMap.put("W", "0x57");
        keyMap.put("X", "0x58");
        keyMap.put("Y", "0x59");
        keyMap.put("Z", "0x5A");

        // --- 数字 (0-9) ---
        keyMap.put("0", "0x30");
        keyMap.put("1", "0x31");
        keyMap.put("2", "0x32");
        keyMap.put("3", "0x33");
        keyMap.put("4", "0x34");
        keyMap.put("5", "0x35");
        keyMap.put("6", "0x36");
        keyMap.put("7", "0x37");
        keyMap.put("8", "0x38");
        keyMap.put("9", "0x39");

        // --- 功能键 (F1-F12) ---
        keyMap.put("F1", "0x70");
        keyMap.put("F2", "0x71");
        keyMap.put("F3", "0x72");
        keyMap.put("F4", "0x73");
        keyMap.put("F5", "0x74");
        keyMap.put("F6", "0x75");
        keyMap.put("F7", "0x76");
        keyMap.put("F8", "0x77");
        keyMap.put("F9", "0x78");
        keyMap.put("F10", "0x79");
        keyMap.put("F11", "0x7A");
        keyMap.put("F12", "0x7B");

        // --- 修饰键 (统一使用左边的键码) ---
        keyMap.put("Shift", "0xA0"); // VK_LSHIFT
        keyMap.put("Ctrl", "0xA2");  // VK_LCONTROL
        keyMap.put("Alt", "0xA4");   // VK_LMENU (Left Alt)
        keyMap.put("Win", "0x5B");   // VK_LWIN
        keyMap.put("Cap", "0x14");   // VK_CAPITAL (Caps Lock)

        // --- 控制与导航键 ---
        keyMap.put("ESC", "0x1B");     // VK_ESCAPE
        keyMap.put("Enter", "0x0D");   // VK_RETURN
        keyMap.put("Tab", "0x09");     // VK_TAB
        keyMap.put("Back", "0x08");    // VK_BACK (Backspace)
        keyMap.put("Space", "0x20");   // VK_SPACE
        keyMap.put("PgUp", "0x21");    // VK_PRIOR (Page Up)
        keyMap.put("PgDn", "0x22");    // VK_NEXT (Page Down)
        keyMap.put("End", "0x23");
        keyMap.put("Home", "0x24");
        keyMap.put("←", "0x25");       // VK_LEFT (Left Arrow)
        keyMap.put("↑", "0x26");       // VK_UP (Up Arrow)
        keyMap.put("→", "0x27");       // VK_RIGHT (Right Arrow)
        keyMap.put("↓", "0x28");       // VK_DOWN (Down Arrow)
        keyMap.put("Ins", "0x2D");     // VK_INSERT
        keyMap.put("Del", "0x2E");     // VK_DELETE

        // --- 标点符号 ---
        keyMap.put("`", "0xC0");       // VK_OEM_3
        keyMap.put("-", "0xBD");       // VK_OEM_MINUS
        keyMap.put("=", "0xBB");       // VK_OEM_PLUS
        keyMap.put("[", "0xDB");       // VK_OEM_4
        keyMap.put("]", "0xDD");       // VK_OEM_6
        keyMap.put("\\", "0xDC");      // VK_OEM_5
        keyMap.put(";", "0xBA");       // VK_OEM_1
        keyMap.put("'", "0xDE");       // VK_OEM_7
        keyMap.put(",", "0xBC");       // VK_OEM_COMMA
        keyMap.put(".", "0xBE");       // VK_OEM_PERIOD
        keyMap.put("/", "0xBF");       // VK_OEM_2
    }

    /**
     * 将按键名称映射到其Windows虚拟键码。
     *
     * @param keyName 按键的显示名称。
     * @return 对应的十六进制码字符串，如果找不到则返回 null。
     */
    public static String getKeyCode(String keyName) {
        return keyMap.get(keyName);
    }
}