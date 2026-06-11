package com.limelight.binding.input.evdev

import android.app.Activity
import android.os.Build
import android.os.Looper
import android.widget.Toast

import com.limelight.LimeLog
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.nvstream.jni.MoonBridge

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max
import kotlin.math.roundToInt

class EvdevCaptureProvider(
    private val activity: Activity,
    private val listener: EvdevListener,
    private val optimizeHardwareTouchpad: Boolean
) : InputCaptureProvider() {

    private val libraryPath: String = activity.applicationInfo.nativeLibraryDir

    private var shutdown = false
    private var evdevIn: InputStream? = null
    private var evdevOut: OutputStream? = null
    private var su: Process? = null
    private var servSock: ServerSocket? = null
    private var evdevSock: Socket? = null
    private var started = false
    private val touchpadDevices = HashMap<Int, TouchpadDeviceState>()

    private val handlerThread = object : Thread() {
        override fun run() {
            var deltaX = 0
            var deltaY = 0
            var deltaVScroll: Byte = 0
            var deltaHScroll: Byte = 0

            try {
                servSock = ServerSocket(0, 1)
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }

            val evdevReaderCmd = libraryPath + File.separatorChar + "libevdev_reader.so " +
                servSock!!.localPort + " " + (if (optimizeHardwareTouchpad) 1 else 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    su = ProcessBuilder("su", "-c", evdevReaderCmd).start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            } else {
                val builder = ProcessBuilder("su")
                builder.redirectErrorStream(true)

                try {
                    su = builder.start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }

                val suOut = DataOutputStream(su!!.outputStream)
                try {
                    suOut.writeChars("$evdevReaderCmd\n")
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            }

            LimeLog.info("Waiting for EvdevReader connection to port " + servSock!!.localPort)
            try {
                evdevSock = servSock!!.accept()
                evdevIn = evdevSock!!.getInputStream()
                evdevOut = evdevSock!!.getOutputStream()
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }
            LimeLog.info("EvdevReader connected from port " + evdevSock!!.port)

            while (!isInterrupted && !shutdown) {
                val event: EvdevEvent?
                try {
                    event = EvdevReader.read(evdevIn!!)
                } catch (e: IOException) {
                    break
                }
                if (event == null) break

                if (event.isTouchpad) {
                    touchpadDevices.getOrPut(event.deviceId) {
                        LimeLog.info(
                            "Evdev touchpad detected: deviceId=${event.deviceId}, " +
                                "x=${event.absXMin}..${event.absXMax}@${event.absXResolution}, " +
                                "y=${event.absYMin}..${event.absYMax}@${event.absYResolution}"
                        )
                        TouchpadDeviceState(event, optimizeHardwareTouchpad)
                    }.handleEvent(event, listener)
                    continue
                }

                when (event.type) {
                    EvdevEvent.EV_SYN -> {
                        if (deltaX != 0 || deltaY != 0) {
                            listener.mouseMove(deltaX, deltaY)
                            deltaX = 0
                            deltaY = 0
                        }
                        if (deltaVScroll.toInt() != 0) {
                            listener.mouseVScroll(deltaVScroll)
                            deltaVScroll = 0
                        }
                        if (deltaHScroll.toInt() != 0) {
                            listener.mouseHScroll(deltaHScroll)
                            deltaHScroll = 0
                        }
                    }

                    EvdevEvent.EV_REL -> {
                        when (event.code) {
                            EvdevEvent.REL_X -> deltaX = event.value
                            EvdevEvent.REL_Y -> deltaY = event.value
                            EvdevEvent.REL_HWHEEL -> deltaHScroll = event.value.toByte()
                            EvdevEvent.REL_WHEEL -> deltaVScroll = event.value.toByte()
                        }
                    }

                    EvdevEvent.EV_KEY -> {
                        when (event.code) {
                            EvdevEvent.BTN_LEFT ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_LEFT, event.value != 0)
                            EvdevEvent.BTN_MIDDLE ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_MIDDLE, event.value != 0)
                            EvdevEvent.BTN_RIGHT ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_RIGHT, event.value != 0)
                            EvdevEvent.BTN_SIDE ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X1, event.value != 0)
                            EvdevEvent.BTN_EXTRA ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X2, event.value != 0)
                            EvdevEvent.BTN_FORWARD, EvdevEvent.BTN_BACK, EvdevEvent.BTN_TASK -> {
                                // Other unhandled mouse buttons
                            }
                            else -> {
                                val keyCode = EvdevTranslator.translateEvdevKeyCode(event.code)
                                if (keyCode.toInt() != 0) {
                                    listener.keyboardEvent(event.value != 0, keyCode)
                                }
                            }
                        }
                    }

                    EvdevEvent.EV_MSC -> { }
                }
            }
        }
    }

    private class TouchpadDeviceState(firstEvent: EvdevEvent, private val optimizeHardwareTouchpad: Boolean) {
        private val slots = Array(MAX_TOUCHPAD_SLOTS) { TouchpadSlot() }
        private var currentSlot = 0
        private var buttonState: Byte = 0
        private var buttonChanged = false
        private var touchpadFrameUnsupported = false
        private var pendingMoveFrame: TouchpadFrame? = null
        private var lastMoveFrameSentNs = 0L
        private val toolFingerDown = BooleanArray(MAX_TOUCHPAD_SLOTS + 1)
        private var frameMaxToolFingerCount = 0
        private val syntheticSlots = Array(MAX_TOUCHPAD_SLOTS) { SyntheticTouchpadSlot() }

        private var absXMin = 0
        private var absXMax = 0
        private var absYMin = 0
        private var absYMax = 0
        private var deviceWidthMm: Short = DEFAULT_TOUCHPAD_WIDTH_MM.toShort()
        private var deviceHeightMm: Short = DEFAULT_TOUCHPAD_HEIGHT_MM.toShort()

        init {
            updateDeviceInfo(firstEvent)
        }

        fun handleEvent(event: EvdevEvent, listener: EvdevListener) {
            updateDeviceInfo(event)

            when (event.type) {
                EvdevEvent.EV_ABS -> handleAbsEvent(event)
                EvdevEvent.EV_KEY -> handleKeyEvent(event)
                EvdevEvent.EV_SYN -> {
                    when (event.code) {
                        EvdevEvent.SYN_REPORT -> flushFrame(listener)
                        EvdevEvent.SYN_DROPPED -> cancelAll(listener)
                    }
                }
            }
        }

        private fun updateDeviceInfo(event: EvdevEvent) {
            if (event.absXMax > event.absXMin && event.absYMax > event.absYMin) {
                absXMin = event.absXMin
                absXMax = event.absXMax
                absYMin = event.absYMin
                absYMax = event.absYMax
                deviceWidthMm = computePhysicalSizeMm(
                    event.absXMin,
                    event.absXMax,
                    event.absXResolution,
                    DEFAULT_TOUCHPAD_WIDTH_MM
                ).toShort()
                deviceHeightMm = computePhysicalSizeMm(
                    event.absYMin,
                    event.absYMax,
                    event.absYResolution,
                    DEFAULT_TOUCHPAD_HEIGHT_MM
                ).toShort()
            }
        }

        private fun handleAbsEvent(event: EvdevEvent) {
            when (event.code) {
                EvdevEvent.ABS_MT_SLOT -> {
                    currentSlot = if (event.value in 0 until MAX_TOUCHPAD_SLOTS) event.value else -1
                }
                EvdevEvent.ABS_MT_TRACKING_ID -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    if (event.value >= 0) {
                        slot.trackingId = event.value
                        slot.lastTrackingId = event.value
                        slot.active = true
                        slot.pendingDown = true
                        slot.pendingUp = false
                        slot.dirty = true
                    } else if (slot.active || slot.pendingDown) {
                        slot.lastTrackingId = slot.trackingId
                        slot.active = false
                        slot.pendingDown = false
                        slot.pendingUp = true
                        slot.dirty = false
                    }
                }
                EvdevEvent.ABS_MT_POSITION_X -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    slot.x = normalize(event.value, absXMin, absXMax)
                    slot.dirty = slot.active || slot.pendingDown
                }
                EvdevEvent.ABS_MT_POSITION_Y -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    slot.y = normalize(event.value, absYMin, absYMax)
                    slot.dirty = slot.active || slot.pendingDown
                }
                EvdevEvent.ABS_MT_PRESSURE -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    slot.pressure = (event.value / 255f).coerceIn(0f, 1f)
                    slot.dirty = slot.active || slot.pendingDown
                }
                EvdevEvent.ABS_MT_TOUCH_MAJOR -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    slot.contactAreaMajor = 0f
                    slot.dirty = slot.active || slot.pendingDown
                }
                EvdevEvent.ABS_MT_TOUCH_MINOR -> {
                    if (currentSlot < 0) return
                    val slot = slots[currentSlot]
                    slot.contactAreaMinor = 0f
                    slot.dirty = slot.active || slot.pendingDown
                }
            }
        }

        private fun handleKeyEvent(event: EvdevEvent) {
            val toolFingerCount = toolFingerCountForCode(event.code)
            if (toolFingerCount > 0) {
                val isDown = event.value != 0
                toolFingerDown[toolFingerCount] = isDown
                if (isDown) {
                    frameMaxToolFingerCount = max(frameMaxToolFingerCount, toolFingerCount)
                }
                return
            }

            if (event.code == EvdevEvent.BTN_LEFT) {
                val newState = if (event.value != 0) {
                    (buttonState.toInt() or MoonBridge.SS_TOUCHPAD_BUTTON_PRIMARY.toInt()).toByte()
                } else {
                    (buttonState.toInt() and MoonBridge.SS_TOUCHPAD_BUTTON_PRIMARY.toInt().inv()).toByte()
                }

                if (newState != buttonState) {
                    buttonState = newState
                    buttonChanged = true
                }
            }
        }

        private fun flushFrame(listener: EvdevListener) {
            if (!optimizeHardwareTouchpad || touchpadFrameUnsupported) {
                pendingMoveFrame = null
                flushFrameLegacy(listener)
                return
            }

            updateSyntheticContacts()

            val frame = buildFrame() ?: run {
                flushPendingMoveIfDue(listener)
                finishFrame()
                return
            }

            if (frame.hasStateChange) {
                pendingMoveFrame = null
                if (!sendFrame(listener, frame)) {
                    flushFrameLegacy(listener)
                    return
                }
                lastMoveFrameSentNs = System.nanoTime()
                finishFrame()
                return
            }

            pendingMoveFrame = frame
            val nowNs = System.nanoTime()
            if (lastMoveFrameSentNs == 0L || nowNs - lastMoveFrameSentNs >= MOVE_FRAME_MIN_INTERVAL_NS) {
                val moveFrame = pendingMoveFrame
                pendingMoveFrame = null
                if (moveFrame != null && !sendFrame(listener, moveFrame)) {
                    flushFrameLegacy(listener)
                    return
                }
                lastMoveFrameSentNs = nowNs
            }

            finishFrame()
        }

        private fun buildFrame(): TouchpadFrame? {
            val hasContactStateChange = slots.any { it.pendingDown || it.pendingUp } ||
                syntheticSlots.any { it.pendingDown || it.pendingUp }
            val hasMove = slots.any { it.active && it.dirty && !it.pendingUp } ||
                syntheticSlots.any { it.active && it.dirty && !it.pendingUp }
            val hasStateChange = hasContactStateChange || buttonChanged

            if (!hasStateChange && !hasMove) {
                return null
            }

            val eventTypes = ByteArray(MAX_TOUCHPAD_SLOTS)
            val pointerIds = IntArray(MAX_TOUCHPAD_SLOTS)
            val x = FloatArray(MAX_TOUCHPAD_SLOTS)
            val y = FloatArray(MAX_TOUCHPAD_SLOTS)
            val pressure = FloatArray(MAX_TOUCHPAD_SLOTS)
            var contactCount = 0

            fun appendSlot(eventType: Byte, slot: TouchpadSlot, pointerId: Int) {
                if (contactCount >= MAX_TOUCHPAD_SLOTS) return
                eventTypes[contactCount] = eventType
                pointerIds[contactCount] = pointerId.coerceAtLeast(0)
                x[contactCount] = slot.x
                y[contactCount] = slot.y
                pressure[contactCount] = slot.pressure
                contactCount++
            }

            for (slot in slots) {
                if (slot.pendingDown) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_DOWN, slot, slot.trackingId)
                }
            }

            for (slot in slots) {
                if (slot.active && !slot.pendingDown && !slot.pendingUp && (slot.dirty || hasStateChange)) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_MOVE, slot, slot.trackingId)
                }
            }

            for (slot in syntheticSlots) {
                if (slot.pendingDown) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_DOWN, slot, slot.trackingId)
                }
            }

            for (slot in syntheticSlots) {
                if (slot.active && !slot.pendingDown && !slot.pendingUp && (slot.dirty || hasStateChange)) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_MOVE, slot, slot.trackingId)
                }
            }

            if (buttonChanged && contactCount == 0) {
                val primarySlot = slots.firstOrNull { it.active && !it.pendingUp }
                if (primarySlot != null) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY, primarySlot, primarySlot.trackingId)
                }
            }

            for (slot in slots) {
                if (slot.pendingUp) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_UP, slot, slot.lastTrackingId)
                }
            }

            for (slot in syntheticSlots) {
                if (slot.pendingUp) {
                    appendSlot(MoonBridge.LI_TOUCH_EVENT_UP, slot, slot.lastTrackingId)
                }
            }

            if (contactCount == 0) {
                return null
            }

            return TouchpadFrame(
                contactCount,
                eventTypes,
                pointerIds,
                x,
                y,
                pressure,
                hasStateChange
            )
        }

        private fun flushPendingMoveIfDue(listener: EvdevListener) {
            val frame = pendingMoveFrame ?: return
            val nowNs = System.nanoTime()
            if (lastMoveFrameSentNs == 0L || nowNs - lastMoveFrameSentNs >= MOVE_FRAME_MIN_INTERVAL_NS) {
                pendingMoveFrame = null
                if (sendFrame(listener, frame)) {
                    lastMoveFrameSentNs = nowNs
                }
            }
        }

        private fun sendFrame(listener: EvdevListener, frame: TouchpadFrame): Boolean {
            val result = listener.touchpadFrameEvent(
                frame.contactCount.toByte(),
                frame.eventTypes,
                frame.pointerIds,
                frame.x,
                frame.y,
                frame.pressure,
                MoonBridge.LI_ROT_UNKNOWN,
                deviceWidthMm,
                deviceHeightMm,
                buttonState
            )

            if (result == MoonBridge.LI_ERR_UNSUPPORTED) {
                touchpadFrameUnsupported = true
                pendingMoveFrame = null
                return false
            }

            return true
        }

        private fun flushFrameLegacy(listener: EvdevListener) {
            resetSyntheticContacts()

            for (slot in slots) {
                if (slot.pendingDown) {
                    sendSlot(listener, MoonBridge.LI_TOUCH_EVENT_DOWN, slot, slot.trackingId)
                    slot.pendingDown = false
                    slot.dirty = false
                }
            }

            for (slot in slots) {
                if (slot.active && slot.dirty && !slot.pendingUp) {
                    sendSlot(listener, MoonBridge.LI_TOUCH_EVENT_MOVE, slot, slot.trackingId)
                    slot.dirty = false
                }
            }

            if (buttonChanged) {
                val primarySlot = slots.firstOrNull { it.active && !it.pendingUp }
                if (primarySlot != null) {
                    sendSlot(listener, MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY, primarySlot, primarySlot.trackingId)
                }
                buttonChanged = false
            }

            for (slot in slots) {
                if (slot.pendingUp) {
                    sendSlot(listener, MoonBridge.LI_TOUCH_EVENT_UP, slot, slot.lastTrackingId)
                    slot.pendingUp = false
                    slot.trackingId = -1
                    slot.lastTrackingId = -1
                    slot.dirty = false
                    slot.pressure = 0f
                    slot.contactAreaMajor = 0f
                    slot.contactAreaMinor = 0f
                }
            }
        }

        private fun finishFrame() {
            finishSlots(slots)
            finishSlots(syntheticSlots)
            frameMaxToolFingerCount = 0
            buttonChanged = false
        }

        private fun finishSlots(frameSlots: Array<out TouchpadSlot>) {
            for (slot in frameSlots) {
                if (slot.pendingDown) {
                    slot.pendingDown = false
                    slot.dirty = false
                }
            }

            for (slot in frameSlots) {
                if (slot.active && slot.dirty && !slot.pendingUp) {
                    slot.dirty = false
                }
            }

            for (slot in frameSlots) {
                if (slot.pendingUp) {
                    slot.pendingUp = false
                    slot.trackingId = -1
                    slot.lastTrackingId = -1
                    slot.dirty = false
                    slot.pressure = 0f
                    slot.contactAreaMajor = 0f
                    slot.contactAreaMinor = 0f
                }
            }
        }

        private fun cancelAll(listener: EvdevListener) {
            pendingMoveFrame = null
            lastMoveFrameSentNs = 0L

            listener.touchpadEvent(
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
                buttonState
            )

            for (slot in slots) {
                slot.reset()
            }
            resetSyntheticContacts()
            toolFingerDown.fill(false)
            frameMaxToolFingerCount = 0
            buttonChanged = false
        }

        private fun sendSlot(listener: EvdevListener, eventType: Byte, slot: TouchpadSlot, pointerId: Int) {
            listener.touchpadEvent(
                eventType,
                pointerId.coerceAtLeast(0),
                slot.x,
                slot.y,
                slot.pressure,
                slot.contactAreaMajor,
                slot.contactAreaMinor,
                MoonBridge.LI_ROT_UNKNOWN,
                deviceWidthMm,
                deviceHeightMm,
                buttonState
            )
        }

        private fun updateSyntheticContacts() {
            val realActiveSlots = slots.filter { (it.active || it.pendingDown) && !it.pendingUp }
            val toolFingerCount = max(highestToolFingerCount(), frameMaxToolFingerCount)
            // Four-finger gestures need real contact slots. BTN_TOOL_QUADTAP alone
            // does not include positions, so do not invent a fourth/fifth contact.
            val syntheticToolFingerCount = if (toolFingerCount <= MAX_SYNTHETIC_TOOL_FINGERS) {
                toolFingerCount
            }
            else {
                0
            }
            val targetFingerCount = max(syntheticToolFingerCount, realActiveSlots.size)
                .coerceAtMost(MAX_TOUCHPAD_SLOTS)
            val syntheticNeeded = (targetFingerCount - realActiveSlots.size)
                .coerceIn(0, MAX_TOUCHPAD_SLOTS - realActiveSlots.size)

            if (syntheticNeeded == 0 || realActiveSlots.isEmpty()) {
                releaseSyntheticContacts()
                return
            }

            var centroidX = 0f
            var centroidY = 0f
            var pressureTotal = 0f
            for (slot in realActiveSlots) {
                centroidX += slot.x
                centroidY += slot.y
                pressureTotal += if (slot.pressure > 0f) slot.pressure else DEFAULT_TOUCHPAD_PRESSURE
            }
            centroidX /= realActiveSlots.size
            centroidY /= realActiveSlots.size
            val pressure = (pressureTotal / realActiveSlots.size).coerceIn(0f, 1f)

            for (i in syntheticSlots.indices) {
                val slot = syntheticSlots[i]
                if (i < syntheticNeeded) {
                    val newX = syntheticX(centroidX, i)
                    val newY = syntheticY(centroidY, i)
                    val trackingId = SYNTHETIC_POINTER_ID_BASE + i

                    if (!slot.active && !slot.pendingDown) {
                        slot.trackingId = trackingId
                        slot.lastTrackingId = trackingId
                        slot.active = true
                        slot.pendingDown = true
                        slot.pendingUp = false
                        slot.dirty = true
                    }
                    else if (slot.x != newX || slot.y != newY || slot.pressure != pressure) {
                        slot.dirty = slot.active || slot.pendingDown
                    }

                    slot.x = newX
                    slot.y = newY
                    slot.pressure = pressure
                }
                else {
                    releaseSyntheticContact(slot)
                }
            }
        }

        private fun releaseSyntheticContacts() {
            for (slot in syntheticSlots) {
                releaseSyntheticContact(slot)
            }
        }

        private fun releaseSyntheticContact(slot: SyntheticTouchpadSlot) {
            if (slot.active || slot.pendingDown) {
                slot.lastTrackingId = slot.trackingId
                slot.active = false
                slot.pendingDown = false
                slot.pendingUp = true
                slot.dirty = false
            }
        }

        private fun resetSyntheticContacts() {
            for (slot in syntheticSlots) {
                slot.reset()
            }
        }

        private fun highestToolFingerCount(): Int {
            for (i in toolFingerDown.indices.reversed()) {
                if (toolFingerDown[i]) {
                    return i
                }
            }

            return 0
        }

        private fun toolFingerCountForCode(code: Short): Int {
            return when (code) {
                EvdevEvent.BTN_TOOL_FINGER -> 1
                EvdevEvent.BTN_TOOL_DOUBLETAP -> 2
                EvdevEvent.BTN_TOOL_TRIPLETAP -> 3
                EvdevEvent.BTN_TOOL_QUADTAP -> 4
                EvdevEvent.BTN_TOOL_QUINTTAP -> 5
                else -> 0
            }
        }

        private fun syntheticX(centroidX: Float, index: Int): Float {
            val direction = when (index) {
                1 -> -1f
                3 -> -1f
                else -> 1f
            }

            return (centroidX + direction * SYNTHETIC_CONTACT_OFFSET).coerceIn(0f, 1f)
        }

        private fun syntheticY(centroidY: Float, index: Int): Float {
            val direction = when (index) {
                2 -> -1f
                3 -> -1f
                else -> 1f
            }

            return (centroidY + direction * SYNTHETIC_CONTACT_OFFSET).coerceIn(0f, 1f)
        }

        private fun normalize(value: Int, min: Int, max: Int): Float {
            val range = max - min
            if (range <= 0) return 0f
            return ((value - min).toFloat() / range.toFloat()).coerceIn(0f, 1f)
        }

        private fun computePhysicalSizeMm(min: Int, max: Int, resolution: Int, fallback: Int): Int {
            val range = max - min + 1
            if (range <= 0 || resolution <= 0) return fallback

            var mm = range.toFloat() / resolution.toFloat()

            // Xiaomi's Android dump reports values such as 312 for an axis that is
            // physically about 92 mm wide, which behaves like tenths of units/mm.
            if (mm < MIN_TOUCHPAD_MM && resolution >= 100) {
                mm = range.toFloat() / (resolution.toFloat() / 10f)
            }

            if (mm < MIN_TOUCHPAD_MM || mm > MAX_TOUCHPAD_MM) {
                return fallback
            }

            return mm.roundToInt().coerceIn(MIN_TOUCHPAD_MM, MAX_TOUCHPAD_MM)
        }

        private open class TouchpadSlot {
            var active = false
            var trackingId = -1
            var lastTrackingId = -1
            var x = 0f
            var y = 0f
            var pressure = 0f
            var contactAreaMajor = 0f
            var contactAreaMinor = 0f
            var pendingDown = false
            var pendingUp = false
            var dirty = false

            fun reset() {
                active = false
                trackingId = -1
                lastTrackingId = -1
                x = 0f
                y = 0f
                pressure = 0f
                contactAreaMajor = 0f
                contactAreaMinor = 0f
                pendingDown = false
                pendingUp = false
                dirty = false
            }
        }

        private class SyntheticTouchpadSlot : TouchpadSlot()

        private class TouchpadFrame(
            val contactCount: Int,
            val eventTypes: ByteArray,
            val pointerIds: IntArray,
            val x: FloatArray,
            val y: FloatArray,
            val pressure: FloatArray,
            val hasStateChange: Boolean
        )

        companion object {
            private const val MAX_TOUCHPAD_SLOTS = 5
            private const val MOVE_FRAME_MIN_INTERVAL_NS = 8_333_333L
            private const val MAX_SYNTHETIC_TOOL_FINGERS = 3
            private const val SYNTHETIC_POINTER_ID_BASE = 0x10000000
            private const val SYNTHETIC_CONTACT_OFFSET = 0.08f
            private const val DEFAULT_TOUCHPAD_PRESSURE = 0.5f
            private const val DEFAULT_TOUCHPAD_WIDTH_MM = 92
            private const val DEFAULT_TOUCHPAD_HEIGHT_MM = 54
            private const val MIN_TOUCHPAD_MM = 40
            private const val MAX_TOUCHPAD_MM = 200
        }
    }

    private fun reportDeviceNotRooted() {
        activity.runOnUiThread {
            Toast.makeText(activity, "This device is not rooted - Mouse capture is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun runInNetworkSafeContextSynchronously(runnable: Runnable) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            val t = Thread(runnable)
            t.start()
            try {
                t.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        } else {
            runnable.run()
        }
    }

    override fun showCursor() {
        super.showCursor()
        runInNetworkSafeContextSynchronously {
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut!!.write(UNGRAB_REQUEST.toInt())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun hideCursor() {
        super.hideCursor()
        runInNetworkSafeContextSynchronously {
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut!!.write(REGRAB_REQUEST.toInt())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun enableCapture() {
        if (!started) {
            handlerThread.start()
            started = true
        }
        super.enableCapture()
    }

    override fun destroy() {
        if (!started) return

        shutdown = true
        handlerThread.interrupt()

        runInNetworkSafeContextSynchronously {
            try {
                servSock?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevSock?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevIn?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevOut?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        su?.destroy()

        try {
            handlerThread.join()
        } catch (_: InterruptedException) { }
    }

    companion object {
        private const val UNGRAB_REQUEST: Byte = 1
        private const val REGRAB_REQUEST: Byte = 2
    }
}
