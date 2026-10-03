package com.limelight.binding.input.touchpad

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration

/** Device identity survives OEM source rewriting; hover and real buttons use normal mouse input. */
internal class CompatibilityTouchpadHandler @JvmOverloads constructor(
    private val context: Context,
    private val send: (CompatibilityTouchpadGesture.Action, Int) -> Unit,
    private val acceptsDevice: (InputDevice?) -> Boolean = { TouchpadCompatibilityDevices.contains(context, it) },
) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val gesture = CompatibilityTouchpadGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var deviceId = -1
    private var primaryDown = false
    private var rawButtonPointerId = MotionEvent.INVALID_POINTER_ID

    init { inputManager.registerInputDeviceListener(this, null) }

    fun owns(event: MotionEvent): Boolean = deviceId == event.deviceId && deviceId != -1

    @SuppressLint("InlinedApi") // Native FLAG_CANCELED predates its public Java constant.
    fun movesPointer(event: MotionEvent): Boolean =
        event.actionMasked != MotionEvent.ACTION_CANCEL && event.flags and MotionEvent.FLAG_CANCELED == 0 &&
            (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || event.buttonState != 0 ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || (owns(event) && primaryDown) ||
                ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) &&
                    isMousePress(event)))

    private fun isMousePress(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.pointerCount == 1 &&
            event.classification == MotionEvent.CLASSIFICATION_NONE && event.flags and FLAG_NO_FOCUS_CHANGE == 0

    fun handle(event: MotionEvent): Boolean {
        // Some Android builds rewrite a touchpad's taps, freeform DOWN and final UP as
        // SOURCE_TOUCHSCREEN. The opted-in physical device, not that per-frame source,
        // determines whether this is the touchpad (real touchscreens cannot be selected).
        if (!acceptsDevice(event.device)) {
            if (owns(event)) cancel()
            return false
        }
        if (event.flags and MotionEvent.FLAG_CANCELED != 0 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // A ViewGroup cancellation can carry a new local press's button bits.
            // Finish our gesture before considering any normal mouse button input.
            val handled = owns(event) && rawButtonPointerId == MotionEvent.INVALID_POINTER_ID
            if (owns(event)) cancel()
            return handled
        }
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD) &&
            (event.buttonState != 0 || (owns(event) && rawButtonPointerId != MotionEvent.INVALID_POINTER_ID))) {
            // Button bits still use the normal mouse path, but raw X/Y describe
            // contact movement. Keep sending those deltas while a button is held.
            if (!owns(event) || rawButtonPointerId == MotionEvent.INVALID_POINTER_ID) {
                cancel()
                deviceId = event.deviceId
                rawButtonPointerId = event.getPointerId(0)
                gesture.down(points(event).take(1), event.eventTime, singleContactScroll = false)
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                for (history in 0 until event.historySize) sendRawButtonMovement(points(event, history), event.metaState)
                sendRawButtonMovement(points(event), event.metaState)
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                event.getPointerId(event.actionIndex) == rawButtonPointerId) {
                val next = points(event).first { it.id != rawButtonPointerId }
                rawButtonPointerId = next.id
                // A new contact has its own origin; lifting the old one must not jump.
                gesture.down(listOf(next), event.eventTime, singleContactScroll = false)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) cancel()
            return false
        }
        // Android may synthesize right clicks and physical presses with a FINGER tool.
        // Let the normal button-state diff handle the entire press/release sequence.
        if (event.buttonState != 0 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (owns(event)) cancel()
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            deviceId = event.deviceId
            // InputReader emits TAP/TAP_DRAG as a mouse press, with UP delayed to allow
            // dragging. PRESS/SWIPE/FREEFORM carry the native NO_FOCUS_CHANGE flag.
            // Preserve the former's down/up timing instead of recognizing another tap.
            // Older InputReader versions do not distinguish these modes with this flag.
            if (isMousePress(event)) {
                primaryDown = true
                sendPosition(event)
                send(CompatibilityTouchpadGesture.Action.Button(true), event.metaState)
                return true
            }
            gesture.down(points(event), event.eventTime,
                singleContactScroll = !event.isFromSource(InputDevice.SOURCE_TOUCHPAD),
                allowTap = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || event.classification == MotionEvent.CLASSIFICATION_NONE)
            return true
        }
        if (!owns(event)) return false
        if (primaryDown) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> sendPosition(event)
                MotionEvent.ACTION_UP -> {
                    sendPosition(event)
                    cancel()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancel()
                    deviceId = event.deviceId
                    gesture.down(points(event), event.eventTime)
                }
            }
            return true
        }
        val actions = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> gesture.pointerDown(points(event))
            MotionEvent.ACTION_MOVE -> {
                for (history in 0 until event.historySize) {
                    gesture.move(points(event, history)).forEach { send(it, event.metaState) }
                }
                gesture.move(points(event))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> gesture.up(points(event), event.eventTime)
            else -> return false
        }
        actions.forEach { send(it, event.metaState) }
        if (event.actionMasked == MotionEvent.ACTION_UP) cancel()
        return true
    }

    fun cancel() {
        if (primaryDown) {
            primaryDown = false
            send(CompatibilityTouchpadGesture.Action.Button(false), 0)
        }
        deviceId = -1
        rawButtonPointerId = MotionEvent.INVALID_POINTER_ID
        gesture.cancel().forEach { send(it, 0) }
    }

    private fun sendRawButtonMovement(points: List<CompatibilityTouchpadGesture.Point>, metaState: Int) {
        gesture.move(points.filter { it.id == rawButtonPointerId }).filterIsInstance<CompatibilityTouchpadGesture.Action.Move>()
            .forEach { send(it, metaState) }
    }

    fun destroy() {
        cancel()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) { if (this.deviceId == deviceId) cancel() }
    override fun onInputDeviceRemoved(deviceId: Int) { if (this.deviceId == deviceId) cancel() }

    private fun sendPosition(event: MotionEvent) =
        send(pointerPosition(event), event.metaState)

    companion object {
        // AMOTION_EVENT_FLAG_NO_FOCUS_CHANGE from Android's native input contract.
        // This is not FLAG_CANCELED (0x20); freeform gestures are valid input.
        private const val FLAG_NO_FOCUS_CHANGE = 0x40

        fun pointerPosition(event: MotionEvent): CompatibilityTouchpadGesture.Action.Position {
            var dx = 0f
            var dy = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE)) {
                dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                for (history in 0 until event.historySize) {
                    dx += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, history)
                    dy += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, history)
                }
            }
            return CompatibilityTouchpadGesture.Action.Position(event.rawX, event.rawY, dx, dy)
        }
    }

    private fun points(event: MotionEvent, history: Int = -1): List<CompatibilityTouchpadGesture.Point> {
        // Events may arrive through the Activity or a view. Keep screen coordinates
        // even when the stream is inset or letterboxed.
        val offsetX = event.rawX - event.x
        val offsetY = event.rawY - event.y
        return (0 until event.pointerCount).map {
            CompatibilityTouchpadGesture.Point(event.getPointerId(it),
                offsetX + if (history < 0) event.getX(it) else event.getHistoricalX(it, history),
                offsetY + if (history < 0) event.getY(it) else event.getHistoricalY(it, history))
        }
    }
}
