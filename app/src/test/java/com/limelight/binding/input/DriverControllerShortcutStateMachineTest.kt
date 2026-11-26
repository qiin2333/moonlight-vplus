package com.limelight.binding.input

import com.limelight.binding.input.DriverControllerShortcutStateMachine.Action
import com.limelight.binding.input.DriverControllerShortcutStateMachine.Update
import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The USB-takeover side of the wheel: snapshots instead of events, same state machine. */
class DriverControllerShortcutStateMachineTest {
    @Test
    fun shortPressAndDisabledStartPassThrough() {
        val short = machine()
        assertTrue(short.buttons(ControllerPacket.PLAY_FLAG, 100).actions.contains(Action.SCHEDULE_LONG_PRESS))
        val up = short.buttons(0, 300)
        assertFalse(up.actions.contains(Action.COMMIT_ACTION))
        assertFalse(up.consumeAllInput)
        assertFalse(up.sendNeutralState)

        val off = machine()
        assertFalse(
            off.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, false)
                .actions.contains(Action.SCHEDULE_LONG_PRESS)
        )
        assertFalse(off.onLongPressTimeout(850, false).wheelVisible)
        assertFalse(off.onButtonSnapshot(0, 851, false).consumeAllInput)
    }

    @Test
    fun longPressShowsTheWheelWithoutCapturingOrNeutralizingHostInput() {
        val wheel = machine().open()

        assertTrue(wheel.actions.contains(Action.SHOW_WHEEL))
        assertTrue(wheel.wheelVisible)
        assertFalse(wheel.consumeAllInput)
        assertFalse(wheel.sendNeutralState)
    }

    @Test
    fun axesSelectAndReleasingThemRunsTheItem() {
        val machine = machine().also { it.open() }

        val first = machine.pull(rightX = 0.9f, frames = 1)
        val selected = machine.pull(rightX = 0.9f, frames = 1)
        assertEquals(StartWheelAction.MOUSE, selected.wheel.highlighted)
        assertTrue(first.sendNeutralState)
        assertFalse(selected.sendNeutralState)
        assertTrue(selected.consumeAllInput)

        val release = machine.pull(frames = 1)
        assertEquals(StartWheelAction.MOUSE, release.committedAction)
        assertTrue(release.actions.contains(Action.COMMIT_ACTION))
        assertTrue(release.actions.contains(Action.HIDE_WHEEL))
        assertTrue(machine.isLocalInputCaptureActive())

        machine.buttons(0, 900)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun aTappedDpadDirectionRunsOnRelease() {
        val machine = machine().also { it.open() }

        machine.buttons(ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG, 851)
        assertEquals(StartWheelAction.MENU, machine.pull(frames = 1).wheel.highlighted)
        val released = machine.buttons(ControllerPacket.PLAY_FLAG, 852)
        val commit = machine.pull(frames = 1)

        assertEquals(StartWheelAction.MENU, commit.committedAction)
        assertTrue(commit.actions.contains(Action.OPEN_GAME_MENU))
        assertNull(released.committedAction)
    }

    @Test
    fun aDirectionHeldAtOpenIsNotSelectedUntilLetGo() {
        val machine = machine()
        machine.buttons(ControllerPacket.PLAY_FLAG, 100)
        machine.pull(leftY = -0.9f, frames = 1)

        val shown = machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)

        assertFalse(shown.wheel.armed)
        repeat(3) { assertNull(machine.pull(leftY = -0.9f, frames = 1).wheel.highlighted) }
        val letGo = machine.pull(frames = 1)
        assertTrue(letGo.wheel.armed)
        assertNull(letGo.committedAction)
    }

    @Test
    fun theBDismissesEvenInTheReportThatReleasesStart() {
        val machine = machine().also {
            it.open()
            it.pull(rightX = 0.9f)
        }

        // Start and B change in one driver snapshot: the dismiss wins over the commit.
        val cancel = machine.buttons(ControllerPacket.B_FLAG, 851)

        assertTrue(cancel.actions.contains(Action.HIDE_WHEEL))
        assertNull(cancel.committedAction)
        assertTrue(cancel.consumeAllInput)

        val released = machine.buttons(0, 900)
        assertNull(released.committedAction)
        // The wheel had captured the stick too, so the capture ends once it is back in the middle.
        assertTrue(machine.isLocalInputCaptureActive())
        machine.pull(frames = 1)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun theMenuWaitsForItsOwnRequestAndAFailureWaitsForRelease() {
        val opening = machine().also { it.open() }
        val request = requestMenu(opening)
        assertTrue(request.actions.contains(Action.OPEN_GAME_MENU))
        assertNotNull(request.menuOpenRequestId)
        val opened = opening.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertTrue(opened.consumeAllInput)
        assertTrue(opening.isMenuActive())

        // The player lets go of everything while the menu is open, then closes it.
        opening.buttons(0, 900)
        val dismissed = opening.onGameMenuUnavailable()
        assertFalse(opening.isMenuActive())
        assertFalse(dismissed.consumeAllInput)
        assertFalse(opening.isLocalInputCaptureActive())

        val failing = machine().also { it.open() }
        val failedRequest = requestMenu(failing)
        val failed = failing.onGameMenuOpenResult(requireNotNull(failedRequest.menuOpenRequestId), false)
        assertTrue(failed.consumeAllInput)
        assertFalse(failing.buttons(0, 900).consumeAllInput)
        assertFalse(failing.pull(frames = 1).consumeAllInput)
    }

    @Test
    fun theExitComboWinsAtIdleAndOverTheWheel() {
        val idle = machine()
        val pressed = idle.buttons(DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS, 100)
        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)
        assertEquals(listOf(Action.EXIT_STREAM), idle.buttons(0, 101).actions)

        // The wheel has already taken the stick, so it holds the capture the combo must win over.
        val overWheel = machine().also {
            it.open()
            it.pull(rightX = 0.9f)
        }
        assertTrue(overWheel.isLocalInputCaptureActive())
        val combo = overWheel.buttons(DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS, 851)
        assertTrue(combo.actions.contains(Action.HIDE_WHEEL))
        assertFalse(combo.actions.contains(Action.EXIT_STREAM))
        assertEquals(listOf(Action.EXIT_STREAM), overWheel.buttons(0, 900).actions)
    }

    private fun machine() = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

    private fun DriverControllerShortcutStateMachine.open(): Update {
        buttons(ControllerPacket.PLAY_FLAG, 100)
        return onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
    }

    /** Holds a direction for [frames] snapshots; analog selection needs two stable frames. */
    private fun DriverControllerShortcutStateMachine.pull(
        leftX: Float = 0f,
        leftY: Float = 0f,
        rightX: Float = 0f,
        rightY: Float = 0f,
        frames: Int = 2
    ): Update {
        var update = onSelectionAxes(leftX, leftY, rightX, rightY)
        repeat(frames - 1) { update = onSelectionAxes(leftX, leftY, rightX, rightY) }
        return update
    }

    private fun DriverControllerShortcutStateMachine.buttons(flags: Int, at: Long): Update =
        onButtonSnapshot(flags, at, true)

    /** A tapped D-pad Up needs two snapshots: the press, then the report that commits it. */
    private fun requestMenu(machine: DriverControllerShortcutStateMachine): Update {
        machine.buttons(ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG, 851)
        machine.pull(frames = 1)
        machine.buttons(ControllerPacket.PLAY_FLAG, 852)
        return machine.pull(frames = 1)
    }

    private companion object {
        private const val TEST_LONG_PRESS_MS = 750L
    }
}
