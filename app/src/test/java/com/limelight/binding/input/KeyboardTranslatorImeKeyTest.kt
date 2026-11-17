package com.limelight.binding.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTranslatorImeKeyTest {
    @Test
    fun hanEngKeyMapsToVkHangul() {
        assertEquals(0x15, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_KANA, 122))
        assertEquals(0x15, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_KANA, 0))
    }

    @Test
    fun hanjaKeyMapsToVkHanja() {
        assertEquals(0x19, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_EISU, 123))
        assertEquals(0x19, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_EISU, 0))
    }

    @Test
    fun languageSwitchKeyMapsToVkHangul() {
        assertEquals(0x15, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0))
    }

    @Test
    fun unmappedKeysFallBackToScanCode() {
        assertEquals(0x15, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_UNKNOWN, 122))
        assertEquals(0x19, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_UNKNOWN, 123))
        assertEquals(0, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_UNKNOWN, 0))
    }

    @Test
    fun ordinaryKeysAreLeftAlone() {
        // Right Alt doubles as Han/Eng on many keyboards, but that conversion belongs to the host layout.
        assertEquals(0, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_ALT_RIGHT, 100))
        assertEquals(0, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_A, 30))
        // A mapped key keeps its meaning even if its scan code collides.
        assertEquals(0, KeyboardTranslator.translateImeKey(KeyEvent.KEYCODE_A, 122))
    }

    @Test
    fun rightModifiersMapToKoreanImeKeys() {
        assertEquals(0x15, KeyboardTranslator.translateKoreanRightModifier(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(0x19, KeyboardTranslator.translateKoreanRightModifier(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(0, KeyboardTranslator.translateKoreanRightModifier(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(0, KeyboardTranslator.translateKoreanRightModifier(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(0x8015.toShort(), KeyboardTranslator.toGfeKeyCode(0x15))
    }

    @Test
    fun imeKeysAreRecognizedAfterTranslation() {
        assertTrue(KeyboardTranslator.isImeKey(0x8015.toShort()))
        assertTrue(KeyboardTranslator.isImeKey(0x8019.toShort()))
        assertFalse(KeyboardTranslator.isImeKey(0x80A5.toShort()))
        assertFalse(KeyboardTranslator.isImeKey(0.toShort()))
    }
}
