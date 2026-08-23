package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbControllerShortcutStateMachineTest {
    @Test
    fun shortPressDoesNotOpenWheelOrRunClientAction() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val up = machine.onButtonSnapshot(0, 300, true)

        assertTrue(down.actions.contains(UsbControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(up.actions.contains(UsbControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(up.actions.contains(UsbControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(up.consumeAllInput)
        assertFalse(up.sendNeutralState)
    }

    @Test
    fun longPressShowsWheelWithoutCapturingOrNeutralizingHostInput() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val wheel = machine.onLongPressTimeout(850, true)

        assertTrue(wheel.actions.contains(UsbControllerShortcutStateMachine.Action.SHOW_WHEEL))
        assertTrue(wheel.wheelVisible)
        assertFalse(wheel.consumeAllInput)
        assertFalse(wheel.sendNeutralState)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun selectionUsesTwoStableFramesAndMapsCardinalDirections() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val firstRight = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        val secondRight = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        assertFalse(firstRight.actions.contains(UsbControllerShortcutStateMachine.Action.UPDATE_SELECTION))
        assertEquals(StartWheelAction.MOUSE, secondRight.selectedAction)

        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        val menu = machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        assertEquals(StartWheelAction.MENU, menu.selectedAction)
    }

    @Test
    fun digitalDpadSelectsOnFirstSnapshot() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.LEFT_FLAG,
            851,
            true
        )

        val selected = machine.onSelectionAxes(0f, 0f, 0f, 0f)

        assertEquals(StartWheelAction.PERFORMANCE, selected.selectedAction)
        assertTrue(selected.actions.contains(UsbControllerShortcutStateMachine.Action.UPDATE_SELECTION))
        assertFalse(selected.consumeAllInput)
        assertFalse(selected.sendNeutralState)
    }

    @Test
    fun tappedDpadDirectionCommitsWhenStartIsReleasedLater() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG,
            851,
            true
        )
        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)
        repeat(4) {
            machine.onSelectionAxes(0f, 0f, 0f, 0f)
        }

        val release = machine.onButtonSnapshot(0, 900, true)

        assertTrue(release.actions.contains(UsbControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
    }

    @Test
    fun menuCommitIsEmittedOnlyAfterStartUp() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)

        val commit = machine.onButtonSnapshot(0, 851, true)

        assertTrue(commit.actions.contains(UsbControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(commit.consumeAllInput)
        assertFalse(commit.sendNeutralState)
        assertNotNull(commit.menuOpenRequestId)
        assertFalse(machine.isMenuActive())

        val opened = machine.onGameMenuOpenResult(requireNotNull(commit.menuOpenRequestId), true)
        assertTrue(opened.consumeAllInput)
        assertTrue(opened.sendNeutralState)
        assertTrue(machine.isMenuActive())
    }

    @Test
    fun nonMenuActionsCommitOnceOnStartUp() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)

        val release = machine.onButtonSnapshot(0, 851, true)

        assertEquals(
            listOf(UsbControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS,
                UsbControllerShortcutStateMachine.Action.HIDE_WHEEL,
                UsbControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION),
            release.actions
        )
        assertFalse(release.consumeAllInput)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun disabledStartActionPassesThroughWithoutWheelOrClientCommit() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, false)
        val timeout = machine.onLongPressTimeout(850, false)
        val release = machine.onButtonSnapshot(0, 851, false)

        assertFalse(down.actions.contains(UsbControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(timeout.wheelVisible)
        assertFalse(release.actions.contains(UsbControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(release.actions.contains(UsbControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(release.consumeAllInput)
    }

    @Test
    fun activeMenuForwardsOnlySupportedNavigationAndWaitsForReleaseAfterFailure() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenu(machine)
        machine.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)

        val right = machine.onButtonSnapshot(ControllerPacket.RIGHT_FLAG, 900, true)
        assertTrue(right.consumeAllInput)
        assertEquals(
            listOf(UsbControllerShortcutStateMachine.ButtonChange(ControllerPacket.RIGHT_FLAG, true)),
            right.menuButtonChanges
        )

        machine.onGameMenuUnavailable()
        assertTrue(machine.isLocalInputCaptureActive())
        machine.onButtonSnapshot(0, 901, true)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun exitComboStillHasPriorityAndExitsOnRelease() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val pressed = machine.onButtonSnapshot(
            UsbControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            100,
            true
        )
        val released = machine.onButtonSnapshot(0, 101, true)

        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)
        assertEquals(
            listOf(UsbControllerShortcutStateMachine.Action.EXIT_STREAM),
            released.actions
        )
    }

    @Test
    fun exitComboHidesVisibleWheelBeforeWaitingForRelease() {
        val machine = UsbControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val pressed = machine.onButtonSnapshot(
            UsbControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            900,
            true
        )

        assertTrue(pressed.actions.contains(UsbControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS))
        assertTrue(pressed.actions.contains(UsbControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(pressed.wheelVisible)
        assertTrue(pressed.consumeAllInput)
    }

    private fun showWheel(machine: UsbControllerShortcutStateMachine) {
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
    }

    private fun requestMenu(machine: UsbControllerShortcutStateMachine): UsbControllerShortcutStateMachine.Update {
        showWheel(machine)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        return machine.onButtonSnapshot(0, 100 + TEST_LONG_PRESS_MS + 1, true)
    }

    companion object {
        private const val TEST_LONG_PRESS_MS = 750L
    }
}
