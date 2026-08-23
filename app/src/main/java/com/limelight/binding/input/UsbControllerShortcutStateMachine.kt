package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket

/** USB snapshot adapter for [StartGestureReducer] and its local-input ownership boundary. */
internal class UsbControllerShortcutStateMachine(
    private val longPressDurationMs: Long = ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS.toLong()
) {
    enum class Action {
        SCHEDULE_LONG_PRESS,
        CANCEL_LONG_PRESS,
        SHOW_WHEEL,
        HIDE_WHEEL,
        UPDATE_SELECTION,
        TOGGLE_MOUSE_EMULATION,
        TOGGLE_KEYBOARD,
        TOGGLE_PERFORMANCE,
        OPEN_GAME_MENU,
        EXIT_STREAM
    }

    data class ButtonChange(val buttonFlag: Int, val pressed: Boolean)

    data class Update(
        val actions: List<Action> = emptyList(),
        val menuButtonChanges: List<ButtonChange> = emptyList(),
        val consumeAllInput: Boolean = false,
        val sendNeutralState: Boolean = false,
        val menuOpenRequestId: Long? = null,
        val selectedAction: StartWheelAction = StartWheelAction.CONTINUE,
        val wheelVisible: Boolean = false
    )

    private val reducer = StartGestureReducer(longPressDurationMs)
    private var lastButtonFlags = 0
    private var exitPending = false
    private var hostNeutralStateSent = false
    private var pendingMenuPressedFlags = 0
    private var lastLeftStickX = 0f
    private var lastLeftStickY = 0f
    private var lastRightStickX = 0f
    private var lastRightStickY = 0f

    @Synchronized
    fun onButtonSnapshot(buttonFlags: Int, eventTimeMs: Long, startActionEnabled: Boolean): Update {
        val previousButtonFlags = lastButtonFlags
        val changedButtonFlags = previousButtonFlags xor buttonFlags
        lastButtonFlags = buttonFlags

        if (exitPending) {
            if (buttonFlags == 0) {
                exitPending = false
                return Update(actions = listOf(Action.EXIT_STREAM), consumeAllInput = true)
            }
            return Update(consumeAllInput = true)
        }

        if (!reducer.isLocalInputCaptureActive() && buttonFlags == EXIT_COMBO_FLAGS) {
            val resetUpdate = reducer.reset().toUsbUpdate()
            exitPending = true
            pendingMenuPressedFlags = 0
            return Update(
                actions = resetUpdate.actions,
                consumeAllInput = true,
                sendNeutralState = markHostNeutralStateRequired()
            )
        }

        val reducerState = reducer.state()
        val startPressed = buttonFlags and ControllerPacket.PLAY_FLAG != 0
        val startWasPressed = previousButtonFlags and ControllerPacket.PLAY_FLAG != 0
        if (reducerState == StartGestureReducer.State.WHEEL_VISIBLE) {
            val reducerUpdate = if (!startPressed && startWasPressed) {
                reducer.onStartUp(
                    eventTimeMs,
                    buttonFlags,
                    lastLeftStickX,
                    lastLeftStickY,
                    lastRightStickX,
                    lastRightStickY
                )
            } else {
                reducer.onInputSnapshot(
                    buttonFlags,
                    lastLeftStickX,
                    lastLeftStickY,
                    lastRightStickX,
                    lastRightStickY
                )
            }
            return reducerUpdate.toUsbUpdate()
        }
        if (reducerState == StartGestureReducer.State.MENU_PENDING ||
            reducerState == StartGestureReducer.State.MENU_ACTIVE ||
            reducerState == StartGestureReducer.State.WAIT_FOR_RELEASE
        ) {
            if (reducerState == StartGestureReducer.State.MENU_PENDING) {
                pendingMenuPressedFlags = buttonFlags and MENU_BUTTON_MASK
            }
            val reducerUpdate = reducer.onInputSnapshot(
                buttonFlags,
                lastLeftStickX,
                lastLeftStickY,
                lastRightStickX,
                lastRightStickY
            )
            val menuChanges = if (reducerState == StartGestureReducer.State.MENU_ACTIVE) {
                buildMenuButtonChanges(changedButtonFlags, buttonFlags)
            } else {
                emptyList()
            }
            return reducerUpdate.toUsbUpdate(menuChanges)
        }

        val reducerUpdate = when {
            startPressed && !startWasPressed ->
                reducer.onStartDown(eventTimeMs, startActionEnabled)
            !startPressed && startWasPressed ->
                reducer.onStartUp(eventTimeMs, buttonFlags = buttonFlags)
            else -> reducer.onInputSnapshot(buttonFlags)
        }
        return reducerUpdate.toUsbUpdate()
    }

    @Synchronized
    fun onSelectionAxes(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float
    ): Update {
        lastLeftStickX = leftStickX
        lastLeftStickY = leftStickY
        lastRightStickX = rightStickX
        lastRightStickY = rightStickY
        val update = if (reducer.state() == StartGestureReducer.State.WHEEL_VISIBLE) {
            reducer.onSelection(
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                lastButtonFlags
            )
        } else {
            reducer.onInputSnapshot(
                lastButtonFlags,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
        }
        return update.toUsbUpdate()
    }

    @Synchronized
    fun recordPendingSnapshot(
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float
    ) {
        lastButtonFlags = buttonFlags
        pendingMenuPressedFlags = buttonFlags and MENU_BUTTON_MASK
        reducer.recordPendingSnapshot(
            buttonFlags,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
    }

    @Synchronized
    fun onLongPressTimeout(eventTimeMs: Long, startActionEnabled: Boolean): Update {
        return reducer.onLongPressTimeout(
            eventTimeMs,
            startActionEnabled
        ).toUsbUpdate()
    }

    @Synchronized
    fun onGameMenuOpenResult(requestId: Long, opened: Boolean): Update {
        if (!reducer.isMenuOpenRequestPending(requestId)) {
            return Update(consumeAllInput = isLocalInputCaptureActive())
        }
        val update = reducer.onGameMenuOpenResult(requestId, opened)
        val replayChanges = if (opened) {
            buildInitialMenuButtonChanges(pendingMenuPressedFlags)
        } else {
            emptyList()
        }
        pendingMenuPressedFlags = 0
        return update.toUsbUpdate(replayChanges)
    }

    @Synchronized
    fun onGameMenuOpenedExternally(): Update {
        val update = reducer.onGameMenuOpenedExternally()
        val heldMenuButtons = lastButtonFlags and MENU_BUTTON_MASK
        return update.toUsbUpdate(buildInitialMenuButtonChanges(heldMenuButtons))
    }

    @Synchronized
    fun onGameMenuUnavailable(): Update {
        val update = reducer.onGameMenuUnavailable()
        pendingMenuPressedFlags = 0
        return update.toUsbUpdate()
    }

    @Synchronized
    fun isMenuOpenRequestPending(requestId: Long): Boolean =
        reducer.isMenuOpenRequestPending(requestId)

    @Synchronized
    fun isMenuActive(): Boolean = reducer.state() == StartGestureReducer.State.MENU_ACTIVE

    @Synchronized
    fun isLocalInputCaptureActive(): Boolean =
        exitPending || reducer.isLocalInputCaptureActive()

    @Synchronized
    fun needsAxisUpdates(): Boolean =
        reducer.state() == StartGestureReducer.State.WHEEL_VISIBLE || isLocalInputCaptureActive()

    @Synchronized
    fun isStartPressed(): Boolean = lastButtonFlags and ControllerPacket.PLAY_FLAG != 0

    @Synchronized
    fun reset(): Update {
        val update = reducer.reset()
        lastButtonFlags = 0
        exitPending = false
        pendingMenuPressedFlags = 0
        hostNeutralStateSent = false
        lastLeftStickX = 0f
        lastLeftStickY = 0f
        lastRightStickX = 0f
        lastRightStickY = 0f
        return update.toUsbUpdate()
    }

    private fun markHostNeutralStateRequired(): Boolean {
        if (hostNeutralStateSent) return false
        hostNeutralStateSent = true
        return true
    }

    private fun buildInitialMenuButtonChanges(currentFlags: Int): List<ButtonChange> = buildList {
        val direction = canonicalMenuDirection(currentFlags)
        if (direction != 0) add(ButtonChange(direction, pressed = true))
        for (flag in MENU_ACTION_BUTTON_FLAGS) {
            if (currentFlags and flag != 0) add(ButtonChange(flag, pressed = true))
        }
    }

    private fun buildMenuButtonChanges(changedFlags: Int, currentFlags: Int): List<ButtonChange> {
        if (changedFlags == 0) return emptyList()
        return buildList {
            val previousFlags = currentFlags xor changedFlags
            val previousDirection = canonicalMenuDirection(previousFlags)
            val currentDirection = canonicalMenuDirection(currentFlags)
            if (previousDirection != currentDirection) {
                if (previousDirection != 0) add(ButtonChange(previousDirection, pressed = false))
                if (currentDirection != 0) add(ButtonChange(currentDirection, pressed = true))
            }
            for (flag in MENU_ACTION_BUTTON_FLAGS) {
                if (changedFlags and flag != 0) {
                    add(ButtonChange(flag, currentFlags and flag != 0))
                }
            }
        }
    }

    private fun canonicalMenuDirection(buttonFlags: Int): Int =
        MENU_DIRECTION_FLAGS.firstOrNull { buttonFlags and it != 0 } ?: 0

    private fun StartGestureReducer.Update.toUsbUpdate(
        menuButtonChanges: List<ButtonChange> = emptyList()
    ): Update {
        val mappedActions = actions.mapNotNull { action ->
            when (action) {
                StartGestureReducer.EventAction.SCHEDULE_LONG_PRESS -> Action.SCHEDULE_LONG_PRESS
                StartGestureReducer.EventAction.CANCEL_LONG_PRESS -> Action.CANCEL_LONG_PRESS
                StartGestureReducer.EventAction.SHOW_WHEEL -> Action.SHOW_WHEEL
                StartGestureReducer.EventAction.HIDE_WHEEL -> Action.HIDE_WHEEL
                StartGestureReducer.EventAction.OPEN_GAME_MENU -> Action.OPEN_GAME_MENU
                StartGestureReducer.EventAction.COMMIT_ACTION -> when (committedAction) {
                    StartWheelAction.MOUSE -> Action.TOGGLE_MOUSE_EMULATION
                    StartWheelAction.KEYBOARD -> Action.TOGGLE_KEYBOARD
                    StartWheelAction.PERFORMANCE -> Action.TOGGLE_PERFORMANCE
                    else -> null
                }
                StartGestureReducer.EventAction.SELECTION_CHANGED -> Action.UPDATE_SELECTION
                StartGestureReducer.EventAction.EXIT_STREAM -> Action.EXIT_STREAM
            }
        }
        return Update(
            actions = mappedActions,
            menuButtonChanges = menuButtonChanges,
            consumeAllInput = consumeAllInput,
            sendNeutralState = sendNeutralState,
            menuOpenRequestId = menuOpenRequestId,
            selectedAction = selectedAction,
            wheelVisible = wheelVisible
        )
    }

    companion object {
        val EXIT_COMBO_FLAGS: Int = ControllerPacket.PLAY_FLAG or ControllerPacket.BACK_FLAG or
            ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG

        private val MENU_DIRECTION_FLAGS = intArrayOf(
            ControllerPacket.UP_FLAG,
            ControllerPacket.DOWN_FLAG,
            ControllerPacket.LEFT_FLAG,
            ControllerPacket.RIGHT_FLAG
        )
        private val MENU_ACTION_BUTTON_FLAGS = intArrayOf(
            ControllerPacket.A_FLAG,
            ControllerPacket.B_FLAG
        )
        private val MENU_BUTTON_MASK =
            (MENU_DIRECTION_FLAGS + MENU_ACTION_BUTTON_FLAGS).fold(0) { mask, flag -> mask or flag }
    }
}
