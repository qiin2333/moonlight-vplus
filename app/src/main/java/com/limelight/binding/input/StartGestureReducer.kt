package com.limelight.binding.input

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.limelight.R
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

/** What has to be set up on this device before an item is shown. */
enum class StartWheelAvailability {
    ALWAYS,
    GYRO,
    MIC
}

/** One sector of the wheel: what it runs, how it is labelled and when it exists. */
data class StartWheelItem(
    val action: StartWheelAction,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val availability: StartWheelAvailability = StartWheelAvailability.ALWAYS,
    /** True for the one item that ends the session; the overlay draws it as a warning. */
    val danger: Boolean = false
)

/**
 * The wheel's sectors, clockwise from up, 45 degrees each. This list is the only place that defines
 * what the wheel holds: the reducer takes its order and the overlay its labels and icons.
 */
object StartWheelLayout {
    val items = listOf(
        StartWheelItem(
            StartWheelAction.MENU,
            R.string.start_wheel_item_menu,
            R.drawable.phc_list
        ),
        StartWheelItem(
            StartWheelAction.GYRO,
            R.string.start_wheel_item_gyro,
            R.drawable.phc_game_controller,
            StartWheelAvailability.GYRO
        ),
        StartWheelItem(
            StartWheelAction.MOUSE,
            R.string.start_wheel_item_mouse,
            R.drawable.ic_start_wheel_mouse
        ),
        StartWheelItem(
            StartWheelAction.MIC,
            R.string.start_wheel_item_mic,
            R.drawable.ic_mic_gm,
            StartWheelAvailability.MIC
        ),
        StartWheelItem(
            StartWheelAction.KEYBOARD,
            R.string.start_wheel_item_keyboard,
            R.drawable.phc_keyboard
        ),
        StartWheelItem(
            StartWheelAction.DISCONNECT,
            R.string.start_wheel_item_disconnect,
            R.drawable.phc_action_disconnect,
            danger = true
        ),
        StartWheelItem(
            StartWheelAction.PERFORMANCE_TOGGLE,
            R.string.start_wheel_item_perf_show,
            R.drawable.phc_layers
        ),
        StartWheelItem(
            StartWheelAction.ESC,
            R.string.start_wheel_item_esc,
            R.drawable.phc_action_exit
        )
    )

    /** Item in [slot], or null when the wheel shows fewer sectors than its layout. */
    fun action(slot: Int): StartWheelAction? = items.getOrNull(slot)?.action

    fun item(action: StartWheelAction): StartWheelItem? = items.firstOrNull { it.action == action }

    /** Number of sectors the wheel draws, which is the size of its layout. */
    val slotCount: Int get() = items.size
}

/**
 * What the Start hold wheel currently shows. Pure data, safe to hand to the overlay from any thread.
 */
data class StartWheelSnapshot(
    val visible: Boolean = false,
    /** Highlighted sector (0 up, clockwise), or -1 when nothing is highlighted. */
    val slot: Int = -1,
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
 * B closes the wheel without running anything.
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
        val consumeAllInput: Boolean = false,
        val sendNeutralState: Boolean = false,
        val menuOpenRequestId: Long? = null,
        val committedAction: StartWheelAction? = null
    )

    private var state = State.IDLE
    private var startDownTimeMs = 0L
    private var selectedSlot = -1
    /** Where each control pointed when the wheel appeared, or NaN once it is let go or clearly turns. */
    private val heldDirections = FloatArray(InputSource.entries.size) { Float.NaN }
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
        blockHeldDirections(leftStickX, leftStickY, rightStickX, rightStickY, dpadFlags)
        return snapshot(actions = listOf(EventAction.SHOW_WHEEL))
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
        val previousSource = inputSource
        updatePhysicalInput(
            dpadFlags or (lastButtonFlags and ControllerPacket.PLAY_FLAG),
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY
        )
        forgetLetGoDirections(leftStickX, leftStickY, rightStickX, rightStickY, dpadAxis)
        if (previousSource != null && selectedSlot >= 0 && highlightedItem() != null &&
            !isSourceActive(previousSource, leftStickX, leftStickY, rightStickX, rightStickY, dpadAxis)
        ) {
            // The control that chose was let go first: its item runs now, even if another stick is
            // still pushed.
            forgetDirectionCandidate()
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
        if (!axisActive(x, y, exitThreshold)) {
            // Let go: what was pointed at runs now, whichever control was released first. With nothing
            // pointed at, letting go only clears.
            if (selectedSlot >= 0 && highlightedItem() != null) {
                forgetDirectionCandidate()
                return commitSelectedAction()
            }
            return clearHighlight()
        }
        val source = inputSource
        if (source != null && isHeldDirection(source) && !releaseHeldDirection(source, x, y)) {
            // Pushed before the wheel appeared: the player is moving, not choosing. The direction keeps
            // reaching the game and nothing is captured until it is let go or clearly turned.
            return clearHighlight()
        }
        if (inputSource != InputSource.DPAD && !axisActive(x, y, enterThreshold)) {
            // Inside the dead band the wheel does not act on the direction, so the game keeps it.
            return snapshot()
        }
        // From here the direction belongs to the wheel: the host is told to let go before it is used.
        val captureStarted = !wheelInputCaptured
        if (captureStarted) {
            wheelInputCaptured = true
            neutralStateSent = true
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
        if (isWheelDismissPress(buttonFlags)) {
            // B wins over the Start release in the same report: it dismisses instead of committing.
            updatePhysicalInput(
                buttonFlags and ControllerPacket.PLAY_FLAG.inv(),
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY
            )
            return dismissWheel(
                captureForWheelButton(),
                listOf(EventAction.CANCEL_LONG_PRESS, EventAction.HIDE_WHEEL)
            )
        }
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
        if (isWheelDismissPress(buttonFlags)) {
            // B is a wheel button: the host is told to let go before it could reach it.
            updatePhysicalInput(buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY)
            return dismissWheel(captureForWheelButton())
        }
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

    /** True when this report is the press that dismisses the wheel: B went down while it is shown. */
    private fun isWheelDismissPress(buttonFlags: Int): Boolean =
        state == State.WHEEL_VISIBLE &&
            buttonFlags and ControllerPacket.B_FLAG != 0 &&
            lastButtonFlags and ControllerPacket.B_FLAG == 0

    /** Hides the wheel without running anything. Start is still down, so the capture outlives it. */
    private fun dismissWheel(
        captureStarted: Boolean,
        actions: List<EventAction> = listOf(EventAction.HIDE_WHEEL)
    ): Update {
        clearStartGesture()
        state = if (hasPressedInput()) State.WAIT_FOR_RELEASE else State.IDLE
        return snapshot(actions = actions, sendNeutralState = captureStarted)
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
        if (state != State.WHEEL_VISIBLE || selectedSlot < 0) return null
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
        heldDirections.fill(Float.NaN)
        forgetDirectionCandidate()
    }

    /** Nothing is pointed at any more: the highlight goes, and nothing runs. */
    private fun clearHighlight(): Update {
        val hadSlot = selectedSlot >= 0
        forgetDirectionCandidate()
        selectedSlot = -1
        return snapshot(actions = if (hadSlot) listOf(EventAction.SELECTION_CHANGED) else emptyList())
    }

    /**
     * A control that is already pushed when the wheel appears is the player moving, not choosing.
     * Where it points waits until it is let go or clearly turns, whichever other control is let go
     * around it: walking with one stick and steering the camera with the other is how games are played.
     */
    private fun blockHeldDirections(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadFlags: Int
    ) {
        val dpadAxis = dpadToAxis(dpadFlags)
        if (dpadAxis.first != 0f || dpadAxis.second != 0f) {
            heldDirections[InputSource.DPAD.ordinal] = angleOf(dpadAxis.first, dpadAxis.second)
        }
        if (axisActive(leftStickX, leftStickY, enterThreshold)) {
            heldDirections[InputSource.LEFT_STICK.ordinal] = angleOf(leftStickX, leftStickY)
        }
        if (axisActive(rightStickX, rightStickY, enterThreshold)) {
            heldDirections[InputSource.RIGHT_STICK.ordinal] = angleOf(rightStickX, rightStickY)
        }
    }

    /**
     * A control is let go once it is back inside the activation threshold, the same line that made it
     * count as pushed, so a later push is free to choose.
     */
    private fun forgetLetGoDirections(
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadAxis: Pair<Float, Float>
    ) {
        if (!axisActive(leftStickX, leftStickY, enterThreshold)) {
            heldDirections[InputSource.LEFT_STICK.ordinal] = Float.NaN
        }
        if (!axisActive(rightStickX, rightStickY, enterThreshold)) {
            heldDirections[InputSource.RIGHT_STICK.ordinal] = Float.NaN
        }
        if (dpadAxis.first == 0f && dpadAxis.second == 0f) {
            heldDirections[InputSource.DPAD.ordinal] = Float.NaN
        }
    }

    private fun isHeldDirection(source: InputSource): Boolean = !heldDirections[source.ordinal].isNaN()

    /** Ends the hold once [x]/[y] point clearly away from where the control was pushed; true once it has. */
    private fun releaseHeldDirection(source: InputSource, x: Float, y: Float): Boolean {
        val heldAngle = heldDirections[source.ordinal]
        if (heldAngle.isNaN()) return true
        val angle = angleOf(x, y)
        val heldSector = nearestSector(heldAngle)
        if (nearestSector(angle) != heldSector &&
            angleDistance(angle, heldSector * SECTOR_DEGREES) >= BLOCK_RELEASE_DEGREES &&
            angleDistance(angle, heldAngle) >= BLOCK_JITTER_DEGREES
        ) {
            heldDirections[source.ordinal] = Float.NaN
        }
        return heldDirections[source.ordinal].isNaN()
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

    private fun isSourceActive(
        source: InputSource,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        dpadAxis: Pair<Float, Float>
    ): Boolean = when (source) {
        InputSource.RIGHT_STICK -> axisActive(rightStickX, rightStickY, exitThreshold)
        InputSource.LEFT_STICK -> axisActive(leftStickX, leftStickY, exitThreshold)
        InputSource.DPAD -> dpadAxis.first != 0f || dpadAxis.second != 0f
    }

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
                highlighted = highlighted,
                emptySlot = wheelVisible && selectedSlot >= 0 && highlighted == null
            ),
            consumeAllInput = consumeAllInput,
            sendNeutralState = sendNeutralState,
            menuOpenRequestId = menuOpenRequestId,
            committedAction = committedAction
        )
    }

    private companion object {
        val SECTOR_DEGREES = 360f / StartWheelLayout.slotCount

        /** A turn this far past a blocked sector's centre counts as a deliberate move, not wobble. */
        const val BLOCK_RELEASE_DEGREES = 30f

        /** Smaller turns than this are jitter, not a deliberate move. */
        const val BLOCK_JITTER_DEGREES = 12f
    }
}
