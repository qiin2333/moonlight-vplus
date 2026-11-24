package com.limelight.binding.input.advance_setting

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPageLayoutsTest {
    @Test
    fun standardNumPageContainsFullNumpad() {
        val spec = KeyboardPageLayouts.spec(KeyboardPage.NUM, KeyboardTier.STANDARD)
        assertEquals(15, spec.units)
        assertEquals(5, spec.rows)
        val codes = spec.blocks
            .filterIsInstance<KeyboardBlockSpec.Grid>()
            .flatMap { it.cells }
            .mapNotNull { it.spec?.code }
            .toSet()
        for (code in KeyEvent.KEYCODE_NUM_LOCK..KeyEvent.KEYCODE_NUMPAD_DOT) {
            assertTrue("numpad code " + code, codes.contains(code))
        }
        assertTrue(codes.contains(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun compactNumPageIsTenUnitsAndFourRows() {
        val spec = KeyboardPageLayouts.spec(KeyboardPage.NUM, KeyboardTier.POCKET)
        assertEquals(10, spec.units)
        assertEquals(4, spec.rows)
        val keys = spec.blocks
            .filterIsInstance<KeyboardBlockSpec.Rows>()
            .flatMap { block -> block.rows.flatMap { it.keys } }
        assertTrue(keys.any { it.page == KeyboardPage.MAIN })
        assertTrue(keys.any { it.kind == KeyKind.ACCENT })
    }

    @Test
    fun fullNavPageHasFunctionRowAndSystemKeys() {
        val spec = KeyboardPageLayouts.spec(KeyboardPage.NAV, KeyboardTier.STANDARD)
        assertEquals(15, spec.units)
        assertEquals(5, spec.rows)
        val codes = spec.blocks
            .filterIsInstance<KeyboardBlockSpec.Rows>()
            .flatMap { block -> block.rows.flatMap { it.keys } }
            .map { it.code }
            .toSet()
        for (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            assertTrue("function code " + code, codes.contains(code))
        }
        assertTrue(codes.contains(KeyEvent.KEYCODE_ESCAPE))
        assertTrue(codes.contains(KeyEvent.KEYCODE_DPAD_UP))
    }
}