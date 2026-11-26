package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system-controller side of the wheel: one ring of eight sectors, and whichever control is let
 * go first runs the item it points at.
 */
class StartGestureReducerTest {
    /** Slot, stick vector and action, spelled out rather than read back from the table under test. */
    private val sectors = listOf(
        Triple(0, 0f to hostStickYToWheelAxis(32766), StartWheelAction.MENU),
        Triple(1, 0.7f to -0.7f, StartWheelAction.GYRO),
        Triple(2, 0.9f to 0f, StartWheelAction.MOUSE),
        Triple(3, 0.7f to 0.7f, StartWheelAction.MIC),
        Triple(4, 0f to hostStickYToWheelAxis(-32766), StartWheelAction.KEYBOARD),
        Triple(5, -0.7f to 0.7f, StartWheelAction.DISCONNECT),
        Triple(6, -0.9f to 0f, StartWheelAction.PERFORMANCE_TOGGLE),
        Triple(7, -0.7f to -0.7f, StartWheelAction.ESC)
    )

    @Test
    fun disabledStartStaysIdle() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, false)

        assertFalse(reducer.onLongPressTimeout(900, false).wheelVisible)
        assertFalse(reducer.onStartUp(901).actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun theWheelOpensWithoutTakingTheInputYet() {
        val shown = StartGestureReducer(750).also { it.onStartDown(100, true) }
            .onLongPressTimeout(850, true)

        assertTrue(shown.wheelVisible)
        assertEquals(-1, shown.wheel.slot)
        assertFalse(shown.consumeAllInput)
        assertFalse(shown.sendNeutralState)
    }

    @Test
    fun everySectorPointsAtItsItem() {
        sectors.forEach { (slot, vector, action) ->
            val selected = wheel().pull(vector.first, vector.second)

            assertEquals("slot $slot", slot, selected.wheel.slot)
            assertEquals("slot $slot", action, selected.wheel.highlighted)
            assertTrue("slot $slot", selected.actions.contains(StartGestureReducer.EventAction.SELECTION_CHANGED))
        }
    }

    @Test
    fun analogSelectionNeedsTwoStableFramesAndKeepsThroughHysteresis() {
        val reducer = wheel()

        assertNull(reducer.pull(0.9f, 0f, frames = 1).wheel.highlighted)
        assertEquals(StartWheelAction.MOUSE, reducer.pull(0.9f, 0f, frames = 1).wheel.highlighted)
        // Inside the release band the highlight stays.
        assertEquals(StartWheelAction.MOUSE, reducer.pull(0.40f, 0f, frames = 1).wheel.highlighted)
    }

    @Test
    fun aDriftBelowTheActivationThresholdSelectsNothing() {
        val reducer = wheel()

        val drifting = reducer.pull(0.5f, 0f, frames = 4)

        assertNull(drifting.wheel.highlighted)
        assertFalse(drifting.consumeAllInput)
        assertNull(reducer.letGo().committedAction)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
    }

    @Test
    fun releasingTheStickRunsTheItemAndClosesTheWheel() {
        val reducer = wheel().also { it.pull(0.9f, 0f) }

        val letGo = reducer.letGo()

        assertEquals(StartWheelAction.MOUSE, letGo.committedAction)
        assertTrue(letGo.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertTrue(letGo.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertNull(reducer.onStartUp(900).committedAction)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun releasingStartWhilePointingRunsTheItem() {
        val reducer = wheel().also { it.pull(-0.9f, 0f) }

        val release = reducer.onStartUp(900, leftStickX = -0.9f)

        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, release.committedAction)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        val centered = reducer.onInputSnapshot(0, leftStickX = 0f)
        assertFalse(centered.consumeAllInput)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun releasingWithNothingHighlightedRunsNothing() {
        val reducer = wheel()

        val release = reducer.onStartUp(900)

        assertTrue(release.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun aDirectionHeldAtOpenIsBlockedAndKeepsReachingTheGame() {
        val reducer = wheel(heldStickX = 0.9f)

        val held = reducer.pull(0.9f, 0f, frames = 3)

        assertEquals(-1, held.wheel.slot)
        assertFalse(held.wheel.armed)
        assertNull(held.wheel.highlighted)
        // The direction is the player moving, so the game keeps it until the wheel uses it.
        assertFalse(held.consumeAllInput)
        assertFalse(held.sendNeutralState)

        val letGo = reducer.letGo()
        assertTrue(letGo.wheel.armed)
        assertNull(letGo.committedAction)
        reducer.pull(0.9f, 0f)
        assertEquals(StartWheelAction.MOUSE, reducer.letGo().committedAction)
    }

    @Test
    fun aBlockedDirectionSelectsOnceItClearlyTurns() {
        val turned = wheel(heldStickX = 0.9f).pull(0.7f, -0.7f)

        assertTrue(turned.wheel.armed)
        assertEquals(StartWheelAction.GYRO, turned.wheel.highlighted)
    }

    @Test
    fun aTappedDpadDirectionRunsOnReleaseAndCanChangeFirst() {
        val changing = wheel()
        assertEquals(
            StartWheelAction.MENU,
            changing.pull(0f, 0f, dpad = ControllerPacket.UP_FLAG, frames = 1).wheel.highlighted
        )
        assertEquals(
            StartWheelAction.MOUSE,
            changing.pull(0f, 0f, dpad = ControllerPacket.RIGHT_FLAG, frames = 1).wheel.highlighted
        )
        assertEquals(StartWheelAction.MOUSE, changing.letGo().committedAction)

        val diagonal = wheel()
        assertEquals(
            StartWheelAction.DISCONNECT,
            diagonal.pull(
                0f, 0f,
                dpad = ControllerPacket.DOWN_FLAG or ControllerPacket.LEFT_FLAG,
                frames = 1
            ).wheel.highlighted
        )
        assertEquals(StartWheelAction.DISCONNECT, diagonal.letGo().committedAction)
    }

    @Test
    fun theControlThatChoseRunsWhenItIsReleasedEvenIfAnotherIsHeld() {
        // The right stick chooses; the left stick keeps walking.
        val sticks = wheel()
        sticks.pull(0.5f, -0.9f, rightX = 0.9f)
        assertEquals(StartWheelAction.MOUSE, sticks.pull(0.5f, -0.9f, rightX = 0f, frames = 1).committedAction)

        // The D-pad chooses while a stick is pushed.
        val dpad = wheel().also { it.pull(0f, 0f, rightX = 0.9f) }
        dpad.pull(0f, 0f, rightX = 0.9f, frames = 1, dpad = ControllerPacket.UP_FLAG)
        assertEquals(
            StartWheelAction.MENU,
            dpad.pull(0f, 0f, rightX = 0.9f, frames = 1).committedAction
        )
    }

    @Test
    fun anUnavailableItemIsAnEmptySector() {
        val reducer = wheel(isItemAvailable = { it != StartWheelAction.MIC })

        val empty = reducer.pull(0.7f, 0.7f)

        assertEquals(3, empty.wheel.slot)
        assertTrue(empty.wheel.emptySlot)
        assertNull(empty.wheel.highlighted)
        assertNull(reducer.letGo().committedAction)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
        assertFalse(reducer.onStartUp(900).actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun theMenuRunsThroughItsOwnRequestAndAFailureRollsBack() {
        val opening = wheel().also { it.pull(0f, -0.9f) }
        val request = opening.letGo()
        assertEquals(StartWheelAction.MENU, request.committedAction)
        assertTrue(request.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
        assertNotNull(request.menuOpenRequestId)
        assertEquals(StartGestureReducer.State.MENU_PENDING, opening.state())
        val opened = opening.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, opening.state())
        assertTrue(opened.consumeAllInput)

        val failing = wheel().also { it.pull(0f, -0.9f) }
        val failedRequest = failing.letGo()
        val failed = failing.onGameMenuOpenResult(requireNotNull(failedRequest.menuOpenRequestId), false)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, failing.state())
        assertTrue(failed.consumeAllInput)
        val stale = failing.onGameMenuOpenResult(requireNotNull(failedRequest.menuOpenRequestId), true)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, failing.state())
        assertTrue(stale.consumeAllInput)
    }

    @Test
    fun cancelAndResetDismissWithoutRunning() {
        val cancelled = wheel().also { it.pull(0f, 0.9f) }
        val cancel = cancelled.onInputSnapshot(ControllerPacket.B_FLAG)
        assertTrue(cancel.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(cancel.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, cancelled.state())
        assertNull(cancelled.onStartUp(900).committedAction)

        val resetting = wheel()
        val reset = resetting.reset()
        assertEquals(
            listOf(
                StartGestureReducer.EventAction.CANCEL_LONG_PRESS,
                StartGestureReducer.EventAction.HIDE_WHEEL
            ),
            reset.actions
        )
        assertEquals(StartGestureReducer.State.IDLE, resetting.state())
    }

    @Test
    fun anExternalMenuHidesTheWheelAndEachSessionSendsItsOwnNeutralState() {
        val idle = StartGestureReducer(750)
        assertTrue(idle.onGameMenuOpenedExternally().sendNeutralState)
        idle.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.IDLE, idle.state())
        assertTrue(idle.onGameMenuOpenedExternally().sendNeutralState)

        val shown = wheel().also { it.pull(0.9f, 0f) }
        val opened = shown.onGameMenuOpenedExternally()
        assertEquals(1, opened.actions.count { it == StartGestureReducer.EventAction.HIDE_WHEEL })
        assertFalse(opened.wheelVisible)
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, shown.state())

        // Start is still held, so dismissal waits for its release.
        shown.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, shown.state())
        shown.onStartUp(900)
        assertEquals(StartGestureReducer.State.IDLE, shown.state())
    }

    @Test
    fun theStrongerStickWinsWhenNeitherHasOwnershipYet() {
        val selected = wheel().pull(0.4f, 0f, rightX = 0.9f)

        assertEquals(StartWheelAction.MOUSE, selected.wheel.highlighted)
        assertTrue(selected.actions.contains(StartGestureReducer.EventAction.SELECTION_CHANGED))
    }

    /** The wheel opened and waiting, with an optional direction already held as it appeared. */
    private fun wheel(
        heldStickX: Float = 0f,
        isItemAvailable: (StartWheelAction) -> Boolean = { true }
    ): StartGestureReducer = StartGestureReducer(750, isItemAvailable = isItemAvailable).also {
        it.onStartDown(100, true)
        it.onLongPressTimeout(850, true, leftStickX = heldStickX)
    }

    /** Holds a direction for [frames] reports; analog selection needs two stable frames. */
    private fun StartGestureReducer.pull(
        leftX: Float,
        leftY: Float,
        rightX: Float = 0f,
        rightY: Float = 0f,
        dpad: Int = 0,
        frames: Int = 2
    ): StartGestureReducer.Update {
        var update = onSelection(leftX, leftY, rightX, rightY, dpad)
        repeat(frames - 1) { update = onSelection(leftX, leftY, rightX, rightY, dpad) }
        return update
    }

    /** Back to the middle: the release that runs the highlighted item. */
    private fun StartGestureReducer.letGo(): StartGestureReducer.Update =
        onSelection(0f, 0f, 0f, 0f, 0)
}
