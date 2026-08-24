package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGestureReducerTest {
    @Test
    fun disabledStartNeverLeavesIdle() {
        val reducer = StartGestureReducer(750)

        reducer.onStartDown(100, false)
        val timeout = reducer.onLongPressTimeout(900, false)
        val release = reducer.onStartUp(901)

        assertFalse(timeout.wheelVisible)
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun rightStickSelectionUsesHysteresis() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        val shown = reducer.onLongPressTimeout(850, true)
        assertTrue(shown.wheelVisible)

        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val mouse = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, mouse.selectedAction)

        val withinExitDeadzone = reducer.onSelection(0f, 0f, 0.40f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, withinExitDeadzone.selectedAction)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val center = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.CONTINUE, center.selectedAction)
    }

    @Test
    fun dpadSelectsImmediatelyAndWinsOverAnalogInput() {
        val reducer = visibleWheel()

        val selected = reducer.onSelection(
            leftStickX = 0f,
            leftStickY = 0f,
            rightStickX = 0.9f,
            rightStickY = 0f,
            dpadFlags = ControllerPacket.UP_FLAG
        )

        assertEquals(StartWheelAction.MENU, selected.selectedAction)
        assertTrue(selected.actions.contains(StartGestureReducer.EventAction.SELECTION_CHANGED))
    }

    @Test
    fun dpadReleaseCommitsImmediatelyWithoutStartRelease() {
        val reducer = visibleWheel()

        val pressed = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)
        assertEquals(StartWheelAction.MENU, released.committedAction)
        assertTrue(released.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())
    }

    @Test
    fun heldDpadDirectionCanChangeBeforeRelease() {
        val reducer = visibleWheel()

        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        val changed = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.RIGHT_FLAG)

        assertEquals(StartWheelAction.MOUSE, changed.selectedAction)
    }

    @Test
    fun dpadReleaseCommitsBeforeHeldAnalogCanTakeOwnership() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0.9f, 0f, ControllerPacket.UP_FLAG)

        val released = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)

        assertEquals(StartWheelAction.MENU, released.committedAction)
        assertTrue(released.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
    }

    @Test
    fun longPressWithoutDirectionKeepsPassthroughAndContinuesGameOnRelease() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)

        val shown = reducer.onLongPressTimeout(850, true)
        val release = reducer.onStartUp(900)

        assertTrue(shown.wheelVisible)
        assertFalse(shown.consumeAllInput)
        assertFalse(shown.sendNeutralState)
        assertTrue(release.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun firstAnalogIntentStartsCaptureAndNeutralizesOnlyOnce() {
        val reducer = visibleWheel()

        val first = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val second = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)

        assertTrue(first.consumeAllInput)
        assertTrue(first.sendNeutralState)
        assertTrue(second.consumeAllInput)
        assertFalse(second.sendNeutralState)
        assertEquals(StartWheelAction.MOUSE, second.selectedAction)
    }

    @Test
    fun leftAndRightSticksCanTakeOwnershipAfterCentering() {
        val reducer = visibleWheel()

        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        val left = reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.PERFORMANCE, left.selectedAction)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val centered = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.CONTINUE, centered.selectedAction)

        reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        val right = reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        assertEquals(StartWheelAction.KEYBOARD, right.selectedAction)
    }

    @Test
    fun activeAnalogSourceBlocksOtherStickUntilRelease() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)

        reducer.onSelection(0f, -0.9f, 0.4f, 0f, 0)
        val blocked = reducer.onSelection(0f, -0.9f, 0.4f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, blocked.selectedAction)

        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val switched = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MENU, switched.selectedAction)
    }

    @Test
    fun strongerStickWinsWhenNoAnalogSourceIsActive() {
        val reducer = visibleWheel()

        reducer.onSelection(-0.95f, 0f, 0.7f, 0f, 0)
        val selected = reducer.onSelection(-0.95f, 0f, 0.7f, 0f, 0)

        assertEquals(StartWheelAction.PERFORMANCE, selected.selectedAction)
    }

    @Test
    fun hostStickYConversionPreservesWheelUpAndDownDirections() {
        val upReducer = visibleWheel()
        repeat(2) {
            upReducer.onSelection(0f, hostStickYToWheelAxis(32766), 0f, 0f, 0)
        }
        assertEquals(StartWheelAction.MENU, upReducer.selectedAction())

        val downReducer = visibleWheel()
        repeat(2) {
            downReducer.onSelection(0f, hostStickYToWheelAxis(-32766), 0f, 0f, 0)
        }
        assertEquals(StartWheelAction.KEYBOARD, downReducer.selectedAction())
    }

    @Test
    fun analogDriftBelowActivationThresholdDoesNotSelect() {
        val reducer = visibleWheel()

        repeat(4) {
            reducer.onSelection(0.5f, 0f, 0f, 0f, 0)
        }

        assertEquals(StartWheelAction.CONTINUE, reducer.selectedAction())
    }

    @Test
    fun diagonalDpadUsesOneDeterministicHorizontalAction() {
        val reducer = visibleWheel()

        val selected = reducer.onSelection(
            0f,
            0f,
            0f,
            0f,
            ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG
        )

        assertEquals(StartWheelAction.MOUSE, selected.selectedAction)
    }

    @Test
    fun menuRequestKeepsCaptureAndDoesNotNeutralizeTwice() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        val firstSelection = reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)

        val request = reducer.onStartUp(851)
        assertTrue(firstSelection.consumeAllInput)
        assertTrue(firstSelection.sendNeutralState)
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())
        assertTrue(request.consumeAllInput)
        assertFalse(request.sendNeutralState)
        assertTrue(request.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))

        val opened = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(opened.consumeAllInput)
        assertFalse(opened.sendNeutralState)
    }

    @Test
    fun openedMenuKeepsOpenerPressedUntilPhysicalRelease() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        val request = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)

        val dismissed = reducer.onGameMenuUnavailable()
        val released = reducer.onStartUp(900)

        assertTrue(dismissed.consumeAllInput)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(released.consumeAllInput)
    }

    @Test
    fun analogCommitWaitsForStickToCenterBeforeRestoringHostInput() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)

        val committed = reducer.onStartUp(900, rightStickX = 0.9f)
        val stillHeld = reducer.onInputSnapshot(0, rightStickX = 0.9f)
        val centered = reducer.onInputSnapshot(0, rightStickX = 0f)

        assertEquals(StartWheelAction.MOUSE, committed.committedAction)
        assertTrue(committed.consumeAllInput)
        assertTrue(stillHeld.consumeAllInput)
        assertFalse(centered.consumeAllInput)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun menuFailureRollsBackAndStaleResultIsIgnored() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        val request = reducer.onStartUp(851)
        val failed = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), false)

        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(failed.consumeAllInput)
        assertFalse(failed.sendNeutralState)

        val stale = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(stale.consumeAllInput)
    }

    @Test
    fun menuFailureWithPressedInputWaitsUntilRelease() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        val request = reducer.onStartUp(851, buttonFlags = ControllerPacket.A_FLAG)

        val failed = reducer.onGameMenuOpenResult(
            requireNotNull(request.menuOpenRequestId),
            false
        )
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertTrue(failed.consumeAllInput)

        val released = reducer.onInputSnapshot(0)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(released.consumeAllInput)
    }

    @Test
    fun menuFailureWithHeldStickWaitsUntilCenter() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        val request = reducer.onStartUp(851, rightStickY = -0.9f)

        val failed = reducer.onGameMenuOpenResult(
            requireNotNull(request.menuOpenRequestId),
            false
        )
        val centered = reducer.onInputSnapshot(0, rightStickY = 0f)

        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertTrue(failed.consumeAllInput)
        assertFalse(centered.consumeAllInput)
    }

    @Test
    fun externalMenuDismissalWithoutPressedInputReturnsToIdle() {
        val reducer = StartGestureReducer(750)

        val opened = reducer.onGameMenuOpenedExternally()
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(opened.consumeAllInput)
        assertTrue(opened.sendNeutralState)

        val dismissed = reducer.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(dismissed.consumeAllInput)

        val nextPress = reducer.onStartDown(1_000, true)
        assertTrue(nextPress.actions.contains(StartGestureReducer.EventAction.SCHEDULE_LONG_PRESS))
    }

    @Test
    fun eachExternalMenuSessionSendsItsOwnNeutralState() {
        val reducer = StartGestureReducer(750)

        val firstOpen = reducer.onGameMenuOpenedExternally()
        reducer.onGameMenuUnavailable()
        val secondOpen = reducer.onGameMenuOpenedExternally()

        assertTrue(firstOpen.sendNeutralState)
        assertTrue(secondOpen.sendNeutralState)
    }

    @Test
    fun externalMenuOpenCancelsAndHidesVisibleWheel() {
        val reducer = visibleWheel()

        val opened = reducer.onGameMenuOpenedExternally()

        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(opened.actions.contains(StartGestureReducer.EventAction.CANCEL_LONG_PRESS))
        assertTrue(opened.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(opened.wheelVisible)
    }

    @Test
    fun confirmedExternalOpenSupersedesPendingRequest() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        val request = reducer.onStartUp(851)

        reducer.onGameMenuOpenedExternally()
        val staleFailure = reducer.onGameMenuOpenResult(
            requireNotNull(request.menuOpenRequestId),
            false
        )

        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(staleFailure.consumeAllInput)
        assertFalse(staleFailure.sendNeutralState)
    }

    private fun visibleWheel(): StartGestureReducer {
        return StartGestureReducer(750).also { reducer ->
            reducer.onStartDown(100, true)
            reducer.onLongPressTimeout(850, true)
        }
    }
}
