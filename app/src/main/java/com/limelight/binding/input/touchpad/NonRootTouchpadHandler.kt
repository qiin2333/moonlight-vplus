package com.limelight.binding.input.touchpad

import android.view.InputDevice
import android.view.MotionEvent
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.roundToInt

class NonRootTouchpadHandler {
    private val activePointerIds = LinkedHashSet<Int>()
    private var touchpadFrameUnsupported = false
    private var deviceWidthMm: Short = DEFAULT_TOUCHPAD_WIDTH_MM.toShort()
    private var deviceHeightMm: Short = DEFAULT_TOUCHPAD_HEIGHT_MM.toShort()

    fun handleMotionEvent(event: MotionEvent, conn: NvConnection?): Boolean {
        if (!isHardwareTouchpadEvent(event) || conn == null) {
            return false
        }

        val deviceInfo = DeviceInfo.from(event) ?: return false
        deviceWidthMm = deviceInfo.widthMm
        deviceHeightMm = deviceInfo.heightMm

        val buttonState = touchpadButtonState(event)
        val handled = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerIds.clear()
                activePointerIds.add(event.getPointerId(event.actionIndex))
                sendStateChangeFrame(conn, event, deviceInfo, event.actionIndex, true, buttonState)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerIds.add(event.getPointerId(event.actionIndex))
                sendStateChangeFrame(conn, event, deviceInfo, event.actionIndex, true, buttonState)
            }

            MotionEvent.ACTION_MOVE -> {
                syncActivePointers(event)
                sendMoveFrames(conn, event, deviceInfo, buttonState)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionPointerId = event.getPointerId(event.actionIndex)
                val sent = sendStateChangeFrame(conn, event, deviceInfo, event.actionIndex, false, buttonState)
                activePointerIds.remove(actionPointerId)
                sent
            }

            MotionEvent.ACTION_UP -> {
                val sent = sendStateChangeFrame(conn, event, deviceInfo, event.actionIndex, false, buttonState)
                activePointerIds.clear()
                sent
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelAll(conn)
                true
            }

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                sendButtonFrame(conn, event, deviceInfo, buttonState)
            }

            else -> false
        }

        return handled
    }

    fun cancelAll(conn: NvConnection?) {
        if (conn == null || activePointerIds.isEmpty()) {
            activePointerIds.clear()
            return
        }

        conn.sendTouchpadEvent(
            MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
            0,
            0f,
            0f,
            0f,
            0f,
            0f,
            MoonBridge.LI_ROT_UNKNOWN,
            deviceWidthMm,
            deviceHeightMm,
            0
        )
        activePointerIds.clear()
    }

    private fun sendMoveFrames(
        conn: NvConnection,
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        buttonState: Byte
    ): Boolean {
        var handled = true
        for (historyIndex in 0 until event.historySize) {
            val contacts = buildMoveContacts(event, deviceInfo, historyIndex)
            if (contacts.isNotEmpty()) {
                handled = sendContacts(conn, contacts, buttonState) && handled
            }
        }

        val contacts = buildMoveContacts(event, deviceInfo, null)
        return contacts.isNotEmpty() && sendContacts(conn, contacts, buttonState) && handled
    }

    private fun sendStateChangeFrame(
        conn: NvConnection,
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        actionIndex: Int,
        isDown: Boolean,
        buttonState: Byte
    ): Boolean {
        if (actionIndex !in 0 until event.pointerCount) {
            return false
        }

        val contacts = ArrayList<Contact>(event.pointerCount.coerceAtMost(MAX_TOUCHPAD_CONTACTS))
        if (isDown) {
            addContact(contacts, event, deviceInfo, actionIndex, MoonBridge.LI_TOUCH_EVENT_DOWN, null)
            addMoveContactsExcept(contacts, event, deviceInfo, actionIndex)
        } else {
            addMoveContactsExcept(contacts, event, deviceInfo, actionIndex)
            addContact(
                contacts,
                event,
                deviceInfo,
                actionIndex,
                if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    MoonBridge.LI_TOUCH_EVENT_CANCEL
                } else {
                    MoonBridge.LI_TOUCH_EVENT_UP
                },
                null
            )
        }

        return contacts.isNotEmpty() && sendContacts(conn, contacts, buttonState)
    }

    private fun sendButtonFrame(
        conn: NvConnection,
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        buttonState: Byte
    ): Boolean {
        if (event.pointerCount <= 0) {
            return false
        }

        val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val contacts = ArrayList<Contact>(1)
        addContact(contacts, event, deviceInfo, pointerIndex, MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY, null)
        return contacts.isNotEmpty() && sendContacts(conn, contacts, buttonState)
    }

    private fun buildMoveContacts(
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        historyIndex: Int?
    ): List<Contact> {
        val contacts = ArrayList<Contact>(event.pointerCount.coerceAtMost(MAX_TOUCHPAD_CONTACTS))
        val count = event.pointerCount.coerceAtMost(MAX_TOUCHPAD_CONTACTS)
        for (pointerIndex in 0 until count) {
            addContact(contacts, event, deviceInfo, pointerIndex, MoonBridge.LI_TOUCH_EVENT_MOVE, historyIndex)
        }
        return contacts
    }

    private fun addMoveContactsExcept(
        contacts: MutableList<Contact>,
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        skipPointerIndex: Int
    ) {
        val count = event.pointerCount.coerceAtMost(MAX_TOUCHPAD_CONTACTS)
        for (pointerIndex in 0 until count) {
            if (pointerIndex != skipPointerIndex) {
                addContact(contacts, event, deviceInfo, pointerIndex, MoonBridge.LI_TOUCH_EVENT_MOVE, null)
            }
        }
    }

    private fun addContact(
        contacts: MutableList<Contact>,
        event: MotionEvent,
        deviceInfo: DeviceInfo,
        pointerIndex: Int,
        eventType: Byte,
        historyIndex: Int?
    ) {
        if (contacts.size >= MAX_TOUCHPAD_CONTACTS || pointerIndex !in 0 until event.pointerCount) {
            return
        }

        val x = if (historyIndex == null) event.getX(pointerIndex) else event.getHistoricalX(pointerIndex, historyIndex)
        val y = if (historyIndex == null) event.getY(pointerIndex) else event.getHistoricalY(pointerIndex, historyIndex)
        val pressure = if (historyIndex == null) {
            event.getPressure(pointerIndex)
        } else {
            event.getHistoricalPressure(pointerIndex, historyIndex)
        }

        contacts.add(
            Contact(
                eventType,
                event.getPointerId(pointerIndex),
                normalize(x, deviceInfo.xRange),
                normalize(y, deviceInfo.yRange),
                normalizePressure(pressure, deviceInfo.pressureRange)
            )
        )
    }

    private fun sendContacts(conn: NvConnection, contacts: List<Contact>, buttonState: Byte): Boolean {
        if (!touchpadFrameUnsupported) {
            val frameResult = conn.sendTouchpadFrameEvent(
                contacts.size.toByte(),
                ByteArray(contacts.size) { index -> contacts[index].eventType },
                IntArray(contacts.size) { index -> contacts[index].pointerId },
                FloatArray(contacts.size) { index -> contacts[index].x },
                FloatArray(contacts.size) { index -> contacts[index].y },
                FloatArray(contacts.size) { index -> contacts[index].pressure },
                MoonBridge.LI_ROT_UNKNOWN,
                deviceWidthMm,
                deviceHeightMm,
                buttonState
            )

            if (frameResult != MoonBridge.LI_ERR_UNSUPPORTED) {
                return true
            }

            touchpadFrameUnsupported = true
        }

        return sendLegacyContacts(conn, contacts, buttonState)
    }

    private fun sendLegacyContacts(conn: NvConnection, contacts: List<Contact>, buttonState: Byte): Boolean {
        var handled = true
        for (contact in contacts) {
            val result = conn.sendTouchpadEvent(
                contact.eventType,
                contact.pointerId,
                contact.x,
                contact.y,
                contact.pressure,
                0f,
                0f,
                MoonBridge.LI_ROT_UNKNOWN,
                deviceWidthMm,
                deviceHeightMm,
                buttonState
            )
            handled = result != MoonBridge.LI_ERR_UNSUPPORTED && handled
        }
        return handled
    }

    private fun syncActivePointers(event: MotionEvent) {
        activePointerIds.clear()
        for (i in 0 until event.pointerCount) {
            activePointerIds.add(event.getPointerId(i))
        }
    }

    private data class Contact(
        val eventType: Byte,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val pressure: Float
    )

    private data class DeviceInfo(
        val xRange: InputDevice.MotionRange,
        val yRange: InputDevice.MotionRange,
        val pressureRange: InputDevice.MotionRange?,
        val widthMm: Short,
        val heightMm: Short
    ) {
        companion object {
            fun from(event: MotionEvent): DeviceInfo? {
                val device = event.device ?: return null
                val xRange = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
                    ?: device.getMotionRange(MotionEvent.AXIS_X, event.source)
                    ?: return null
                val yRange = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
                    ?: device.getMotionRange(MotionEvent.AXIS_Y, event.source)
                    ?: return null
                val pressureRange = device.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)
                    ?: device.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source)

                return DeviceInfo(
                    xRange,
                    yRange,
                    pressureRange,
                    computePhysicalSizeMm(xRange, DEFAULT_TOUCHPAD_WIDTH_MM).toShort(),
                    computePhysicalSizeMm(yRange, DEFAULT_TOUCHPAD_HEIGHT_MM).toShort()
                )
            }

            private fun computePhysicalSizeMm(range: InputDevice.MotionRange, fallback: Int): Int {
                val span = range.max - range.min + 1f
                val resolution = range.resolution
                if (span <= 0f || resolution <= 0f) {
                    return fallback
                }

                var mm = span / resolution
                if (mm < MIN_TOUCHPAD_MM && resolution >= 100f) {
                    mm = span / (resolution / 10f)
                }

                if (mm < MIN_TOUCHPAD_MM || mm > MAX_TOUCHPAD_MM) {
                    return fallback
                }

                return mm.roundToInt().coerceIn(MIN_TOUCHPAD_MM, MAX_TOUCHPAD_MM)
            }
        }
    }

    companion object {
        private const val MAX_TOUCHPAD_CONTACTS = 5
        private const val DEFAULT_TOUCHPAD_WIDTH_MM = 92
        private const val DEFAULT_TOUCHPAD_HEIGHT_MM = 54
        private const val MIN_TOUCHPAD_MM = 40
        private const val MAX_TOUCHPAD_MM = 200

        fun isHardwareTouchpadEvent(event: MotionEvent): Boolean {
            if ((event.source and InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
                return false
            }

            val deviceSources = event.device?.sources ?: return true
            return (deviceSources and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
                (deviceSources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        }

        private fun touchpadButtonState(event: MotionEvent): Byte {
            var state = 0
            if ((event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                state = state or MoonBridge.SS_TOUCHPAD_BUTTON_PRIMARY.toInt()
            }
            return state.toByte()
        }

        private fun normalize(value: Float, range: InputDevice.MotionRange): Float {
            if (range.range <= 0f) {
                return 0f
            }
            return ((value - range.min) / range.range).coerceIn(0f, 1f)
        }

        private fun normalizePressure(value: Float, range: InputDevice.MotionRange?): Float {
            if (range == null || range.range <= 0f) {
                return value.coerceIn(0f, 1f)
            }
            return normalize(value, range)
        }
    }
}
