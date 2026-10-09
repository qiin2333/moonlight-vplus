package com.limelight.gamemenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsMenuTest {
    @Test
    fun choicePageKeepsTheSelectedItemAndItsSourcePage() {
        val entries = DisplaySettingsMenu.choices(
            DisplaySettingsPage.SCREEN,
            listOf(
                DisplayChoice("0", "单屏", false, "只使用一块屏幕"),
                DisplayChoice("1", "双屏", true, "合并两块屏幕")
            )
        )

        assertEquals(listOf("0", "1"), entries.map { it.choiceValue })
        assertEquals(listOf(false, true), entries.map { it.selected })
        assertEquals(listOf("只使用一块屏幕", "合并两块屏幕"), entries.map { it.description })
        assertTrue(entries.all { it.page == DisplaySettingsPage.SCREEN })
    }
}
