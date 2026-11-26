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
    fun shortPressDoesNotOpenWheelOrRunClientAction() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val down = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val up = machine.onButtonSnapshot(0, 300, true)

        assertTrue(down.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(up.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(up.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
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
        assertTrue(wheel.wheel.visible)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, wheel.wheel.level)
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
        assertEquals(StartWheelAction.MOUSE, secondRight.wheel.highlighted)

        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        val menu = machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        assertEquals(StartWheelAction.MENU, menu.wheel.highlighted)
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

        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, selected.wheel.highlighted)
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

        assertTrue(commit.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertEquals(StartWheelAction.MOUSE, commit.committedAction)
        assertTrue(commit.consumeAllInput)
        assertFalse(startRelease.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
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
                DriverControllerShortcutStateMachine.Action.COMMIT_ACTION),
            release.actions
        )
        assertEquals(StartWheelAction.MOUSE, release.committedAction)
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
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
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
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
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

    // ========== Two-ring wheel ==========

    @Test
    fun bumperPressesDriveTheRingsAndTheOuterItemRunsOnStartRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)

        val deeper = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG,
            851,
            true
        )

        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.RING_CHANGED), deeper.actions)
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, deeper.wheel.level)
        assertEquals(StartWheelCategory.INPUT, deeper.wheel.category)
        // The category's own item is on its direction, so it stays what Start would run.
        assertEquals(StartWheelAction.MOUSE, deeper.wheel.highlighted)
        assertTrue(deeper.wheelVisible)
        assertTrue(deeper.consumeAllInput)
        // The selection already captured the input, so the bumper is not announced to the host again.
        assertFalse(deeper.sendNeutralState)

        // The held direction keeps that item highlighted.
        val held = machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        assertEquals(1, held.wheel.slot)
        assertEquals(StartWheelAction.MOUSE, held.wheel.highlighted)

        machine.onSelectionAxes(0f, 0f, 0f, 0f)
        machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        val gyro = machine.onSelectionAxes(0f, 0f, 0f, -0.9f)
        assertEquals(StartWheelAction.VIRTUAL_CONTROLLER, gyro.wheel.highlighted)

        val bumperReleased = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 860, true)
        assertTrue(bumperReleased.actions.isEmpty())
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, bumperReleased.wheel.level)

        val release = machine.onButtonSnapshot(0, 900, true)

        assertEquals(StartWheelAction.VIRTUAL_CONTROLLER, release.committedAction)
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
    }

    @Test
    fun lettingTheStickGoBeforeStartRunsTheHighlightedItem() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0f, -0.9f, 0f, 0f)
        machine.onSelectionAxes(0f, -0.9f, 0f, 0f)

        // The stick springs back while Start is still held.
        val letGo = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertEquals(StartWheelAction.MENU, letGo.wheel.highlighted)
        assertTrue(letGo.actions.isEmpty())

        val release = machine.onButtonSnapshot(0, 900, true)
        assertEquals(StartWheelAction.MENU, release.committedAction)
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
    }

    @Test
    fun aStickHeldSinceBeforeTheWheelAppearedDoesNotSelect() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        // The player keeps moving up in the game while Start is held.
        machine.onSelectionAxes(0f, -0.9f, 0f, 0f)

        val shown = machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
        assertTrue(shown.actions.contains(DriverControllerShortcutStateMachine.Action.SHOW_WHEEL))
        assertFalse(shown.wheel.armed)
        repeat(3) {
            assertNull(machine.onSelectionAxes(0f, -0.9f, 0f, 0f).wheel.highlighted)
        }

        val release = machine.onButtonSnapshot(0, 900, true)
        assertNull(release.committedAction)
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
    }

    @Test
    fun aDpadDirectionHeldAtTheMomentTheWheelAppearsDoesNotSelectOrCommit() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.UP_FLAG, 100, true)
        val shown = machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)
        assertTrue(shown.actions.contains(DriverControllerShortcutStateMachine.Action.SHOW_WHEEL))
        assertFalse(shown.wheel.armed)

        // The driver keeps sending axis frames while the direction is held.
        val held = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertEquals(-1, held.wheel.slot)
        assertNull(held.committedAction)

        // Letting the held direction go runs nothing and arms the wheel again.
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 851, true)
        val letGo = machine.onSelectionAxes(0f, 0f, 0f, 0f)
        assertTrue(letGo.wheel.armed)
        assertNull(letGo.committedAction)
    }

    @Test
    fun theQuitComboStillQuitsWhileTheWheelIsShown() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val combo = machine.onButtonSnapshot(DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS, 851, true)

        assertTrue(combo.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(combo.actions.contains(DriverControllerShortcutStateMachine.Action.EXIT_STREAM))

        val released = machine.onButtonSnapshot(0, 900, true)
        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM), released.actions)
    }

    @Test
    fun leftBumperFoldsTheOuterRingAndDismissesTheCategoryRing() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG, 851, true)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)

        val back = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG, 853, true)

        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.RING_CHANGED), back.actions)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, back.wheel.level)
        assertNull(back.wheel.category)
        assertTrue(back.wheelVisible)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 854, true)
        val dismissed = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.LB_FLAG, 855, true)

        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL), dismissed.actions)
        assertFalse(dismissed.wheelVisible)
        assertTrue(dismissed.consumeAllInput)
        assertTrue(machine.isLocalInputCaptureActive())
    }

    @Test
    fun dismissBeforeAnySelectionSwallowsInputUntilEverythingIsReleased() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val cancel = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            851,
            true
        )

        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL), cancel.actions)
        assertFalse(cancel.wheelVisible)
        // B dismissed the wheel, so it is the wheel's, and so is the Start still held.
        assertTrue(cancel.sendNeutralState)
        assertTrue(cancel.consumeAllInput)
        assertTrue(machine.isLocalInputCaptureActive())

        val release = machine.onButtonSnapshot(0, 900, true)
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertFalse(release.consumeAllInput)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun quitComboCompletedAfterADismissingBumperStillExitsOnRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val dismiss = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            851,
            true
        )

        // LB is the wheel's back key, but Start, Back and RB are held with it: that is the quit combo.
        assertTrue(dismiss.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertTrue(dismiss.consumeAllInput)
        assertFalse(dismiss.actions.contains(DriverControllerShortcutStateMachine.Action.EXIT_STREAM))

        val released = machine.onButtonSnapshot(0, 900, true)
        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM), released.actions)
    }

    @Test
    fun bumperWithoutASelectedCategoryStillTakesTheInput() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)

        val pressed = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG,
            851,
            true
        )

        assertTrue(pressed.actions.isEmpty())
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, pressed.wheel.level)
        assertTrue(pressed.wheelVisible)
        assertTrue(pressed.sendNeutralState)
        assertTrue(pressed.consumeAllInput)

        val held = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG, 852, true)
        assertTrue(held.actions.isEmpty())
        assertFalse(held.sendNeutralState)

        val release = machine.onButtonSnapshot(0, 853, true)
        assertTrue(release.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_WHEEL))
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.COMMIT_ACTION))
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun heldBumperIsNotFedTwice() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG, 851, true)

        val stillHeld = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG or ControllerPacket.RB_FLAG, 852, true)

        assertTrue(stillHeld.actions.isEmpty())
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, stillHeld.wheel.level)
    }

    @Test
    fun startReleaseWinsOverABumperPressedInTheSameSnapshot() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        showWheel(machine)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)
        machine.onSelectionAxes(0.9f, 0f, 0f, 0f)

        val release = machine.onButtonSnapshot(ControllerPacket.RB_FLAG, 851, true)

        assertEquals(StartWheelAction.MOUSE, release.committedAction)
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.RING_CHANGED))
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, release.wheel.level)
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
