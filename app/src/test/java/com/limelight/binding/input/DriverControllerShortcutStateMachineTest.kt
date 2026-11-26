package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverControllerShortcutStateMachineTest {
    @Test
    fun shortPressDoesNotOpenWheelOrRunAnything() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val up = machine.onButtonSnapshot(0, 300, true)

        assertTrue(down.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(up.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertFalse(up.consumeAllInput)
        assertFalse(up.sendNeutralState)
    }

    @Test
    fun longPressShowsTheWheelWithoutCapturingOrNeutralizingHostInput() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val wheel = machine.onLongPressTimeout(850, true)

        assertTrue(wheel.actions.contains(DriverControllerShortcutStateMachine.Action.SHOW_WHEEL))
        assertTrue(wheel.wheelVisible)
        assertTrue(wheel.wheel.visible)
        assertFalse(wheel.consumeAllInput)
        assertFalse(wheel.sendNeutralState)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun axesSelectAndReleasingThemRunsTheItem() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val first = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        val selected = machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        assertEquals(StartWheelAction.MOUSE, selected.wheel.highlighted)
        assertTrue(first.sendNeutralState)
        assertFalse(selected.sendNeutralState)
        assertTrue(selected.consumeAllInput)

        val release = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertEquals(StartWheelAction.MOUSE, release.committedAction)
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertTrue(machine.isLocalInputCaptureActive())

        machine.onButtonSnapshot(0, 900, true)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun aTappedDpadDirectionRunsOnRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG, 851, true)
        val selected = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertEquals(StartWheelAction.MENU, selected.wheel.highlighted)

        val released = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)
        val commit = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertEquals(StartWheelAction.MENU, commit.committedAction)
        assertTrue(commit.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(released.committedAction != null)
    }

    @Test
    fun aDirectionHeldAtOpenIsNotSelectedUntilLetGo() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        machine.onSelectionAxes(0f, -0.9f, 0f, 0f)

        val shown = machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
        assertFalse(shown.wheel.armed)
        repeat(3) {
            assertNull(machine.onSelectionAxes(0f, -0.9f, 0f, 0f).wheel.highlighted)
        }

        val letGo = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertTrue(letGo.wheel.armed)
        assertNull(letGo.committedAction)
    }

    @Test
    fun theBButtonDismissesTheWheel() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val cancel = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG, 851, true)

        assertTrue(cancel.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertTrue(cancel.sendNeutralState)
        assertTrue(cancel.consumeAllInput)

        val released = machine.onButtonSnapshot(0, 900, true)
        assertFalse(released.consumeAllInput)
        assertNull(released.committedAction)
    }

    @Test
    fun theMenuWaitsForItsOwnRequest() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenu(machine)

        assertTrue(request.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertNotNull(request.menuOpenRequestId)
        assertFalse(machine.isMenuActive())

        val opened = machine.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertTrue(opened.consumeAllInput)
        assertTrue(machine.isMenuActive())

        // The player lets go of everything while the menu is open, then closes it.
        machine.onButtonSnapshot(0, 900, true)
        val dismissed = machine.onGameMenuUnavailable()
        assertFalse(machine.isMenuActive())
        assertFalse(dismissed.consumeAllInput)
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
        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM), released.actions)
    }

    @Test
    fun theQuitComboStillQuitsWhileTheWheelIsShown() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        // The wheel has already taken the stick, so it also holds the capture the combo must win over.
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        machine.onSelectionAxes(0f, 0f, 0.9f, 0f)
        assertTrue(machine.isLocalInputCaptureActive())

        val combo = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            851,
            true
        )

        assertTrue(combo.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(combo.actions.contains(DriverControllerShortcutStateMachine.Action.EXIT_STREAM))

        val released = machine.onButtonSnapshot(0, 900, true)
        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM), released.actions)
    }

    @Test
    fun menuFailureWaitsUntilEverythingIsReleased() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenu(machine)

        val failed = machine.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), false)
        assertTrue(failed.consumeAllInput)

        val released = machine.onButtonSnapshot(0, 900, true)
        assertFalse(released.consumeAllInput)

        val axes = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertFalse(axes.consumeAllInput)
    }

    @Test
    fun disabledStartPassesThrough() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, false)
        val timeout = machine.onLongPressTimeout(850, false)
        val release = machine.onButtonSnapshot(0, 851, false)

        assertFalse(down.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(timeout.wheelVisible)
        assertFalse(release.consumeAllInput)
    }

    private fun showWheel(machine: DriverControllerShortcutStateMachine) {
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
    }

    private fun requestMenu(machine: DriverControllerShortcutStateMachine): DriverControllerShortcutStateMachine.Update {
        showWheel(machine)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG, 851, true)
        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        return machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true).let {
            machine.onSelectionAxes(0f, 0f, 0f, 0f)
        }
    }

    companion object {
        private const val TEST_LONG_PRESS_MS = 750L
    }
}
