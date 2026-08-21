package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverControllerShortcutStateMachineTest {
    @Test
    fun exitComboHasPriorityAndExitsAfterRelease() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val pressed = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            200,
            true
        )

        assertTrue(pressed.consumeAllInput)
        assertTrue(pressed.sendNeutralState)
        assertTrue(pressed.actions.contains(DriverControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS))
        assertFalse(pressed.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertFalse(pressed.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertTrue(machine.isLocalInputCaptureActive())

        val released = machine.onButtonSnapshot(0, 201, true)

        assertTrue(released.consumeAllInput)
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM),
            released.actions
        )
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun hintThenBOpensMenuAndStartsLocalCapture() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val start = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val hint = machine.onLongPressTimeout(850, true)
        val menu = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            851,
            true
        )

        assertEquals(
            listOf(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS),
            start.actions
        )
        assertEquals(listOf(DriverControllerShortcutStateMachine.Action.SHOW_HINT), hint.actions)
        assertTrue(menu.actions.contains(DriverControllerShortcutStateMachine.Action.HIDE_HINT))
        assertTrue(menu.actions.contains(DriverControllerShortcutStateMachine.Action.OPEN_GAME_MENU))
        assertTrue(menu.consumeAllInput)
        assertTrue(menu.sendNeutralState)
        assertTrue(menu.menuButtonChanges.isEmpty())
        assertTrue(machine.isLocalInputCaptureActive())
    }

    @Test
    fun releasingLongHeldStartKeepsMouseToggleBehavior() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        val released = machine.onButtonSnapshot(0, 851, true)

        assertTrue(released.actions.contains(DriverControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS))
        assertTrue(released.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))
        assertFalse(released.consumeAllInput)
    }

    @Test
    fun menuTriggerBIsSwallowedUntilReleased() {
        val machine = openMenu()

        val triggerRelease = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)
        val nextBPress = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            853,
            true
        )
        val nextBRelease = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 854, true)

        assertTrue(triggerRelease.consumeAllInput)
        assertTrue(triggerRelease.menuButtonChanges.isEmpty())
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.ButtonChange(ControllerPacket.B_FLAG, true)),
            nextBPress.menuButtonChanges
        )
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.ButtonChange(ControllerPacket.B_FLAG, false)),
            nextBRelease.menuButtonChanges
        )
    }

    @Test
    fun menuConsumesUnsupportedButtonsAndMotionCaptureRemainsActive() {
        val machine = openMenu()
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)

        val unsupportedButton = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.X_FLAG,
            853,
            true
        )

        assertTrue(unsupportedButton.consumeAllInput)
        assertTrue(unsupportedButton.menuButtonChanges.isEmpty())
        assertTrue(machine.isLocalInputCaptureActive())
    }

    @Test
    fun neutralStateIsRequestedOncePerLocalCapture() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val firstOpen = requestMenuOpen(machine, 100)
        completeMenuOpen(machine, firstOpen)
        val whileOpen = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 852, true)

        assertTrue(firstOpen.sendNeutralState)
        assertFalse(whileOpen.sendNeutralState)

        machine.onGameMenuUnavailable()
        assertTrue(machine.isLocalInputCaptureActive())
        machine.onButtonSnapshot(0, 853, true)
        assertFalse(machine.isLocalInputCaptureActive())

        val secondOpen = requestMenuOpen(machine, 1_000)
        assertTrue(secondOpen.sendNeutralState)
    }

    @Test
    fun disabledStartActionStillAllowsExitButNotLongPressActions() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)

        val start = machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, false)
        val timeout = machine.onLongPressTimeout(850, false)
        val release = machine.onButtonSnapshot(0, 851, false)

        assertFalse(start.actions.contains(DriverControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS))
        assertFalse(timeout.actions.contains(DriverControllerShortcutStateMachine.Action.SHOW_HINT))
        assertFalse(release.actions.contains(DriverControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION))

        val exitPressed = machine.onButtonSnapshot(
            DriverControllerShortcutStateMachine.EXIT_COMBO_FLAGS,
            900,
            false
        )
        val exitReleased = machine.onButtonSnapshot(0, 901, false)

        assertTrue(exitPressed.consumeAllInput)
        assertEquals(
            listOf(DriverControllerShortcutStateMachine.Action.EXIT_STREAM),
            exitReleased.actions
        )
    }

    @Test
    fun buttonHeldWhileMenuOpensGetsPairedDownAndUpEvents() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenuOpen(machine, 100)

        machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG or ControllerPacket.A_FLAG,
            852,
            true
        )
        val opened = completeMenuOpen(machine, request)
        val released = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            853,
            true
        )

        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.A_FLAG,
                    true
                )
            ),
            opened.menuButtonChanges
        )
        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.A_FLAG,
                    false
                )
            ),
            released.menuButtonChanges
        )
    }

    @Test
    fun buttonAlreadyHeldWhenMenuIsRequestedGetsPairedDownAndUpEvents() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, 100, true)
        machine.onLongPressTimeout(100 + TEST_LONG_PRESS_MS, true)

        val request = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG or ControllerPacket.A_FLAG,
            100 + TEST_LONG_PRESS_MS + 1,
            true
        )
        val opened = completeMenuOpen(machine, request)
        val released = machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            100 + TEST_LONG_PRESS_MS + 2,
            true
        )

        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.A_FLAG,
                    true
                )
            ),
            opened.menuButtonChanges
        )
        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.A_FLAG,
                    false
                )
            ),
            released.menuButtonChanges
        )
    }

    @Test
    fun staleMenuOpenResultAfterResetIsIgnored() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        val request = requestMenuOpen(machine, 100)
        val requestId = requireNotNull(request.menuOpenRequestId)

        machine.reset()
        val staleResult = machine.onGameMenuOpenResult(requestId, true)

        assertFalse(machine.isMenuOpenRequestPending(requestId))
        assertFalse(machine.isLocalInputCaptureActive())
        assertFalse(staleResult.consumeAllInput)
        assertTrue(staleResult.menuButtonChanges.isEmpty())
    }

    @Test
    fun externallyOpenedMenuCapturesUsbNavigationUntilDismissed() {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        machine.onButtonSnapshot(ControllerPacket.RIGHT_FLAG, 100, true)

        val opened = machine.onGameMenuOpenedExternally()

        assertTrue(opened.consumeAllInput)
        assertTrue(opened.sendNeutralState)
        assertTrue(machine.isLocalInputCaptureActive())
        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.RIGHT_FLAG,
                    true
                )
            ),
            opened.menuButtonChanges
        )

        machine.onGameMenuUnavailable()
        assertTrue(machine.isLocalInputCaptureActive())
        machine.onButtonSnapshot(0, 101, true)
        assertFalse(machine.isLocalInputCaptureActive())
    }

    @Test
    fun diagonalDriverDpadSnapshotProducesOneDirectionAndSwitchesAfterRelease() {
        val machine = openMenu()

        val diagonal = machine.onButtonSnapshot(
            ControllerPacket.UP_FLAG or ControllerPacket.RIGHT_FLAG,
            200,
            true
        )
        val rightOnly = machine.onButtonSnapshot(ControllerPacket.RIGHT_FLAG, 201, true)
        val released = machine.onButtonSnapshot(0, 202, true)

        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.UP_FLAG,
                    true
                )
            ),
            diagonal.menuButtonChanges
        )
        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.UP_FLAG,
                    false
                ),
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.RIGHT_FLAG,
                    true
                )
            ),
            rightOnly.menuButtonChanges
        )
        assertEquals(
            listOf(
                DriverControllerShortcutStateMachine.ButtonChange(
                    ControllerPacket.RIGHT_FLAG,
                    false
                )
            ),
            released.menuButtonChanges
        )
    }

    private fun openMenu(): DriverControllerShortcutStateMachine {
        val machine = DriverControllerShortcutStateMachine(TEST_LONG_PRESS_MS)
        completeMenuOpen(machine)
        return machine
    }

    private fun completeMenuOpen(
        machine: DriverControllerShortcutStateMachine,
        request: DriverControllerShortcutStateMachine.Update = requestMenuOpen(machine, 100)
    ): DriverControllerShortcutStateMachine.Update {
        return machine.onGameMenuOpenResult(requireNotNull(request.menuOpenRequestId), true)
    }

    private fun requestMenuOpen(
        machine: DriverControllerShortcutStateMachine,
        startTimeMs: Long
    ): DriverControllerShortcutStateMachine.Update {
        machine.onButtonSnapshot(ControllerPacket.PLAY_FLAG, startTimeMs, true)
        machine.onLongPressTimeout(startTimeMs + TEST_LONG_PRESS_MS, true)
        return machine.onButtonSnapshot(
            ControllerPacket.PLAY_FLAG or ControllerPacket.B_FLAG,
            startTimeMs + TEST_LONG_PRESS_MS + 1,
            true
        )
    }

    companion object {
        private const val TEST_LONG_PRESS_MS = 750L
    }
}
