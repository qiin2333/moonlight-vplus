package com.limelight.binding.input.advance_setting

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPageLayoutsTest {
    @Test
    fun mainPagesKeepExpectedRowsAndStudioNavigationGrid() {
        for ((tier, units, rows) in listOf(
            Triple(KeyboardTier.POCKET, 10, 4),
            Triple(KeyboardTier.STANDARD, 15, 5),
            Triple(KeyboardTier.STUDIO, 18, 5)
        )) {
            val spec = KeyboardPageLayouts.spec(KeyboardPage.MAIN, tier)
            assertEquals(units, spec.units)
            assertEquals(rows, spec.rows)
            val alpha = spec.blocks.filterIsInstance<KeyboardBlockSpec.Rows>().single()
            assertEquals(rows, alpha.rows.size)
            assertTrue(alpha.rows.flatMap { it.keys }.any { it.code == KeyEvent.KEYCODE_Q })
            assertTrue(alpha.rows.flatMap { it.keys }.any { it.code == KeyEvent.KEYCODE_ENTER })
            val grids = spec.blocks.filterIsInstance<KeyboardBlockSpec.Grid>()
            if (tier == KeyboardTier.STUDIO) {
                val grid = grids.single()
                assertEquals(3, grid.columns)
                assertEquals(5, grid.rows)
                assertEquals(KeyEvent.KEYCODE_DPAD_UP, grid.cells.single { it.column == 2 && it.row == 4 }.spec?.code)
                assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, grid.cells.single { it.column == 2 && it.row == 5 }.spec?.code)
            } else {
                assertTrue(grids.isEmpty())
            }
        }
    }

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
    fun compactNumKeepsBackslashAndShiftedParentheses() {
        val spec = KeyboardPageLayouts.spec(KeyboardPage.NUM, KeyboardTier.POCKET)
        val keys = spec.blocks
            .filterIsInstance<KeyboardBlockSpec.Rows>()
            .flatMap { block -> block.rows.flatMap { it.keys } }
        assertTrue(keys.any { it.code == KeyEvent.KEYCODE_BACKSLASH })
        val parentheses = keys.filter { it.label == "(" || it.label == ")" }
        assertEquals(2, parentheses.size)
        assertTrue(parentheses.all { it.shift })
    }

    @Test
    fun compactSymbolsIncludeApostropheAndBothGraveVariants() {
        val keys = KeyboardPageLayouts.spec(KeyboardPage.NUM, KeyboardTier.POCKET).rowKeys()
        assertTrue(keys.any { it.label == "'" && it.sendCode == KeyEvent.KEYCODE_APOSTROPHE && !it.shift })
        assertTrue(keys.any { it.label == "`" && it.sendCode == KeyEvent.KEYCODE_GRAVE && !it.shift })
        assertTrue(keys.any { it.label == "~" && it.sendCode == KeyEvent.KEYCODE_GRAVE && it.shift })
    }

    @Test
    fun compactNumpadIsReachableAndHasDistinctKeyCodes() {
        val symbols = KeyboardPageLayouts.spec(KeyboardPage.NUM, KeyboardTier.POCKET).rowKeys()
        assertTrue(symbols.any { it.page == KeyboardPage.NUMPAD })

        val numpad = KeyboardPageLayouts.spec(KeyboardPage.NUMPAD, KeyboardTier.POCKET)
        assertEquals(10, numpad.units)
        assertEquals(4, numpad.rows)
        val keys = numpad.rowKeys()
        for (code in KeyEvent.KEYCODE_NUM_LOCK..KeyEvent.KEYCODE_NUMPAD_DOT) {
            assertTrue("numpad code " + code, keys.any { it.sendCode == code })
        }
        assertTrue(keys.any { it.page == KeyboardPage.NUM })
        assertTrue(keys.any { it.page == KeyboardPage.MAIN })
    }

    private fun KeyboardPageSpec.rowKeys(): List<KeySpec> = blocks
        .filterIsInstance<KeyboardBlockSpec.Rows>()
        .flatMap { block -> block.rows.flatMap { it.keys } }

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
