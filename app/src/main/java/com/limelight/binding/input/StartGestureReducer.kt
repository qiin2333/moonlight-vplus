package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import kotlin.math.abs

/** Actions exposed by the Start hold wheel. */
enum class StartWheelAction {
    CONTINUE,
    MENU,
    MOUSE,
    KEYBOARD,
    PERFORMANCE
}

// Moonlight host stick coordinates are positive-up; wheel selection follows Android's positive-down axes.
internal fun hostStickYToWheelAxis(value: Short): Float = -value.toFloat() / 32766f

/** Pure Start gesture state machine shared by Android and USB controller adapters. */
internal class StartGestureReducer(
    private val longPressDurationMs: Long = ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS.toLong(),
    private val enterThreshold: Float = 0.65f,
    private val exitThreshold: Float = 0.35f
) {
    internal enum class State {
        IDLE,
        START_HELD,
        WHEEL_VISIBLE,
        MENU_PENDING,
        MENU_ACTIVE,
        WAIT_FOR_RELEASE
    }

    internal enum class InputSource {
        RIGHT_STICK,
        LEFT_STICK,
        DPAD
    }

    internal enum class EventAction {
        SCHEDULE_LONG_PRESS,
        CANCEL_LONG_PRESS,
        SHOW_WHEEL,
        HIDE_WHEEL,
        SELECTION_CHANGED,
        COMMIT_ACTION,
        OPEN_GAME_MENU,
        EXIT_STREAM
    }

    internal data class Update(
        val actions: List<EventAction> = emptyList(),
        val selectedAction: StartWheelAction = StartWheelAction.CONTINUE,
        val wheelVisible: Boolean = false,
        val consumeAllInput: Boolean = false,
        val sendNeutralState: Boolean = false,
        val menuOpenRequestId: Long? = null,
        val committedAction: StartWheelAction? = null
    )

    private var state = State.IDLE
    private var startDownTimeMs = 0L
    private var selectedAction = StartWheelAction.CONTINUE
    private var inputSource: InputSource? = null
    private var directionCandidate: StartWheelAction? = null
    private var directionCandidateFrames = 0
    private var nextMenuOpenRequestId = 0L
    private var pendingMenuOpenRequestId: Long? = null
    private var pendingMenuSnapshot: PendingSnapshot? = null
    private var lastButtonFlags = 0
    private var lastLeftStickX = 0f
    private var lastLeftStickY = 0f
    private var lastRightStickX = 0f
    private var lastRightStickY = 0f
    private var wheelInputCaptured = false
    private var neutralStateSent = false

    private data class PendingSnapshot(
        val buttonFlags: Int,
        val leftStickX: Float,
        val leftStickY: Float,
        val rightStickX: Float,
        val rightStickY: Float
    )

    @Synchronized
    fun onStartDown(eventTimeMs: Long, startActionEnabled: Boolean): Update {
        if (!startActionEnabled || state != State.IDLE) return snapshot()
        lastButtonFlags = lastButtonFlags or ControllerPacket.PLAY_FLAG
        startDownTimeMs = eventTimeMs
        selectedAction = StartWheelAction.CONTINUE
        inputSource = null
        directionCandidate = null
        directionCandidateFrames = 0
        wheelInputCaptured = false
        neutralStateSent = false
        state = State.START_HELD
        return snapshot(actions = listOf(EventAction.SCHEDULE_LONG_PRESS))
    }

    @Synchronized
    fun onLongPressTimeout(
        eventTimeMs: Long,
        startActionEnabled: Boolean
    ): Update {
        if (!startActionEnabled || state != State.START_HELD || startDownTimeMs == 0L ||
            eventTimeMs - startDownTimeMs < longPressDurationMs
        ) {
            return snapshot()
        }
        inputSource = null
        state = State.WHEEL_VISIBLE
        return snapshot(
            actions = listOf(EventAction.SHOW_WHEEL),
            wheelVisible = true
        )
    }

    /** Updates wheel selection from the active source, preferring explicit D-pad input. */
    @Synchronized
    fun onSelection(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadFlags: Int
    ): Update {
        if (state != State.WHEEL_VISIBLE) return snapshot()
        val previousSource = inputSource
        val dpadAxis = dpadToAxis(dpadFlags)
        val captureStarted = !wheelInputCaptured && (
            dpadAxis.first != 0f || dpadAxis.second != 0f ||
                axisActive(leftStickX, leftStickY, enterThreshold) ||
                axisActive(rightStickX, rightStickY, enterThreshold)
            )
        if (captureStarted) {
            wheelInputCaptured = true
            neutralStateSent = true
        }
        updatePhysicalInput(
            dpadFlags or (lastButtonFlags and ControllerPacket.PLAY_FLAG),
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
        if (previousSource == InputSource.DPAD &&
            dpadAxis.first == 0f && dpadAxis.second == 0f &&
            selectedAction != StartWheelAction.CONTINUE
        ) {
            directionCandidate = null
            directionCandidateFrames = 0
            return commitSelectedAction()
        }
        inputSource = resolveInputSource(
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            dpadAxis
        )
        val (x, y) = when (inputSource) {
            InputSource.RIGHT_STICK -> rightStickX to rightStickY
            InputSource.LEFT_STICK -> leftStickX to leftStickY
            InputSource.DPAD -> dpadAxis
            null -> 0f to 0f
        }
        if (inputSource == InputSource.DPAD && x == 0f && y == 0f) {
            directionCandidate = null
            directionCandidateFrames = 0
            return snapshot(sendNeutralState = captureStarted)
        }
        val candidate = actionForAxis(x, y)
        val digitalTransition = inputSource == InputSource.DPAD
        val next = stabilize(candidate, immediate = digitalTransition)
        if (next == selectedAction) return snapshot(sendNeutralState = captureStarted)
        selectedAction = next
        return snapshot(
            actions = listOf(EventAction.SELECTION_CHANGED),
            sendNeutralState = captureStarted
        )
    }

    /** Records the latest host snapshot while menu creation is pending. */
    @Synchronized
    fun recordPendingSnapshot(
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float
    ) {
        updatePhysicalInput(buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY)
        if (state == State.MENU_PENDING) {
            pendingMenuSnapshot = PendingSnapshot(
                buttonFlags,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
        }
    }

    @Synchronized
    fun onStartUp(
        eventTimeMs: Long,
        buttonFlags: Int = 0,
        leftStickX: Float = 0f,
        leftStickY: Float = 0f,
        rightStickX: Float = 0f,
        rightStickY: Float = 0f
    ): Update {
        updatePhysicalInput(
            buttonFlags and ControllerPacket.PLAY_FLAG.inv(),
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
        val heldDuration = if (startDownTimeMs == 0L) 0L else eventTimeMs - startDownTimeMs
        val actions = mutableListOf<EventAction>(EventAction.CANCEL_LONG_PRESS)
        if (state == State.WAIT_FOR_RELEASE) {
            if (!hasPressedInput()) state = State.IDLE
            return snapshot(actions = actions)
        }
        if (state == State.MENU_PENDING || state == State.MENU_ACTIVE) {
            return snapshot(actions = actions)
        }
        if (state != State.WHEEL_VISIBLE || heldDuration < longPressDurationMs) {
            clearStartGesture()
            state = State.IDLE
            return snapshot(actions = actions)
        }
        actions += EventAction.HIDE_WHEEL
        if (!wheelInputCaptured) {
            clearStartGesture()
            state = State.IDLE
            return snapshot(actions = actions)
        }
        return commitSelectedAction(actions)
    }

    @Synchronized
    fun onGameMenuOpenResult(requestId: Long, opened: Boolean): Update {
        if (state != State.MENU_PENDING || pendingMenuOpenRequestId != requestId) {
            return snapshot()
        }
        val inputStillHeld = hasPressedInput()
        pendingMenuOpenRequestId = null
        pendingMenuSnapshot = null
        val sendNeutral = opened && !neutralStateSent
        if (sendNeutral) neutralStateSent = true
        state = if (opened) State.MENU_ACTIVE else if (inputStillHeld) State.WAIT_FOR_RELEASE else State.IDLE
        return snapshot(
            consumeAllInput = opened || state == State.WAIT_FOR_RELEASE,
            sendNeutralState = sendNeutral,
            menuOpenRequestId = requestId
        )
    }

    @Synchronized
    fun onGameMenuOpenedExternally(): Update {
        val wasWheelVisible = state == State.WHEEL_VISIBLE
        val sendNeutral = !isLocalInputCaptureActive()
        if (sendNeutral) neutralStateSent = true
        clearStartGesture()
        pendingMenuOpenRequestId = null
        pendingMenuSnapshot = null
        state = State.MENU_ACTIVE
        return snapshot(
            actions = buildList {
                add(EventAction.CANCEL_LONG_PRESS)
                if (wasWheelVisible) add(EventAction.HIDE_WHEEL)
            },
            consumeAllInput = true,
            sendNeutralState = sendNeutral
        )
    }

    @Synchronized
    fun onGameMenuUnavailable(): Update {
        if (state == State.MENU_PENDING || state == State.MENU_ACTIVE) {
            state = if (hasPressedInput()) State.WAIT_FOR_RELEASE else State.IDLE
        }
        pendingMenuOpenRequestId = null
        pendingMenuSnapshot = null
        return snapshot(consumeAllInput = state == State.WAIT_FOR_RELEASE)
    }

    @Synchronized
    fun onInputSnapshot(
        buttonFlags: Int,
        leftStickX: Float = lastLeftStickX,
        leftStickY: Float = lastLeftStickY,
        rightStickX: Float = lastRightStickX,
        rightStickY: Float = lastRightStickY
    ): Update {
        updatePhysicalInput(buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY)
        if (state == State.WAIT_FOR_RELEASE && !hasPressedInput()) {
            state = State.IDLE
            return snapshot()
        }
        return snapshot()
    }

    @Synchronized
    fun reset(): Update {
        val hideWheel = state == State.WHEEL_VISIBLE
        clearStartGesture()
        state = State.IDLE
        pendingMenuOpenRequestId = null
        pendingMenuSnapshot = null
        lastButtonFlags = 0
        lastLeftStickX = 0f
        lastLeftStickY = 0f
        lastRightStickX = 0f
        lastRightStickY = 0f
        wheelInputCaptured = false
        neutralStateSent = false
        return snapshot(
            actions = buildList {
                add(EventAction.CANCEL_LONG_PRESS)
                if (hideWheel) add(EventAction.HIDE_WHEEL)
            }
        )
    }

    @Synchronized
    fun state(): State = state

    @Synchronized
    fun selectedAction(): StartWheelAction = selectedAction

    @Synchronized
    fun isStartPressed(): Boolean = lastButtonFlags and ControllerPacket.PLAY_FLAG != 0

    @Synchronized
    fun isLocalInputCaptureActive(): Boolean =
        (state == State.WHEEL_VISIBLE && wheelInputCaptured) || state == State.MENU_PENDING ||
            state == State.MENU_ACTIVE || state == State.WAIT_FOR_RELEASE

    @Synchronized
    fun isMenuOpenRequestPending(requestId: Long): Boolean =
        state == State.MENU_PENDING && pendingMenuOpenRequestId == requestId

    @Synchronized
    fun pendingMenuSnapshot(): List<Float>? = pendingMenuSnapshot?.let {
        listOf(it.leftStickX, it.leftStickY, it.rightStickX, it.rightStickY)
    }

    private fun hasPressedInput(): Boolean =
        lastButtonFlags != 0 || (pendingMenuSnapshot?.buttonFlags ?: 0) != 0 ||
            axisActive(lastLeftStickX, lastLeftStickY, exitThreshold) ||
            axisActive(lastRightStickX, lastRightStickY, exitThreshold)

    private fun clearStartGesture() {
        startDownTimeMs = 0L
        selectedAction = StartWheelAction.CONTINUE
        inputSource = null
        directionCandidate = null
        directionCandidateFrames = 0
        wheelInputCaptured = false
    }

    private fun commitSelectedAction(
        initialActions: List<EventAction> = listOf(
            EventAction.CANCEL_LONG_PRESS,
            EventAction.HIDE_WHEEL
        )
    ): Update {
        val committed = selectedAction
        val actions = initialActions.toMutableList().apply { add(EventAction.COMMIT_ACTION) }
        clearStartGesture()
        if (committed == StartWheelAction.MENU) {
            state = State.MENU_PENDING
            val requestId = ++nextMenuOpenRequestId
            pendingMenuOpenRequestId = requestId
            pendingMenuSnapshot = PendingSnapshot(
                lastButtonFlags,
                lastLeftStickX,
                lastLeftStickY,
                lastRightStickX,
                lastRightStickY
            )
            actions += EventAction.OPEN_GAME_MENU
            return snapshot(
                actions = actions,
                consumeAllInput = true,
                menuOpenRequestId = requestId,
                committedAction = committed
            )
        }

        state = if (hasPressedInput()) State.WAIT_FOR_RELEASE else State.IDLE
        return snapshot(
            actions = actions,
            consumeAllInput = state == State.WAIT_FOR_RELEASE,
            committedAction = committed
        )
    }

    private fun updatePhysicalInput(
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float
    ) {
        lastButtonFlags = buttonFlags
        lastLeftStickX = leftStickX
        lastLeftStickY = leftStickY
        lastRightStickX = rightStickX
        lastRightStickY = rightStickY
    }

    private fun resolveInputSource(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadAxis: Pair<Float, Float>
    ): InputSource? {
        if (dpadAxis.first != 0f || dpadAxis.second != 0f) {
            return InputSource.DPAD
        }

        when (inputSource) {
            InputSource.RIGHT_STICK -> if (axisActive(rightStickX, rightStickY, exitThreshold)) {
                return InputSource.RIGHT_STICK
            }
            InputSource.LEFT_STICK -> if (axisActive(leftStickX, leftStickY, exitThreshold)) {
                return InputSource.LEFT_STICK
            }
            InputSource.DPAD -> if (
                !axisActive(rightStickX, rightStickY, enterThreshold) &&
                !axisActive(leftStickX, leftStickY, enterThreshold)
            ) {
                return InputSource.DPAD
            }
            null -> Unit
        }

        val rightMagnitude = axisMagnitude(rightStickX, rightStickY)
        val leftMagnitude = axisMagnitude(leftStickX, leftStickY)
        return when {
            rightMagnitude < enterThreshold && leftMagnitude < enterThreshold -> null
            rightMagnitude >= leftMagnitude -> InputSource.RIGHT_STICK
            else -> InputSource.LEFT_STICK
        }
    }

    private fun axisActive(x: Float, y: Float, threshold: Float): Boolean =
        axisMagnitude(x, y) >= threshold

    private fun axisMagnitude(x: Float, y: Float): Float = maxOf(abs(x), abs(y))

    private fun stabilize(candidate: StartWheelAction, immediate: Boolean = false): StartWheelAction {
        if (candidate == selectedAction) {
            directionCandidate = null
            directionCandidateFrames = 0
            return selectedAction
        }
        if (immediate) {
            directionCandidate = null
            directionCandidateFrames = 0
            return candidate
        }
        if (directionCandidate != candidate) {
            directionCandidate = candidate
            directionCandidateFrames = 1
            return selectedAction
        }
        directionCandidateFrames++
        return if (directionCandidateFrames >= 2) {
            directionCandidate = null
            directionCandidateFrames = 0
            candidate
        } else {
            selectedAction
        }
    }

    private fun actionForAxis(x: Float, y: Float): StartWheelAction {
        val magnitude = x * x + y * y
        if (selectedAction != StartWheelAction.CONTINUE &&
            isSelectedDirectionHeld(x, y, selectedAction)
        ) {
            return selectedAction
        }
        if (magnitude < exitThreshold * exitThreshold) return StartWheelAction.CONTINUE
        if (maxOf(abs(x), abs(y)) < enterThreshold) return StartWheelAction.CONTINUE
        return if (abs(x) >= abs(y)) {
            if (x >= enterThreshold) StartWheelAction.MOUSE
            else if (x <= -enterThreshold) StartWheelAction.PERFORMANCE
            else StartWheelAction.CONTINUE
        } else {
            if (y <= -enterThreshold) StartWheelAction.MENU
            else if (y >= enterThreshold) StartWheelAction.KEYBOARD
            else StartWheelAction.CONTINUE
        }
    }

    private fun isSelectedDirectionHeld(
        x: Float,
        y: Float,
        action: StartWheelAction
    ): Boolean = when (action) {
        StartWheelAction.MOUSE -> x >= exitThreshold && abs(x) >= abs(y)
        StartWheelAction.PERFORMANCE -> x <= -exitThreshold && abs(x) >= abs(y)
        StartWheelAction.MENU -> y <= -exitThreshold && abs(y) > abs(x)
        StartWheelAction.KEYBOARD -> y >= exitThreshold && abs(y) > abs(x)
        StartWheelAction.CONTINUE -> false
    }

    private fun dpadToAxis(flags: Int): Pair<Float, Float> {
        val x = when {
            flags and ControllerPacket.LEFT_FLAG != 0 -> -1f
            flags and ControllerPacket.RIGHT_FLAG != 0 -> 1f
            else -> 0f
        }
        val y = when {
            flags and ControllerPacket.UP_FLAG != 0 -> -1f
            flags and ControllerPacket.DOWN_FLAG != 0 -> 1f
            else -> 0f
        }
        return x to y
    }

    private fun snapshot(
        actions: List<EventAction> = emptyList(),
        wheelVisible: Boolean = state == State.WHEEL_VISIBLE,
        consumeAllInput: Boolean = isLocalInputCaptureActive(),
        sendNeutralState: Boolean = false,
        menuOpenRequestId: Long? = null,
        committedAction: StartWheelAction? = null
    ): Update = Update(
        actions = actions,
        selectedAction = selectedAction,
        wheelVisible = wheelVisible,
        consumeAllInput = consumeAllInput,
        sendNeutralState = sendNeutralState,
        menuOpenRequestId = menuOpenRequestId,
        committedAction = committedAction
    )
}
