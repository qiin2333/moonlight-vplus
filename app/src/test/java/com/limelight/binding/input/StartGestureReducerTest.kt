package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun holdingStartShowsTheWheelWithoutTakingInputYet() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)

        val shown = reducer.onLongPressTimeout(850, true)

        assertTrue(shown.wheelVisible)
        assertTrue(shown.wheel.visible)
        assertFalse(shown.consumeAllInput)
        assertFalse(shown.sendNeutralState)
        assertEquals(-1, shown.wheel.slot)
    }

    @Test
    fun everySectorPointsAtItsItem() {
        val vectors = mapOf(
            0 to (0f to -0.9f),
            1 to (0.7f to -0.7f),
            2 to (0.9f to 0f),
            3 to (0.7f to 0.7f),
            4 to (0f to 0.9f),
            5 to (-0.7f to 0.7f),
            6 to (-0.9f to 0f),
            7 to (-0.7f to -0.7f)
        )
        vectors.forEach { (slot, vector) ->
            val reducer = visibleWheel()
            reducer.onSelection(vector.first, vector.second, 0f, 0f, 0)
            val selected = reducer.onSelection(vector.first, vector.second, 0f, 0f, 0)
            assertEquals("slot $slot", slot, selected.wheel.slot)
            assertEquals("slot $slot", StartWheelLayout.action(slot), selected.wheel.highlighted)
        }
    }

    @Test
    fun selectionUsesTwoStableFramesAndHysteresis() {
        val reducer = visibleWheel()

        val first = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertNull(first.wheel.highlighted)
        val second = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, second.wheel.highlighted)

        // Inside the release band the highlight stays.
        val drifting = reducer.onSelection(0f, 0f, 0.40f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, drifting.wheel.highlighted)
    }

    @Test
    fun releasingTheStickRunsTheItemItPointsAt() {
        val reducer = visibleWheel()
        repeat(2) { reducer.onSelection(0f, 0f, 0.9f, 0f, 0) }

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertEquals(StartWheelAction.MOUSE, letGo.committedAction)
        assertTrue(letGo.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertTrue(letGo.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())

        val released = reducer.onStartUp(900)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertNull(released.committedAction)
    }

    @Test
    fun keepingTheStickPushedAndReleasingStartAlsoRunsIt() {
        val reducer = visibleWheel()
        repeat(2) { reducer.onSelection(-0.9f, 0f, 0f, 0f, 0) }

        val release = reducer.onStartUp(900, leftStickX = -0.9f)

        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, release.committedAction)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        val centered = reducer.onInputSnapshot(0, leftStickX = 0f)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(centered.consumeAllInput)
    }

    @Test
    fun releasingWithNothingHighlightedRunsNothing() {
        val reducer = visibleWheel()

        val release = reducer.onStartUp(900)

        assertTrue(release.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun aDriftBelowTheActivationThresholdSelectsNothing() {
        val reducer = visibleWheel()
        repeat(4) { reducer.onSelection(0.5f, 0f, 0f, 0f, 0) }
        assertNull(reducer.selectedAction())

        val release = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertNull(release.committedAction)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
    }

    @Test
    fun aDirectionHeldAtOpenIsBlockedUntilLetGo() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)

        val shown = reducer.onLongPressTimeout(850, true, leftStickX = 0.9f)
        assertTrue(shown.wheelVisible)
        assertFalse(shown.wheel.armed)

        repeat(3) {
            val held = reducer.onSelection(0.9f, 0f, 0f, 0f, 0)
            assertEquals(-1, held.wheel.slot)
            assertNull(held.wheel.highlighted)
        }

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertTrue(letGo.wheel.armed)
        assertNull(letGo.committedAction)

        repeat(2) { reducer.onSelection(0f, 0f, 0.9f, 0f, 0) }
        assertEquals(StartWheelAction.MOUSE, reducer.onSelection(0f, 0f, 0f, 0f, 0).committedAction)
    }

    @Test
    fun aBlockedDirectionSelectsOnceItClearlyTurns() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true, leftStickX = 0.9f)

        reducer.onSelection(0.7f, -0.7f, 0f, 0f, 0)
        val turned = reducer.onSelection(0.7f, -0.7f, 0f, 0f, 0)

        assertTrue(turned.wheel.armed)
        assertEquals(StartWheelAction.GYRO, turned.wheel.highlighted)
    }

    @Test
    fun aTappedDpadDirectionRunsOnRelease() {
        val reducer = visibleWheel()

        val pressed = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        assertEquals(StartWheelAction.MENU, pressed.wheel.highlighted)
        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)

        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertEquals(StartWheelAction.MENU, released.committedAction)
        assertTrue(released.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())
    }

    @Test
    fun aHeldDpadDirectionCanChangeBeforeItIsReleased() {
        val reducer = visibleWheel()

        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        val changed = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.RIGHT_FLAG)
        assertEquals(StartWheelAction.MOUSE, changed.wheel.highlighted)

        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, released.committedAction)
    }

    @Test
    fun dpadDiagonalsReachTheDiagonalSectors() {
        val reducer = visibleWheel()

        val pressed = reducer.onSelection(
            0f, 0f, 0f, 0f,
            ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG
        )

        assertEquals(5, pressed.wheel.slot)
        assertEquals(StartWheelAction.DISCONNECT, pressed.wheel.highlighted)
    }

    @Test
    fun anUnavailableItemIsAnEmptySectorAndRunsNothing() {
        val reducer = StartGestureReducer(750, isItemAvailable = { it != StartWheelAction.MIC })
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)

        repeat(2) { reducer.onSelection(0.7f, 0.7f, 0f, 0f, 0) }
        val empty = reducer.onSelection(0.7f, 0.7f, 0f, 0f, 0)
        assertEquals(3, empty.wheel.slot)
        assertTrue(empty.wheel.emptySlot)
        assertNull(empty.wheel.highlighted)

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertNull(letGo.committedAction)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
        val release = reducer.onStartUp(900)
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun theMenuRunsThroughItsOwnRequest() {
        val reducer = visibleWheel()
        repeat(2) { reducer.onSelection(0f, -0.9f, 0f, 0f, 0) }

        val request = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertEquals(StartWheelAction.MENU, request.committedAction)
        assertTrue(request.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
        assertNotNull(request.menuOpenRequestId)
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())

        val opened = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(opened.consumeAllInput)
    }

    @Test
    fun aFailedMenuRollsBack() {
        val reducer = visibleWheel()
        repeat(2) { reducer.onSelection(0f, -0.9f, 0f, 0f, 0) }
        val request = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        val failed = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), false)

        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertTrue(failed.consumeAllInput)
        val stale = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertFalse(stale.sendNeutralState)
    }

    @Test
    fun cancelDismissesWithoutRunningAnything() {
        val reducer = visibleWheel()
        repeat(2) { reducer.onSelection(0f, 0.9f, 0f, 0f, 0) }

        val cancel = reducer.onCancelPressed()

        assertTrue(cancel.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(cancel.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())

        val released = reducer.onStartUp(900)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertNull(released.committedAction)
    }

    @Test
    fun resetHidesTheWheelOnce() {
        val reducer = visibleWheel()

        val reset = reducer.reset()

        assertEquals(
            listOf(
                StartGestureReducer.EventAction.CANCEL_LONG_PRESS,
                StartGestureReducer.EventAction.HIDE_WHEEL
            ),
            reset.actions
        )
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun anExternalMenuOpenHidesTheWheelOnce() {
        val reducer = StartGestureReducer(750)

        val opened = reducer.onGameMenuOpenedExternally()

        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertFalse(opened.wheelVisible)

        val dismissed = reducer.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(dismissed.consumeAllInput)
    }

    @Test
    fun anExternalMenuOpenDuringTheWheelDismissesIt() {
        val reducer = visibleWheel()

        val opened = reducer.onGameMenuOpenedExternally()

        assertEquals(1, opened.actions.count { it == StartGestureReducer.EventAction.HIDE_WHEEL })
        assertFalse(opened.wheelVisible)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        // Start is still held, so dismissal waits for its release.
        reducer.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        reducer.onStartUp(900)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
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
    fun theStrongerStickWinsWhenNeitherHasOwnershipYet() {
        val reducer = visibleWheel()

        repeat(2) { reducer.onSelection(0.4f, 0f, 0.9f, 0f, 0) }

        assertEquals(StartWheelAction.MOUSE, reducer.selectedAction())
    }

    @Test
    fun hostStickYConversionPreservesUpAndDown() {
        val up = visibleWheel()
        repeat(2) { up.onSelection(0f, hostStickYToWheelAxis(32766), 0f, 0f, 0) }
        assertEquals(StartWheelAction.MENU, up.selectedAction())

        val down = visibleWheel()
        repeat(2) { down.onSelection(0f, hostStickYToWheelAxis(-32766), 0f, 0f, 0) }
        assertEquals(StartWheelAction.KEYBOARD, down.selectedAction())
    }

    private fun visibleWheel(): StartGestureReducer = StartGestureReducer(750).also { reducer ->
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
    }
}
