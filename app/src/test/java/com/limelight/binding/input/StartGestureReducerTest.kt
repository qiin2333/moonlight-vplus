package com.limelight.binding.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGestureReducerTest {
    @Test
    fun disabledStartNeverLeavesIdle() {
        val reducer = StartGestureReducer(750)

        reducer.onStartDown(100, false)
        val timeout = reducer.onLongPressTimeout(900, false, true, true, true)
        val release = reducer.onStartUp(901)

        assertFalse(timeout.wheelVisible)
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun wheelSelectionUsesFixedSourceAndHysteresis() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        val shown = reducer.onLongPressTimeout(850, true, true, true, true)
        assertTrue(shown.wheelVisible)

        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val mouse = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, mouse.selectedAction)

        val withinExitDeadzone = reducer.onSelection(0f, 0f, 0.30f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, withinExitDeadzone.selectedAction)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val center = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.CONTINUE, center.selectedAction)
    }

    @Test
    fun menuRequestIsPendingWithoutCaptureUntilOpenResult() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true, true, true, true)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)

        val request = reducer.onStartUp(851)
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())
        assertFalse(request.consumeAllInput)
        assertFalse(request.sendNeutralState)
        assertTrue(request.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))

        val opened = reducer.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertTrue(opened.consumeAllInput)
        assertTrue(opened.sendNeutralState)
    }

    @Test
    fun menuFailureRollsBackAndStaleResultIsIgnored() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true, true, true, true)
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
}
