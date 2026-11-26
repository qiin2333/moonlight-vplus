package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Everything the Start hold wheel can run, in the clockwise order of its eight sectors from up.
 *
 * The four directions the single-level wheel has always used keep their actions: menu up, mouse
 * right, keyboard down, performance left. The diagonals add gyro, microphone, disconnect and Esc.
 */
enum class StartWheelAction {
    MENU,
    GYRO,
    MOUSE,
    MIC,
    KEYBOARD,
    DISCONNECT,
    PERFORMANCE_TOGGLE,
    ESC
}

/** The wheel's fixed eight sectors, clockwise from up, 45 degrees each. */
object StartWheelLayout {
    private val SLOT_ACTIONS = arrayOf(
        StartWheelAction.MENU,
        StartWheelAction.GYRO,
        StartWheelAction.MOUSE,
        StartWheelAction.MIC,
        StartWheelAction.KEYBOARD,
        StartWheelAction.DISCONNECT,
        StartWheelAction.PERFORMANCE_TOGGLE,
        StartWheelAction.ESC
    )

    val slotCount: Int get() = SLOT_ACTIONS.size

    /** Item in [slot], or null when the wheel shows fewer sectors than its layout. */
    fun action(slot: Int): StartWheelAction? = SLOT_ACTIONS.getOrNull(slot)
}

/**
 * What the Start hold wheel currently shows. Pure data, safe to hand to the overlay from any thread.
 */
data class StartWheelSnapshot(
    val visible: Boolean = false,
    /** Highlighted sector (0 up, clockwise), or -1 when nothing is highlighted. */
    val slot: Int = -1,
    /**
     * False while a direction that was already held when the wheel appeared keeps pointing somewhere;
     * that direction is the player moving, not choosing, until it is let go or clearly turns.
     */
    val armed: Boolean = true,
    /** Item that releasing would run; null when nothing, or a sector without an available item, is highlighted. */
    val highlighted: StartWheelAction? = null,
    /** True while the highlight sits on a sector with no item, or one whose feature is off. */
    val emptySlot: Boolean = false
)

// Moonlight host stick coordinates are positive-up; wheel selection follows Android's positive-down axes.
internal fun hostStickYToWheelAxis(value: Short): Float = -value.toFloat() / 32766f

/**
 * Pure Start gesture state machine shared by Android and USB controller adapters.
 *
 * Holding Start shows a single ring of eight sectors. A stick or the D-pad highlights the sector it
 * points at, and the highlight follows it: back in the middle there is no highlight, and releasing
 * there runs nothing. Whichever control is let go first runs what is highlighted, so a player can
 * push towards an item and simply let the stick spring back, or keep holding it and release Start.
 *
 * Start itself keeps reaching the game until the wheel is first steered: games that use a long press
 * of Start for their own menus are why the gesture exists, so the first push is what takes the input
 * away from the host. From then on input is captured locally until everything has been released.
 */
internal class StartGestureReducer(
    private val longPressDurationMs: Long = ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS.toLong(),
    private val enterThreshold: Float = 0.65f,
    private val exitThreshold: Float = 0.35f,
    /**
     * Items whose feature is not set up on this device (no controller for the gyro, the microphone
     * switched off) are left out of the wheel and behave like empty sectors.
     */
    private val isItemAvailable: (StartWheelAction) -> Boolean = { true }
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
        val wheel: StartWheelSnapshot = StartWheelSnapshot(),
        val wheelVisible: Boolean = false,
        val consumeAllInput: Boolean = false,
        val sendNeutralState: Boolean = false,
        val menuOpenRequestId: Long? = null,
        val committedAction: StartWheelAction? = null
    )

    private var state = State.IDLE
    private var startDownTimeMs = 0L
    private var selectedSlot = -1
    private var armed = true
    private var blockedDirection = -1
    private var blockedAngle = 0f
    private var inputSource: InputSource? = null
    private var directionCandidate = -1
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
        clearSelection()
        wheelInputCaptured = false
        neutralStateSent = false
        state = State.START_HELD
        return snapshot(actions = listOf(EventAction.SCHEDULE_LONG_PRESS))
    }

    /**
     * The wheel appears once Start has been held long enough. The sticks and the D-pad are passed as
     * they are at that moment, so one that is already pushed can be told from a push made to choose.
     */
    @Synchronized
    fun onLongPressTimeout(
        eventTimeMs: Long,
        startActionEnabled: Boolean,
        leftStickX: Float = 0f,
        leftStickY: Float = 0f,
        rightStickX: Float = 0f,
        rightStickY: Float = 0f,
        dpadFlags: Int = 0
    ): Update {
        if (!startActionEnabled || state != State.START_HELD || startDownTimeMs == 0L ||
            eventTimeMs - startDownTimeMs < longPressDurationMs
        ) {
            return snapshot()
        }
        inputSource = null
        state = State.WHEEL_VISIBLE
        updatePhysicalInput(lastButtonFlags, leftStickX, leftStickY, rightStickX, rightStickY)
        blockPushedDirection(leftStickX, leftStickY, rightStickX, rightStickY, dpadFlags)
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
        if (!axisActive(x, y, exitThreshold)) {
            // Let go: what was pointed at runs now, whichever control was released first. With nothing
            // pointed at, letting go only clears (and a direction blocked at open is released here).
            if (selectedSlot >= 0 && highlightedItem() != null) {
                forgetDirectionCandidate()
                return commitSelectedAction()
            }
            val wasBlocked = !armed
            releaseBlock()
            forgetDirectionCandidate()
            selectedSlot = -1
            return snapshot(
                actions = if (wasBlocked) listOf(EventAction.SELECTION_CHANGED) else emptyList(),
                sendNeutralState = captureStarted
            )
        }
        if (!armed && !releaseBlock(x, y)) {
            return snapshot(sendNeutralState = captureStarted)
        }
        val candidate = sectorForAxis(x, y)
        if (candidate < 0) {
            // Off the middle but not far enough to be a direction: nothing is highlighted.
            return snapshot(sendNeutralState = captureStarted)
        }
        val digitalTransition = inputSource == InputSource.DPAD
        val next = stabilize(candidate, immediate = digitalTransition)
        if (next == selectedSlot) return snapshot(sendNeutralState = captureStarted)
        selectedSlot = next
        return snapshot(
            actions = listOf(EventAction.SELECTION_CHANGED),
            sendNeutralState = captureStarted
        )
    }

    /** B: dismisses the wheel without running anything. */
    @Synchronized
    fun onCancelPressed(): Update =
        if (state == State.WHEEL_VISIBLE) dismissWheel(captureForWheelButton()) else snapshot()

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
        if (!wheelInputCaptured || highlightedItem() == null) {
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

    /** Item that releasing would run right now, or null. */
    @Synchronized
    fun selectedAction(): StartWheelAction? = highlightedItem()

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

    /**
     * A wheel button counts as using the wheel. The host has been getting Start and the stick so far,
     * so it is told to let go before the button that steers the wheel could reach it.
     */
    private fun captureForWheelButton(): Boolean {
        if (wheelInputCaptured) return false
        wheelInputCaptured = true
        neutralStateSent = true
        return true
    }

    /** Hides the wheel without running anything. Start is still down, so the capture outlives it. */
    private fun dismissWheel(captureStarted: Boolean): Update {
        clearStartGesture()
        state = if (hasPressedInput()) State.WAIT_FOR_RELEASE else State.IDLE
        return snapshot(actions = listOf(EventAction.HIDE_WHEEL), sendNeutralState = captureStarted)
    }

    private fun commitSelectedAction(
        initialActions: List<EventAction> = listOf(
            EventAction.CANCEL_LONG_PRESS,
            EventAction.HIDE_WHEEL
        )
    ): Update {
        val committed = highlightedItem()
        val actions = initialActions.toMutableList()
        if (committed != null) actions += EventAction.COMMIT_ACTION
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

    private fun highlightedItem(): StartWheelAction? {
        if (state != State.WHEEL_VISIBLE || !armed || selectedSlot < 0) return null
        return StartWheelLayout.action(selectedSlot)?.takeIf(isItemAvailable)
    }

    private fun clearStartGesture() {
        startDownTimeMs = 0L
        clearSelection()
        inputSource = null
        wheelInputCaptured = false
    }

    private fun clearSelection() {
        selectedSlot = -1
        releaseBlock()
        forgetDirectionCandidate()
    }

    /** Ends the block once [x]/[y] point away from where the direction was held; returns true once unblocked. */
    private fun releaseBlock(x: Float, y: Float): Boolean {
        if (!armed) {
            val angle = angleOf(x, y)
            val blockedSectorAngle = blockedDirection * SECTOR_DEGREES
            if (nearestSector(angle) != blockedDirection &&
                angleDistance(angle, blockedSectorAngle) >= BLOCK_RELEASE_DEGREES &&
                angleDistance(angle, blockedAngle) >= BLOCK_JITTER_DEGREES
            ) {
                releaseBlock()
            }
        }
        return armed
    }

    /**
     * A direction that is already held when the wheel appears is the player moving, not choosing.
     * The sector it points at waits until the direction is let go or clearly turns.
     */
    private fun blockPushedDirection(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadFlags: Int
    ) {
        val dpadAxis = dpadToAxis(dpadFlags)
        val leftMagnitude = axisMagnitude(leftStickX, leftStickY)
        val rightMagnitude = axisMagnitude(rightStickX, rightStickY)
        val (x, y) = when {
            dpadAxis.first != 0f || dpadAxis.second != 0f -> dpadAxis
            leftMagnitude < exitThreshold && rightMagnitude < exitThreshold -> return
            else -> if (rightMagnitude >= leftMagnitude) rightStickX to rightStickY else leftStickX to leftStickY
        }
        blockedAngle = angleOf(x, y)
        blockedDirection = nearestSector(blockedAngle)
        armed = false
    }

    private fun releaseBlock() {
        armed = true
        blockedDirection = -1
    }

    private fun forgetDirectionCandidate() {
        directionCandidate = -1
        directionCandidateFrames = 0
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

    /** Chebyshev length of a stick vector, so every direction shares one activation line. */
    private fun axisMagnitude(x: Float, y: Float): Float = maxOf(abs(x), abs(y))

    /** Degrees clockwise from up: 0 up, 90 right, 180 down, 270 left. */
    private fun angleOf(x: Float, y: Float): Float =
        (Math.toDegrees(atan2(x.toDouble(), -y.toDouble())) + 360.0).toFloat() % 360f

    private fun angleDistance(first: Float, second: Float): Float =
        abs(((first - second + 540f) % 360f) - 180f)

    private fun nearestSector(angle: Float): Int = ((angle + SECTOR_DEGREES / 2f) % 360f / SECTOR_DEGREES).toInt()

    private fun stabilize(candidate: Int, immediate: Boolean = false): Int {
        if (candidate == selectedSlot) {
            forgetDirectionCandidate()
            return selectedSlot
        }
        if (immediate) {
            forgetDirectionCandidate()
            return candidate
        }
        if (directionCandidate != candidate) {
            directionCandidate = candidate
            directionCandidateFrames = 1
            return selectedSlot
        }
        directionCandidateFrames++
        return if (directionCandidateFrames >= 2) {
            forgetDirectionCandidate()
            candidate
        } else {
            selectedSlot
        }
    }

    /** Sector the direction points at, or -1 while it is too close to the middle to mean one. */
    private fun sectorForAxis(x: Float, y: Float): Int {
        if (maxOf(abs(x), abs(y)) < enterThreshold) return -1
        return nearestSector(angleOf(x, y))
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
    ): Update {
        val highlighted = highlightedItem()
        return Update(
            actions = actions,
            wheel = StartWheelSnapshot(
                visible = wheelVisible,
                slot = selectedSlot,
                armed = armed,
                highlighted = highlighted,
                emptySlot = wheelVisible && armed && selectedSlot >= 0 && highlighted == null
            ),
            wheelVisible = wheelVisible,
            consumeAllInput = consumeAllInput,
            sendNeutralState = sendNeutralState,
            menuOpenRequestId = menuOpenRequestId,
            committedAction = committedAction
        )
    }

    private companion object {
        const val SECTOR_DEGREES = 45f

        /** A turn this far past a blocked sector's centre counts as a deliberate move, not wobble. */
        const val BLOCK_RELEASE_DEGREES = 30f

        /** Smaller turns than this are jitter, not a deliberate move. */
        const val BLOCK_JITTER_DEGREES = 12f
    }
}
