package com.limelight.binding.input

import com.limelight.nvstream.input.ControllerPacket
import kotlin.math.abs
import kotlin.math.atan2

/** Categories of the Start hold wheel. The ordinal doubles as the sector index: 0 up, 1 right, 2 down, 3 left. */
enum class StartWheelCategory {
    SYSTEM,
    INPUT,
    KEYBOARD,
    PERFORMANCE;

    companion object {
        fun at(index: Int): StartWheelCategory? = entries.getOrNull(index)
    }
}

/** Everything the Start hold wheel can run. */
enum class StartWheelAction {
    // Category-ring defaults: releasing Start on a highlighted category runs these.
    MENU,
    MOUSE,
    KEYBOARD,
    PERFORMANCE_TOGGLE,

    // System ring
    HOST_SLEEP,
    DISCONNECT,
    QUIT_AND_STOP,

    // Input ring
    VIRTUAL_CONTROLLER,
    GYRO,
    MIC,

    // Keyboard ring: the user's own quick actions, in their order; the app resolves each id.
    KEY_1,
    KEY_2,
    KEY_3
}

/**
 * Fixed layout of the Start hold wheel.
 *
 * The wheel is two concentric rings around a core. The inner ring holds the four categories; the outer
 * ring holds up to four items of the category that was highlighted when the user went a ring deeper.
 * Releasing Start runs the highlighted item. Sector indices follow [StartWheelCategory] order, and each
 * ring keeps the category's shortcut on its own direction so the same action is never in two places.
 */
object StartWheelLayout {
    private val CATEGORY_DEFAULTS = arrayOf(
        StartWheelAction.MENU,
        StartWheelAction.MOUSE,
        StartWheelAction.KEYBOARD,
        StartWheelAction.PERFORMANCE_TOGGLE
    )

    private val CATEGORY_RINGS = arrayOf(
        // Disconnect sits opposite the menu, furthest from the way in; sleep and quit-and-stop flank it.
        arrayOf(
            StartWheelAction.MENU,
            StartWheelAction.HOST_SLEEP,
            StartWheelAction.DISCONNECT,
            StartWheelAction.QUIT_AND_STOP
        ),
        arrayOf(StartWheelAction.VIRTUAL_CONTROLLER, StartWheelAction.MOUSE, StartWheelAction.GYRO, StartWheelAction.MIC),
        arrayOf(StartWheelAction.KEY_1, StartWheelAction.KEY_2, StartWheelAction.KEYBOARD, StartWheelAction.KEY_3),
        // Performance's shortcut is a toggle, so it is the only item, on the category's own direction.
        arrayOf(null, null, null, StartWheelAction.PERFORMANCE_TOGGLE)
    )

    /** Item that releasing Start runs while [category] is highlighted on the category ring. */
    fun categoryDefault(category: StartWheelCategory): StartWheelAction = CATEGORY_DEFAULTS[category.ordinal]

    /** Item in the outer ring of [category] at [slot], or null when that sector is empty. */
    fun ringAction(category: StartWheelCategory, slot: Int): StartWheelAction? =
        CATEGORY_RINGS[category.ordinal].getOrNull(slot)
}

/**
 * What the Start hold wheel currently shows. Pure data, safe to hand to the overlay from any thread.
 */
data class StartWheelSnapshot(
    val visible: Boolean = false,
    /** [LEVEL_CATEGORIES] while picking a category, [LEVEL_ACTIONS] while its outer ring is expanded. */
    val level: Int = LEVEL_CATEGORIES,
    /** The highlighted category, or the parent category while the outer ring is expanded. */
    val category: StartWheelCategory? = null,
    /** Highlighted sector of the active ring, or -1 when nothing is highlighted. */
    val slot: Int = -1,
    /**
     * False while the stick cannot choose: after the ring folds back, and when the wheel appears over
     * a stick that was already pushed. In both cases the stick still points somewhere that must not
     * turn into a choice by itself; it may once it recenters or clearly turns to another sector.
     */
    val armed: Boolean = true,
    /** Item that releasing Start would run; null when nothing, or an empty sector, is highlighted. */
    val highlighted: StartWheelAction? = null,
    /** True while the highlight sits on a sector that has no item. */
    val emptySlot: Boolean = false
) {
    val outerRingActive: Boolean get() = level == LEVEL_ACTIONS

    companion object {
        const val LEVEL_CATEGORIES = 1
        const val LEVEL_ACTIONS = 2
    }
}

// Moonlight host stick coordinates are positive-up; wheel selection follows Android's positive-down axes.
internal fun hostStickYToWheelAxis(value: Short): Float = -value.toFloat() / 32766f

/**
 * Pure Start gesture state machine shared by Android and USB controller adapters.
 *
 * Holding Start shows the wheel. A stick or the D-pad highlights a sector of the active ring, and the
 * highlight stays where the last push left it when the stick springs back: a push is enough, and
 * releasing Start runs whatever is highlighted. RB expands the highlighted category into the outer
 * ring, where the category's own item sits on the category's direction, so expanding leaves what
 * releasing Start would run unchanged until the stick picks another item. LB folds the outer ring back
 * (or dismisses the wheel from the category ring), B dismisses it.
 *
 * Input is observed only until the wheel is first used, by a selection or by one of its buttons. From
 * then on it is captured locally and the host gets a neutral state, so nothing that steers the wheel,
 * stick deflections and LB, RB or B alike, ever reaches the game. Captured input is swallowed until
 * everything has been released.
 */
internal class StartGestureReducer(
    private val longPressDurationMs: Long = ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS.toLong(),
    private val enterThreshold: Float = 0.65f,
    private val exitThreshold: Float = 0.35f,
    /**
     * Items whose feature is not set up on this device (no on-screen controller, no controller for the
     * gyro, the microphone switched off) are left out of the wheel and behave like empty slots.
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
        RING_CHANGED,
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
    private var level = StartWheelSnapshot.LEVEL_CATEGORIES
    private var category = -1
    private var selectedSlot = -1
    private var armed = true
    private var blockedDirection = -1
    private var blockedAngle = 0f
    private var inputSource: InputSource? = null
    private var directionCandidate = -1
    private var directionCandidateFrames = 0
    /** Whether letting go of the D-pad direction runs the highlight; false for one held across a ring change. */
    private var dpadTapCommits = true
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
        level = StartWheelSnapshot.LEVEL_CATEGORIES
        dpadTapCommits = true
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
            selectedSlot >= 0 && dpadTapCommits
        ) {
            // A tapped D-pad direction runs the highlighted item as soon as the direction is released.
            forgetDirectionCandidate()
            return commitSelectedAction()
        }
        // With the D-pad let go, the next press is a tap again.
        if (dpadAxis.first == 0f && dpadAxis.second == 0f) dpadTapCommits = true
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
            // Let go: the highlight stays where the last push left it, so a stick that springs back
            // costs nothing. All that can change is a stick that was blocked after the ring folded back.
            val wasBlocked = !armed
            releaseBlock()
            forgetDirectionCandidate()
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
            // Off the middle but not far enough to be a direction: the highlight stays.
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

    /**
     * RB: expands the outer ring of the highlighted category. The category's own item sits on the
     * category's direction, so what releasing Start would run stays the same until the stick picks
     * another item. Without a highlighted category it only keeps the wheel from passing this bumper
     * on to the game.
     */
    @Synchronized
    fun onRingDeeper(): Update {
        if (state != State.WHEEL_VISIBLE) return snapshot()
        val captureStarted = captureForWheelButton()
        if (level != StartWheelSnapshot.LEVEL_CATEGORIES || selectedSlot < 0) {
            return snapshot(sendNeutralState = captureStarted)
        }
        category = selectedSlot
        level = StartWheelSnapshot.LEVEL_ACTIONS
        armed = true
        blockedDirection = -1
        forgetDirectionCandidate()
        // A D-pad direction held to pick the category must not run the item it lands on when let go.
        dpadTapCommits = false
        return snapshot(actions = listOf(EventAction.RING_CHANGED), sendNeutralState = captureStarted)
    }

    /**
     * LB: folds the outer ring back into the categories, or dismisses the wheel from the category ring
     * without running anything.
     */
    @Synchronized
    fun onRingBack(): Update {
        if (state != State.WHEEL_VISIBLE) return snapshot()
        val captureStarted = captureForWheelButton()
        if (level == StartWheelSnapshot.LEVEL_ACTIONS) {
            level = StartWheelSnapshot.LEVEL_CATEGORIES
            clearSelectionForFoldBack()
            return snapshot(actions = listOf(EventAction.RING_CHANGED), sendNeutralState = captureStarted)
        }
        return dismissWheel(captureStarted)
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

    /** Item that releasing Start would run right now, or null. */
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
        val action = if (level == StartWheelSnapshot.LEVEL_ACTIONS) {
            StartWheelLayout.ringAction(StartWheelCategory.at(category) ?: return null, selectedSlot)
        } else {
            StartWheelCategory.at(selectedSlot)?.let(StartWheelLayout::categoryDefault)
        }
        return action?.takeIf(isItemAvailable)
    }

    private fun clearStartGesture() {
        startDownTimeMs = 0L
        clearSelection()
        level = StartWheelSnapshot.LEVEL_CATEGORIES
        inputSource = null
        wheelInputCaptured = false
    }

    private fun clearSelection() {
        category = -1
        selectedSlot = -1
        releaseBlock()
        forgetDirectionCandidate()
    }

    /**
     * Empties the highlight after the ring folded back. The stick usually still points where the outer
     * item was, which is another category's direction now, so the sector it rests on stays blocked until
     * the stick clearly moves elsewhere; a centered stick is fine.
     */
    private fun clearSelectionForFoldBack() {
        val (x, y) = activeAxis()
        blockedAngle = angleOf(x, y)
        blockedDirection = if (selectedSlot >= 0) selectedSlot else nearestSector(blockedAngle)
        armed = !axisActive(x, y, exitThreshold)
        if (armed) blockedDirection = -1
        selectedSlot = -1
        forgetDirectionCandidate()
        // A D-pad direction held to pick the outer item must not run a category when let go.
        dpadTapCommits = false
    }

    /** Ends the block once [x]/[y] point away from where the stick rested; returns true once unblocked. */
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
     * Since the highlight stays after the stick springs back, letting it count would run its category
     * on nothing more than a long press of Start. The direction waits until it is let go or clearly
     * turns to another sector.
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

    private fun activeAxis(): Pair<Float, Float> = when (inputSource) {
        InputSource.RIGHT_STICK -> lastRightStickX to lastRightStickY
        InputSource.LEFT_STICK -> lastLeftStickX to lastLeftStickY
        InputSource.DPAD -> dpadToAxis(lastButtonFlags)
        null -> 0f to 0f
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

    /** Chebyshev length of a stick vector, so the four cardinal directions share one activation line. */
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

    /** Sector the stick points at, the current sector while it is still held there, or -1 near the center. */
    private fun sectorForAxis(x: Float, y: Float): Int {
        if (selectedSlot >= 0 && isSelectedDirectionHeld(x, y, selectedSlot)) {
            return selectedSlot
        }
        val magnitude = x * x + y * y
        if (magnitude < exitThreshold * exitThreshold) return -1
        if (maxOf(abs(x), abs(y)) < enterThreshold) return -1
        return if (abs(x) >= abs(y)) {
            if (x >= 0) SECTOR_RIGHT else SECTOR_LEFT
        } else {
            if (y >= 0) SECTOR_DOWN else SECTOR_UP
        }
    }

    private fun isSelectedDirectionHeld(x: Float, y: Float, sector: Int): Boolean = when (sector) {
        SECTOR_RIGHT -> x >= exitThreshold && abs(x) >= abs(y)
        SECTOR_LEFT -> x <= -exitThreshold && abs(x) >= abs(y)
        SECTOR_UP -> y <= -exitThreshold && abs(y) > abs(x)
        SECTOR_DOWN -> y >= exitThreshold && abs(y) > abs(x)
        else -> false
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
                level = level,
                category = if (level == StartWheelSnapshot.LEVEL_ACTIONS) {
                    StartWheelCategory.at(category)
                } else {
                    StartWheelCategory.at(selectedSlot)
                },
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
        const val SECTOR_UP = 0
        const val SECTOR_RIGHT = 1
        const val SECTOR_DOWN = 2
        const val SECTOR_LEFT = 3

        const val SECTOR_DEGREES = 90f

        /** A sector spans 45 degrees either side of its center; 7 more degrees of margin absorb stick wobble. */
        const val BLOCK_RELEASE_DEGREES = 52f

        /** Smaller turns than this are jitter, not a deliberate move to another sector. */
        const val BLOCK_JITTER_DEGREES = 12f
    }
}
