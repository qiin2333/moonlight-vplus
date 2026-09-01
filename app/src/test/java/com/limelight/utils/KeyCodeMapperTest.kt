package com.limelight.utils

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCodeMapperTest {
    @Test
    fun mapsNavigationClusterToWindowsVirtualKeys() {
        assertMapping(KeyEvent.KEYCODE_SYSRQ, "PrtSc", "0x2C")
        assertMapping(KeyEvent.KEYCODE_SCROLL_LOCK, "ScrLk", "0x91")
        assertMapping(KeyEvent.KEYCODE_BREAK, "Pause", "0x13")
        assertMapping(KeyEvent.KEYCODE_NUM_LOCK, "Num Lock", "0x90")
        assertMapping(KeyEvent.KEYCODE_META_RIGHT, "R-Win", "0x5C")
        assertMapping(KeyEvent.KEYCODE_MENU, "Menu", "0x5D")
    }

    @Test
    fun mapsNumericKeypadToWindowsVirtualKeys() {
        for (index in 0 until 10) {
            assertMapping(
                KeyEvent.KEYCODE_NUMPAD_0 + index,
                "Num $index",
                String.format("0x%02X", 0x60 + index)
            )
        }
        assertMapping(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "Num /", "0x6F")
        assertMapping(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "Num *", "0x6A")
        assertMapping(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "Num -", "0x6D")
        assertMapping(KeyEvent.KEYCODE_NUMPAD_ADD, "Num +", "0x6B")
        assertMapping(KeyEvent.KEYCODE_NUMPAD_DOT, "Num .", "0x6E")
    }

    private fun assertMapping(androidKeyCode: Int, name: String, windowsKeyCode: String) {
        assertEquals(name, KeyCodeMapper.getDisplayName(androidKeyCode))
        assertEquals(windowsKeyCode, KeyCodeMapper.getWindowsKeyCode(androidKeyCode))
    }
}
