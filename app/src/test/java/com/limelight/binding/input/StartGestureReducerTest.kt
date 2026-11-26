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
    fun rightStickSelectionUsesHysteresis() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        val shown = reducer.onLongPressTimeout(850, true)
        assertTrue(shown.wheelVisible)

        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val mouse = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, mouse.wheel.highlighted)

        val withinExitDeadzone = reducer.onSelection(0f, 0f, 0.40f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, withinExitDeadzone.wheel.highlighted)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val center = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        // Let go of the stick: the highlight stays where the push left it.
        assertEquals(StartWheelAction.MOUSE, center.wheel.highlighted)
        assertEquals(1, center.wheel.slot)
        assertTrue(center.actions.isEmpty())
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

        assertEquals(StartWheelAction.MENU, selected.wheel.highlighted)
        assertEquals(StartWheelCategory.SYSTEM, selected.wheel.category)
        assertEquals(0, selected.wheel.slot)
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

        assertEquals(StartWheelAction.MOUSE, changed.wheel.highlighted)
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
        assertEquals(StartWheelAction.MOUSE, second.wheel.highlighted)
    }

    @Test
    fun leftAndRightSticksCanTakeOwnershipAfterCentering() {
        val reducer = visibleWheel()

        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        val left = reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, left.wheel.highlighted)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val centered = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, centered.wheel.highlighted)

        reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        val right = reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        assertEquals(StartWheelAction.KEYBOARD, right.wheel.highlighted)
    }

    @Test
    fun activeAnalogSourceBlocksOtherStickUntilRelease() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)

        reducer.onSelection(0f, -0.9f, 0.4f, 0f, 0)
        val blocked = reducer.onSelection(0f, -0.9f, 0.4f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, blocked.wheel.highlighted)

        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val switched = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MENU, switched.wheel.highlighted)
    }

    @Test
    fun strongerStickWinsWhenNoAnalogSourceIsActive() {
        val reducer = visibleWheel()

        reducer.onSelection(-0.95f, 0f, 0.7f, 0f, 0)
        val selected = reducer.onSelection(-0.95f, 0f, 0.7f, 0f, 0)

        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, selected.wheel.highlighted)
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

        assertNull(reducer.selectedAction())
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

        assertEquals(StartWheelAction.MOUSE, selected.wheel.highlighted)
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

    // ========== Two-ring wheel ==========

    @Test
    fun categoryRingHighlightsTheCategoryShortcut() {
        val reducer = visibleWheel()

        // A held D-pad direction changes the highlight without running anything.
        val performance = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.LEFT_FLAG)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, performance.wheel.highlighted)
        assertEquals(StartWheelCategory.PERFORMANCE, performance.wheel.category)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, performance.wheel.level)
        assertEquals(3, performance.wheel.slot)

        val keyboard = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.DOWN_FLAG)
        assertEquals(StartWheelAction.KEYBOARD, keyboard.wheel.highlighted)
        assertEquals(StartWheelCategory.KEYBOARD, keyboard.wheel.category)
        assertEquals(2, keyboard.wheel.slot)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
    }

    @Test
    fun deeperNeedsAHighlightedCategoryButStillTakesTheInput() {
        val reducer = visibleWheel()

        // Nothing to expand, yet the bumper is the wheel's now and must not reach the game.
        val idle = reducer.onRingDeeper()
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, idle.wheel.level)
        assertTrue(idle.actions.isEmpty())
        assertTrue(idle.wheelVisible)
        assertTrue(idle.sendNeutralState)
        assertTrue(idle.consumeAllInput)

        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val stillSettling = reducer.onRingDeeper()
        assertTrue(stillSettling.actions.isEmpty())
        assertFalse(stillSettling.sendNeutralState)
    }

    @Test
    fun deeperWithoutACategoryKeepsTheWheelAndReleaseRunsNothing() {
        val reducer = visibleWheel()
        reducer.onRingDeeper()

        val release = reducer.onStartUp(900)

        assertTrue(release.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun deeperExpandsTheHighlightedCategoryAndKeepsItsShortcut() {
        val reducer = visibleWheel()
        selectCategory(reducer)

        val deeper = reducer.onRingDeeper()

        assertTrue(deeper.actions.contains(StartGestureReducer.EventAction.RING_CHANGED))
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, deeper.wheel.level)
        assertEquals(StartWheelCategory.INPUT, deeper.wheel.category)
        // The category's own item sits on its direction, so expanding changes nothing Start would run.
        assertEquals(1, deeper.wheel.slot)
        assertEquals(StartWheelAction.MOUSE, deeper.wheel.highlighted)
        assertTrue(deeper.wheel.armed)
        assertTrue(deeper.wheelVisible)
        assertTrue(deeper.consumeAllInput)

        val release = reducer.onStartUp(900)
        assertEquals(StartWheelAction.MOUSE, release.committedAction)
    }

    @Test
    fun heldDirectionKeepsTheShortcutHighlightedInTheOuterRing() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        repeat(5) {
            val held = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
            assertEquals(1, held.wheel.slot)
            assertEquals(StartWheelAction.MOUSE, held.wheel.highlighted)
            assertTrue(held.actions.isEmpty())
        }

        val release = reducer.onStartUp(900, rightStickX = 0.9f)
        assertEquals(StartWheelAction.MOUSE, release.committedAction)
    }

    @Test
    fun turningToAnotherSectorSelectsItsOuterItem() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        reducer.onSelection(0f, 0f, 0.5f, -0.87f, 0)
        val turned = reducer.onSelection(0f, 0f, 0.5f, -0.87f, 0)

        assertTrue(turned.wheel.armed)
        assertEquals(0, turned.wheel.slot)
        assertEquals(StartWheelAction.VIRTUAL_CONTROLLER, turned.wheel.highlighted)
        assertTrue(turned.actions.contains(StartGestureReducer.EventAction.SELECTION_CHANGED))

        val release = reducer.onStartUp(900, rightStickX = 0.5f, rightStickY = -0.87f)
        assertEquals(StartWheelAction.VIRTUAL_CONTROLLER, release.committedAction)
    }

    @Test
    fun recentringRearmsTheStickForTheOuterRing() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        val centered = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertTrue(centered.wheel.armed)

        reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        val again = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, again.wheel.highlighted)
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, again.wheel.level)
    }

    @Test
    fun smallTurnsStayBlockedAfterTheRingFoldsBack() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        // Input's top item, with the stick resting on it.
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        assertFalse(reducer.onRingBack().wheel.armed)

        // 8 degrees off the blocked direction: jitter, not a new sector.
        val jitter = reducer.onSelection(0f, 0f, 0.14f, -0.99f, 0)
        assertFalse(jitter.wheel.armed)
        assertEquals(-1, jitter.wheel.slot)

        // 44 degrees is still inside the blocked sector's 45-degree span.
        val nearBoundary = reducer.onSelection(0f, 0f, 0.70f, -0.72f, 0)
        assertFalse(nearBoundary.wheel.armed)

        // 54 degrees clears the 52-degree margin and lands in the neighbouring sector.
        val pastMargin = reducer.onSelection(0f, 0f, 0.81f, -0.59f, 0)
        assertTrue(pastMargin.wheel.armed)
    }

    @Test
    fun backFoldsTheOuterRingIntoTheCategories() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()
        // Input's top slot; the stick moved off the blocked right-hand sector.
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        val virtualController = reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        assertEquals(StartWheelAction.VIRTUAL_CONTROLLER, virtualController.wheel.highlighted)

        val back = reducer.onRingBack()

        assertTrue(back.actions.contains(StartGestureReducer.EventAction.RING_CHANGED))
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, back.wheel.level)
        assertNull(back.wheel.category)
        assertNull(back.wheel.highlighted)
        assertEquals(-1, back.wheel.slot)
        assertFalse(back.wheel.armed)
        assertTrue(back.wheelVisible)
        assertFalse(back.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))

        // The stick is still held up; moving it down picks another category.
        reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        val keyboard = reducer.onSelection(0f, 0f, 0f, 0.9f, 0)
        assertEquals(StartWheelAction.KEYBOARD, keyboard.wheel.highlighted)
    }

    @Test
    fun backOnTheCategoryRingDismissesWithoutRunning() {
        val reducer = visibleWheel()
        selectCategory(reducer)

        val back = reducer.onRingBack()

        assertTrue(back.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(back.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertFalse(back.wheelVisible)
        // The host was already neutralized, so Start must be swallowed until it is released.
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertTrue(back.consumeAllInput)

        val released = reducer.onStartUp(900)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(released.consumeAllInput)
        assertFalse(released.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun backBeforeAnySelectionHidesTheWheelAndSwallowsInputUntilRelease() {
        val reducer = visibleWheel()

        val back = reducer.onRingBack()

        assertTrue(back.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(back.wheelVisible)
        // The bumper that dismissed the wheel is not the game's; nor is the Start still held.
        assertTrue(back.sendNeutralState)
        assertTrue(back.consumeAllInput)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())

        // Still held: the wheel must not come back and nothing may reach the game.
        val timeout = reducer.onLongPressTimeout(1_000, true)
        assertFalse(timeout.wheelVisible)
        val ignored = reducer.onSelection(0f, 0f, 0.9f, 0f, 0)
        assertEquals(-1, ignored.wheel.slot)
        assertTrue(reducer.isLocalInputCaptureActive())

        val released = reducer.onStartUp(1_200)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(released.consumeAllInput)
        assertFalse(released.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun cancelButtonBeforeAnySelectionTakesTheInputTheSameWay() {
        val reducer = visibleWheel()

        val cancel = reducer.onCancelPressed()

        assertTrue(cancel.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertTrue(cancel.sendNeutralState)
        assertTrue(cancel.consumeAllInput)
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertEquals(StartGestureReducer.State.IDLE, reducer.onStartUp(900).let { reducer.state() })
    }

    @Test
    fun cancelButtonDismissesFromEitherRing() {
        val fromCategories = visibleWheel()
        selectCategory(fromCategories)
        val cancelCategories = fromCategories.onCancelPressed()
        assertTrue(cancelCategories.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(cancelCategories.wheelVisible)
        assertFalse(cancelCategories.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))

        val fromOuterRing = visibleWheel()
        selectCategory(fromOuterRing)
        fromOuterRing.onRingDeeper()
        fromOuterRing.onSelection(0f, 0f, 0f, 0f, 0)
        fromOuterRing.onSelection(0f, 0f, 0f, -0.9f, 0)
        val cancelOuter = fromOuterRing.onCancelPressed()
        assertTrue(cancelOuter.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(cancelOuter.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, fromOuterRing.state())
    }

    @Test
    fun cancelAndBackAreIgnoredOnceTheWheelIsGone() {
        val reducer = StartGestureReducer(750)

        assertTrue(reducer.onCancelPressed().actions.isEmpty())
        assertTrue(reducer.onRingBack().actions.isEmpty())
        assertTrue(reducer.onRingDeeper().actions.isEmpty())
    }

    @Test
    fun emptyOuterSectorRunsNothing() {
        val reducer = visibleWheel()
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        reducer.onRingDeeper()

        // Performance's top sector is empty; the toggle stays lit on its own direction.
        val recentered = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertTrue(recentered.wheel.armed)
        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val empty = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)

        assertEquals(0, empty.wheel.slot)
        assertTrue(empty.wheel.emptySlot)
        assertNull(empty.wheel.highlighted)

        val release = reducer.onStartUp(900, leftStickY = -0.9f)
        assertTrue(release.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun outerRingMenuUsesTheMenuRequestProtocol() {
        val reducer = visibleWheel()
        selectOuterCategory(reducer, 0f, -0.9f)

        val request = reducer.onStartUp(900)

        assertEquals(StartWheelAction.MENU, request.committedAction)
        assertTrue(request.actions.contains(StartGestureReducer.EventAction.OPEN_GAME_MENU))
        assertNotNull(request.menuOpenRequestId)
        assertEquals(StartGestureReducer.State.MENU_PENDING, reducer.state())
    }

    @Test
    fun everyOuterItemIsReachableAndCommitsItself() {
        val vectors = arrayOf(
            0f to -0.9f,   // up
            0.9f to 0f,    // right
            0f to 0.9f,    // down
            -0.9f to 0f    // left
        )
        StartWheelCategory.entries.forEach { category ->
            val categoryVector = vectors[category.ordinal]
            repeat(4) { slot ->
                val expected = StartWheelLayout.ringAction(category, slot)
                val reducer = visibleWheel()
                // Hold the category direction and go deeper, exactly like a player would.
                reducer.onSelection(categoryVector.first, categoryVector.second, 0f, 0f, 0)
                reducer.onSelection(categoryVector.first, categoryVector.second, 0f, 0f, 0)
                val deeper = reducer.onRingDeeper()
                assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, deeper.wheel.level)
                assertEquals(category, deeper.wheel.category)

                val slotVector = vectors[slot]
                val select = { x: Float, y: Float -> reducer.onSelection(x, y, 0f, 0f, 0) }
                // The slot on the category's own direction is the category's own item, already highlighted.
                val direct = select(slotVector.first, slotVector.second)
                if (slot == category.ordinal) {
                    assertEquals(expected, direct.wheel.highlighted)
                }
                select(slotVector.first, slotVector.second)
                val highlighted = select(slotVector.first, slotVector.second).wheel.highlighted

                val release = reducer.onStartUp(900, leftStickX = slotVector.first, leftStickY = slotVector.second)

                assertEquals("$category/$slot highlight", expected, highlighted)
                assertEquals("$category/$slot commit", expected, release.committedAction)
                assertEquals(
                    "$category/$slot commit event",
                    expected != null,
                    release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION)
                )
            }
        }
    }

    @Test
    fun dpadHoldAndDeeperSelectsAnOuterItemThatRunsOnDirectionRelease() {
        val reducer = visibleWheel()
        val category = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        assertEquals(StartWheelAction.MENU, category.wheel.highlighted)

        val deeper = reducer.onRingDeeper()
        assertEquals(StartWheelCategory.SYSTEM, deeper.wheel.category)
        // System's own item is on its direction, so it stays highlighted.
        assertEquals(StartWheelAction.MENU, deeper.wheel.highlighted)
        assertTrue(deeper.wheel.armed)

        // Letting go of the direction that was held across the ring change runs nothing.
        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MENU, released.wheel.highlighted)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())

        val quit = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.LEFT_FLAG)
        assertEquals(StartWheelAction.QUIT_AND_STOP, quit.wheel.highlighted)
        val run = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.QUIT_AND_STOP, run.committedAction)
    }

    @Test
    fun dpadDirectionHeldAcrossAFoldBackStaysBlocked() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        reducer.onRingDeeper()
        // Let go, then pick System's left item and fold the ring back with the direction still held.
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.LEFT_FLAG)
        val back = reducer.onRingBack()
        assertFalse(back.wheel.armed)
        assertNull(back.wheel.highlighted)

        val held = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.LEFT_FLAG)
        assertEquals(-1, held.wheel.slot)
        assertFalse(held.wheel.armed)

        // Letting the held direction go runs nothing, and the next press is a tap again.
        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())
        assertTrue(released.wheel.armed)

        val moved = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.RIGHT_FLAG)
        assertEquals(1, moved.wheel.slot)
        assertEquals(StartWheelAction.MOUSE, moved.wheel.highlighted)
        assertEquals(StartWheelAction.MOUSE, reducer.onSelection(0f, 0f, 0f, 0f, 0).committedAction)
    }

    // ========== The highlight stays when the stick is let go ==========

    @Test
    fun pushingTheStickAndLettingGoIsEnoughToRunTheItemOnStartRelease() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0.9f, 0f, 0f, 0)
        reducer.onSelection(0f, 0.9f, 0f, 0f, 0)

        // The stick springs back before Start is let go.
        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.KEYBOARD, letGo.wheel.highlighted)
        assertTrue(letGo.actions.isEmpty())
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())

        val release = reducer.onStartUp(900)
        assertEquals(StartWheelAction.KEYBOARD, release.committedAction)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun aStickBetweenTheThresholdsLeavesTheHighlightAlone() {
        val reducer = visibleWheel()
        selectCategory(reducer)

        // Drifting off the direction without reaching the next one is not a new choice.
        repeat(4) { index ->
            val drift = reducer.onSelection(0.5f - index * 0.1f, -0.5f, 0f, 0f, 0)
            assertEquals(StartWheelAction.MOUSE, drift.wheel.highlighted)
            assertTrue(drift.actions.isEmpty())
        }
    }

    @Test
    fun anotherPushMovesTheHighlightAndLettingGoKeepsTheNewOne() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onSelection(0f, 0f, 0f, 0f, 0)

        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val turned = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MENU, turned.wheel.highlighted)

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MENU, letGo.wheel.highlighted)
        assertEquals(StartWheelAction.MENU, reducer.onStartUp(900).committedAction)
    }

    @Test
    fun anOuterItemSurvivesLettingGoOfTheStick() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(0f, 0.9f, 0f, 0f, 0)
        reducer.onSelection(0f, 0.9f, 0f, 0f, 0)

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertEquals(StartWheelAction.GYRO, letGo.wheel.highlighted)
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, letGo.wheel.level)
        assertEquals(StartWheelAction.GYRO, reducer.onStartUp(900).committedAction)
    }

    @Test
    fun expandingWithTheStickCenteredHighlightsTheCategoryShortcut() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onSelection(0f, 0f, 0f, 0f, 0)

        val deeper = reducer.onRingDeeper()

        assertEquals(1, deeper.wheel.slot)
        assertEquals(StartWheelAction.MOUSE, deeper.wheel.highlighted)
        assertEquals(StartWheelAction.MOUSE, reducer.onStartUp(900).committedAction)
    }

    @Test
    fun expandingThePerformanceCategoryKeepsItsToggleUnderTheCursor() {
        val reducer = visibleWheel()
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)

        val deeper = reducer.onRingDeeper()

        // Expanding never lands on an empty slot: the category's own toggle stays highlighted.
        assertEquals(3, deeper.wheel.slot)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, deeper.wheel.highlighted)
        assertFalse(deeper.wheel.emptySlot)
        assertTrue(deeper.wheel.armed)

        val release = reducer.onStartUp(900)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, release.committedAction)
    }

    @Test
    fun lettingTheStickGoAfterTheRingFoldsBackOnlyRearmsTheWheel() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(0f, 0f, 0f, -0.9f, 0)
        assertFalse(reducer.onRingBack().wheel.armed)

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertTrue(letGo.wheel.armed)
        assertEquals(-1, letGo.wheel.slot)
        assertNull(letGo.wheel.highlighted)
        assertTrue(letGo.actions.contains(StartGestureReducer.EventAction.SELECTION_CHANGED))
        assertFalse(reducer.onStartUp(900).actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun aNewStartPressDoesNotInheritTheOldHighlight() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        assertEquals(StartWheelAction.MOUSE, reducer.onStartUp(900).committedAction)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())

        reducer.onStartDown(1_000, true)
        val shown = reducer.onLongPressTimeout(1_850, true)
        assertEquals(-1, shown.wheel.slot)
        assertNull(shown.wheel.highlighted)
    }

    // ========== A stick that was already pushed does not choose ==========

    @Test
    fun aStickAlreadyPushedWhenTheWheelAppearsDoesNotChooseByItself() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)

        // The player is strafing right while Start is held long enough for the wheel.
        val shown = reducer.onLongPressTimeout(850, true, leftStickX = 0.9f)
        assertTrue(shown.wheelVisible)
        assertFalse(shown.wheel.armed)

        repeat(5) {
            val held = reducer.onSelection(0.9f, 0f, 0f, 0f, 0)
            assertEquals(-1, held.wheel.slot)
            assertNull(held.wheel.highlighted)
        }

        // Letting go of Start then must not switch mouse emulation.
        val release = reducer.onStartUp(900, leftStickX = 0.9f)
        assertFalse(release.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
    }

    @Test
    fun aPushedStickChoosesOnceItHasBeenBackToTheMiddle() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true, leftStickX = 0.9f)

        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertTrue(letGo.wheel.armed)
        assertEquals(-1, letGo.wheel.slot)

        reducer.onSelection(0.9f, 0f, 0f, 0f, 0)
        val pushedAgain = reducer.onSelection(0.9f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.MOUSE, pushedAgain.wheel.highlighted)
    }

    @Test
    fun aPushedStickChoosesAnotherDirectionWithoutGoingBackFirst() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true, leftStickX = 0.9f)

        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val turned = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        assertTrue(turned.wheel.armed)
        assertEquals(StartWheelAction.MENU, turned.wheel.highlighted)
    }

    @Test
    fun aStickPushedOnTheRightCountsTheSameWay() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        assertFalse(reducer.onLongPressTimeout(850, true, rightStickY = 0.9f).wheel.armed)

        repeat(3) {
            assertNull(reducer.onSelection(0f, 0f, 0f, 0.9f, 0).wheel.highlighted)
        }
        assertNull(reducer.onStartUp(900, rightStickY = 0.9f).committedAction)
    }

    @Test
    fun aDriftingStickBelowTheReleaseThresholdDoesNotBlockTheWheel() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)

        val shown = reducer.onLongPressTimeout(850, true, leftStickX = 0.3f)

        assertTrue(shown.wheel.armed)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, selectCategory(reducer, -0.9f, 0f).let { reducer.selectedAction() })
    }

    @Test
    fun aDpadDirectionAlreadyHeldWhenTheWheelAppearsDoesNotChooseByItself() {
        val reducer = StartGestureReducer(750)
        reducer.onStartDown(100, true)
        val shown = reducer.onLongPressTimeout(850, true, dpadFlags = ControllerPacket.RIGHT_FLAG)
        assertTrue(shown.wheelVisible)
        assertFalse(shown.wheel.armed)

        repeat(3) {
            val held = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.RIGHT_FLAG)
            assertEquals(-1, held.wheel.slot)
            assertNull(held.wheel.highlighted)
        }

        // Letting the held direction go runs nothing and arms the wheel again.
        val letGo = reducer.onSelection(0f, 0f, 0f, 0f, 0)
        assertTrue(letGo.wheel.armed)
        assertEquals(StartGestureReducer.State.WHEEL_VISIBLE, reducer.state())

        val tap = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.RIGHT_FLAG)
        assertEquals(StartWheelAction.MOUSE, tap.wheel.highlighted)
        assertEquals(StartWheelAction.MOUSE, reducer.onSelection(0f, 0f, 0f, 0f, 0).committedAction)
    }

    // ========== Which items the wheel offers ==========

    @Test
    fun anItemWhoseFeatureIsOffIsNotOffered() {
        val reducer = StartGestureReducer(750, isItemAvailable = { it != StartWheelAction.VIRTUAL_CONTROLLER })
        reducer.onStartDown(100, true)
        reducer.onLongPressTimeout(850, true)
        selectCategory(reducer)

        val deeper = reducer.onRingDeeper()
        assertEquals(StartWheelAction.MOUSE, deeper.wheel.highlighted)

        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        val empty = reducer.onSelection(0f, -0.9f, 0f, 0f, 0)

        // The slot reads as empty and releasing Start runs nothing.
        assertEquals(0, empty.wheel.slot)
        assertTrue(empty.wheel.emptySlot)
        assertNull(empty.wheel.highlighted)
        assertFalse(
            reducer.onStartUp(900, leftStickY = -0.9f)
                .actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION)
        )
    }

    @Test
    fun theKeyboardRingCommitsItsThreeKeySlots() {
        val slots = mapOf(0 to StartWheelAction.KEY_1, 3 to StartWheelAction.KEY_3)
        slots.forEach { (slot, expected) ->
            val reducer = visibleWheel()
            reducer.onSelection(0f, 0.9f, 0f, 0f, 0)
            reducer.onSelection(0f, 0.9f, 0f, 0f, 0)
            reducer.onRingDeeper()
            reducer.onSelection(0f, 0f, 0f, 0f, 0)
            val vector = when (slot) {
                0 -> 0f to -0.9f
                else -> -0.9f to 0f
            }
            reducer.onSelection(vector.first, vector.second, 0f, 0f, 0)
            val highlighted = reducer.onSelection(vector.first, vector.second, 0f, 0f, 0).wheel.highlighted
            assertEquals(expected, highlighted)
            assertEquals(expected, reducer.onStartUp(900).committedAction)
        }
    }

    @Test
    fun theInputRingOffersTheMicrophoneInItsFreeSector() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)

        val mic = reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)

        assertEquals(StartWheelAction.MIC, mic.wheel.highlighted)
        assertEquals(StartWheelAction.MIC, reducer.onStartUp(900).committedAction)
    }

    @Test
    fun theSystemRingReachesSleepAndQuitAndKeepsDisconnectOpposite() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        reducer.onSelection(0f, -0.9f, 0f, 0f, 0)
        reducer.onRingDeeper()

        // The category's own direction keeps the menu; its opposite is where ending the session lives.
        assertEquals(StartWheelAction.MENU, reducer.onRingDeeper().let { reducer.selectedAction() })
        reducer.onSelection(0f, 0.9f, 0f, 0f, 0)
        assertEquals(
            StartWheelAction.DISCONNECT,
            reducer.onSelection(0f, 0.9f, 0f, 0f, 0).wheel.highlighted
        )
        reducer.onSelection(0.9f, 0f, 0f, 0f, 0)
        assertEquals(
            StartWheelAction.HOST_SLEEP,
            reducer.onSelection(0.9f, 0f, 0f, 0f, 0).wheel.highlighted
        )
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        assertEquals(
            StartWheelAction.QUIT_AND_STOP,
            reducer.onSelection(-0.9f, 0f, 0f, 0f, 0).wheel.highlighted
        )
    }

    @Test
    fun resetWhileTheOuterRingIsExpandedHidesTheWheelOnce() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        val reset = reducer.reset()

        assertEquals(
            listOf(
                StartGestureReducer.EventAction.CANCEL_LONG_PRESS,
                StartGestureReducer.EventAction.HIDE_WHEEL
            ),
            reset.actions
        )
        assertFalse(reset.wheelVisible)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, reset.wheel.level)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun externalMenuOpenWhileTheOuterRingIsExpandedHidesTheWheelOnce() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        val opened = reducer.onGameMenuOpenedExternally()

        assertEquals(1, opened.actions.count { it == StartGestureReducer.EventAction.HIDE_WHEEL })
        assertEquals(StartGestureReducer.State.MENU_ACTIVE, reducer.state())
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, opened.wheel.level)

        // The stick never left the category, so the capture holds until it does.
        val dismissed = reducer.onGameMenuUnavailable()
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        val centered = reducer.onInputSnapshot(0, leftStickX = 0f, leftStickY = 0f)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
        assertFalse(centered.consumeAllInput)
    }

    @Test
    fun dpadTapOnAnEmptyOuterSectorRunsNothing() {
        val reducer = visibleWheel()
        reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.LEFT_FLAG)
        reducer.onRingDeeper()
        // Letting go of Left recentres the source; Performance's top sector is empty.
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        val empty = reducer.onSelection(0f, 0f, 0f, 0f, ControllerPacket.UP_FLAG)
        assertTrue(empty.wheel.emptySlot)

        val released = reducer.onSelection(0f, 0f, 0f, 0f, 0)

        assertTrue(released.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertFalse(released.actions.contains(StartGestureReducer.EventAction.COMMIT_ACTION))
        assertEquals(StartGestureReducer.State.WAIT_FOR_RELEASE, reducer.state())
        assertEquals(StartGestureReducer.State.IDLE, reducer.onStartUp(900).let { reducer.state() })
    }

    @Test
    fun stickLeftOnTheCategoryRingRunsThePerformanceShortcut() {
        val reducer = visibleWheel()
        reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        val selected = reducer.onSelection(-0.9f, 0f, 0f, 0f, 0)
        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, selected.wheel.highlighted)

        val release = reducer.onStartUp(900)

        assertEquals(StartWheelAction.PERFORMANCE_TOGGLE, release.committedAction)
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun dismissedWheelResetReturnsToIdleWithoutHidingTwice() {
        val reducer = visibleWheel()
        reducer.onRingBack()

        val reset = reducer.reset()

        assertFalse(reset.actions.contains(StartGestureReducer.EventAction.HIDE_WHEEL))
        assertEquals(StartGestureReducer.State.IDLE, reducer.state())
    }

    @Test
    fun nextStartPressStartsOverOnTheCategoryRing() {
        val reducer = visibleWheel()
        selectCategory(reducer)
        reducer.onRingDeeper()

        val release = reducer.onStartUp(900)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, release.wheel.level)
        assertNull(release.wheel.category)

        reducer.onStartDown(2_000, true)
        val shown = reducer.onLongPressTimeout(2_800, true)
        assertTrue(shown.wheelVisible)
        assertEquals(StartWheelSnapshot.LEVEL_CATEGORIES, shown.wheel.level)
        assertEquals(-1, shown.wheel.slot)
        assertTrue(shown.wheel.armed)
    }

    /** Holds the left stick at (x, y) until the category ring highlights that direction, then expands it. */
    private fun selectOuterCategory(reducer: StartGestureReducer, x: Float, y: Float) {
        selectCategory(reducer, x, y)
        reducer.onRingDeeper()
        // The stick still rests on the category's own sector; recentre, then pick the top item.
        reducer.onSelection(0f, 0f, 0f, 0f, 0)
        reducer.onSelection(x, y, 0f, 0f, 0)
        val top = reducer.onSelection(x, y, 0f, 0f, 0)
        assertEquals(0, top.wheel.slot)
        assertEquals(StartWheelSnapshot.LEVEL_ACTIONS, top.wheel.level)
    }

    /** Holds the left stick at (x, y) until the category ring highlights that direction. */
    private fun selectCategory(reducer: StartGestureReducer, x: Float = 0.9f, y: Float = 0f) {
        reducer.onSelection(x, y, 0f, 0f, 0)
        val selected = reducer.onSelection(x, y, 0f, 0f, 0)
        val category = requireNotNull(selected.wheel.category)
        assertEquals(StartWheelLayout.categoryDefault(category), selected.wheel.highlighted)
    }

    private fun visibleWheel(): StartGestureReducer {
        return StartGestureReducer(750).also { reducer ->
            reducer.onStartDown(100, true)
            reducer.onLongPressTimeout(850, true)
        }
    }
}
