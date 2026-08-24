package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverControllerShortcutStateMachineTest {
    @Test
    fun shortPressDoesNotOpenWheelOrRunClientAction() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val up = machine.onButtonSnapshot(0, 300, true)

        assertTrue(down.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(up.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(up.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(up.consumeAllInput)
        assertFalse(up.sendNeutralState)
    }

    @Test
    fun longPressShowsWheelWithoutCapturingOrNeutralizingHostInput() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val wheel = machine.onLongPressTimeout(850, true)

        assertTrue(wheel.actions.contains(DriverControllerShortcutStateMachine.Action.SHOW_WHEEL))
        assertTrue(wheel.wheelVisible)
        assertFalse(wheel.consumeAllInput)
        assertFalse(wheel.sendNeutralState)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun selectionUsesTwoStableFramesAndMapsCardinalDirections() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val firstRight = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        val secondRight = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        assertFalse(firstRight.actions.contains(DriverControllerShortcutStateMachine.Action.UPDATE_SELECTION))
        assertEquals(StartWheelAction.MOUSE, secondRight.selectedAction)

        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        val menu = machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        assertEquals(StartWheelAction.MENU, menu.selectedAction)
    }

    @Test
    fun digitalDpadSelectsOnFirstSnapshot() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.LEFT_FLAG,
            851,
            true
        )

        val selected = machine.onSelectionAxes(0f, 0f, 0f, 0f)

        assertEquals(StartWheelAction.PERFORMANCE, selected.selectedAction)
        assertTrue(selected.actions.contains(DriverControllerShortcutStateMachine.Action.UPDATE_SELECTION))
        assertTrue(selected.consumeAllInput)
        assertTrue(selected.sendNeutralState)
    }

    @Test
    fun tappedDpadDirectionCommitsOnDirectionReleaseOnlyOnce() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG,
            851,
            true
        )
        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        val dpadRelease = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)
        val commit = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        val startRelease = machine.onButtonSnapshot(0, 900, true)

        assertTrue(dpadRelease.consumeAllInput)
        assertTrue(commit.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(startRelease.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
    }

    @Test
    fun nonMenuDpadActionRunsOnDirectionReleaseAndWaitsForStart() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.RIGHT_FLAG,
            851,
            true
        )
        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)
        val commit = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        val startRelease = machine.onButtonSnapshot(0, 900, true)

        assertTrue(commit.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertTrue(commit.consumeAllInput)
        assertFalse(startRelease.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(startRelease.consumeAllInput)
    }

    @Test
    fun capturedWheelDoesNotPromoteExitCombo() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.RIGHT_FLAG,
            851,
            true
        )
        machine.onSelectionAxes(0f, 0f, 0f, 0f)

        val combo = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            852,
            true
        )

        assertTrue(combo.consumeAllInput)
        assertFalse(combo.actions.contains(DriverControllerShortcutStateMachine.Action.EXIT_STREAM))
    }

    @Test
    fun menuCommitIsEmittedOnlyAfterStartUp() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        val firstSelection = machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)

        val commit = machine.onButtonSnapshot(0, 851, true)

        assertTrue(firstSelection.consumeAllInput)
        assertTrue(firstSelection.sendNeutralState)
        assertTrue(commit.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertTrue(commit.consumeAllInput)
        assertFalse(commit.sendNeutralState)
        assertNotNull(commit.menuOpenRequestId)
        assertFalse(machine.isMenuActive())

        val opened = machine.onGameMenuOpenResult(requireNotNull(commit.menuOpenRequestId), true)
        assertTrue(opened.consumeAllInput)
        assertFalse(opened.sendNeutralState)
        assertTrue(machine.isMenuActive())
    }

    @Test
    fun nonMenuActionsCommitOnceOnStartUp() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        assertTrue(machine.needsAxisUpdates())
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)

        val release = machine.onButtonSnapshot(0, 851, true)

        assertEquals(
            listOf(DriverControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS,
                DriverControllerShortcutStateMachine.Action.HIDE_WHEEL,
                DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION),
            release.actions
        )
        assertTrue(release.consumeAllInput)
        assertTrue(machine.isLocalInputCaptureActive())
        assertTrue(machine.needsAxisUpdates())

        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertFalse(machine.isLocalInputCaptureActive())
        assertFalse(machine.needsAxisUpdates())
    }

    @Test
    fun longPressReleaseWithoutDirectionContinuesHostInput() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val release = machine.onButtonSnapshot(0, 900, true)

        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(release.consumeAllInput)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun disabledStartActionPassesThroughWithoutWheelOrClientCommit() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, false)
        val timeout = machine.onLongPressTimeout(850, false)
        val release = machine.onButtonSnapshot(0, 851, false)

        assertFalse(down.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(timeout.wheelVisible)
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(release.consumeAllInput)
    }

    @Test
    fun activeMenuForwardsOnlySupportedNavigationAndWaitsForReleaseAfterFailure() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenu(machine)
        machine.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)

        val right = machine.onButtonSnapshot(ControllerPacket.RIGHT_FLAG, 900, true)
        assertTrue(right.consumeAllInput)
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.ButtonChange(ControllerPacket.RIGHT_FLAG, true)),
            right.menuButtonChanges
        )

        machine.onGameMenuUnavailable()
        assertTrue(machine.isLocalInputCaptureActive())
        machine.onButtonSnapshot(0, 901, true)
        assertTrue(machine.isLocalInputCaptureActive())
        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun exitComboStillHasPriorityAndExitsOnRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val pressed = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            100,
            true
        )
        val released = machine.onButtonSnapshot(0, 101, true)

        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM),
            released.actions
        )
    }

    @Test
    fun exitComboHidesVisibleWheelBeforeWaitingForRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val pressed = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            900,
            true
        )

        assertTrue(pressed.actions.contains(DriverControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS))
        assertTrue(pressed.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(pressed.wheelVisible)
        assertTrue(pressed.consumeAllInput)
    }

    private fun showWheel(machine: DriverControllerShortcutStateMachine) {
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
    }

    private fun requestMenu(machine: DriverControllerShortcutStateMachine): DriverControllerShortcutStateMachine.Update {
        showWheel(machine)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        return machine.onButtonSnapshot(0, 100 + TEST_LONG_PRESS_MS + 1, true)
    }

    companion object {
        private const val TEST_LONG_PRESS_MS = 750L
    }
}
