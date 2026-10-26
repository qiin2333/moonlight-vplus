package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppActionSheetInitialPositionTest {
    @Test
    fun longListStartsAtSavedChoice() {
        val actions = (0..40).map { AppActionSheet.Action(it, "Choice $it") }

        assertEquals(AppActionSheet.InitialActionPosition(37, 37),
            AppActionSheet.initialActionPosition(actions, 37))
    }

    @Test
    fun disabledSavedChoiceRemainsVisibleAndFocusUsesClosestEnabledRow() {
        val actions = (0..4).map { AppActionSheet.Action(it, "Choice $it", enabled = it != 2) }

        assertEquals(AppActionSheet.InitialActionPosition(2, 3),
            AppActionSheet.initialActionPosition(actions, 2))
    }

    @Test
    fun ordinaryMenuSkipsLeadingDisabledActions() {
        val actions = listOf(
            AppActionSheet.Action(10, "Unavailable", enabled = false),
            AppActionSheet.Action(20, "Available")
        )

        assertEquals(AppActionSheet.InitialActionPosition(1, 1),
            AppActionSheet.initialActionPosition(actions, null))
        assertEquals(AppActionSheet.InitialActionPosition(1, 1),
            AppActionSheet.initialActionPosition(actions, 99))
    }

    @Test
    fun entirelyDisabledListDoesNotRequestFocus() {
        val actions = (0..4).map { AppActionSheet.Action(it, "Choice $it", enabled = false) }
        val position = AppActionSheet.initialActionPosition(actions, 3)

        assertEquals(3, position.scrollIndex)
        assertNull(position.focusIndex)
        assertEquals(AppActionSheet.InitialActionPosition(null, null),
            AppActionSheet.initialActionPosition(emptyList(), 3))
    }

    @Test
    fun multiSelectStartsAtFirstSavedChoiceInsteadOfFirstRow() {
        val actions = (0..40).map { AppActionSheet.Action(it, "Choice $it", checked = it == 30 || it == 37) }

        assertEquals(AppActionSheet.InitialActionPosition(30, 30),
            AppActionSheet.initialMultiSelectPosition(actions))
    }

    @Test
    fun multiSelectShowsDisabledSavedChoiceAndFallsBackToAnEnabledFocusTarget() {
        val actions = listOf(
            AppActionSheet.Action(1, "First", enabled = false, checked = false),
            AppActionSheet.Action(2, "Saved", enabled = false, checked = true),
            AppActionSheet.Action(3, "Next", checked = false)
        )

        assertEquals(AppActionSheet.InitialActionPosition(1, 2),
            AppActionSheet.initialMultiSelectPosition(actions))
        assertEquals(AppActionSheet.InitialActionPosition(2, 2),
            AppActionSheet.initialMultiSelectPosition(actions.map { it.copy(checked = false) }))
    }
}
