package com.limelight.binding.input.driver

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseInputSessionTest {
    private data class TouchEvent(
        val type: Byte,
        val pointerId: Int,
        val x: Float,
        val y: Float
    )

    @Test
    fun defersTouchStateUntilControllerIsReadyAndTracksTransitions() {
        var ready = false
        val touches = mutableListOf<TouchEvent>()
        val session = DualSenseInputSession(
            isControllerReady = { ready },
            reportBattery = { _, _ -> },
            reportTouch = { type, pointerId, x, y ->
                touches += TouchEvent(type, pointerId, x, y)
            }
        )

        session.accept(state(touch0 = DualSenseTouchContact(true, 7, 0.25f, 0.5f)))
        assertTrue(touches.isEmpty())

        ready = true
        session.accept(state(touch0 = DualSenseTouchContact(true, 7, 0.25f, 0.5f)))
        session.accept(state(touch0 = DualSenseTouchContact(true, 7, 0.5f, 0.75f)))
        session.accept(state(touch0 = DualSenseTouchContact(false, 7, 0f, 0f)))

        assertEquals(
            listOf(
                MoonBridge.LI_TOUCH_EVENT_DOWN,
                MoonBridge.LI_TOUCH_EVENT_MOVE,
                MoonBridge.LI_TOUCH_EVENT_UP
            ),
            touches.map { it.type }
        )
        assertEquals(listOf(7, 7, 7), touches.map { it.pointerId })
    }

    @Test
    fun normalizesAxesAndMapsBattery() {
        val batteryEvents = mutableListOf<Pair<Byte, Byte>>()
        val session = DualSenseInputSession(
            isControllerReady = { true },
            reportBattery = { state, percentage -> batteryEvents += state to percentage },
            reportTouch = { _, _, _, _ -> }
        )

        val normalized = session.accept(
            state(
                leftStickX = 0,
                leftStickY = 255,
                leftTrigger = 128,
                battery = DualSenseBattery(DualSenseBatteryStatus.CHARGING, 75)
            )
        )

        assertEquals(-1f, normalized.leftStickX, 0f)
        assertEquals(1f, normalized.leftStickY, 0f)
        assertEquals(128f / 255f, normalized.leftTrigger, 0f)
        assertEquals(
            MoonBridge.LI_BATTERY_STATE_CHARGING to 75.toByte(),
            batteryEvents.single()
        )
    }

    @Test
    fun releaseTouchesEmitsFinalUp() {
        val eventTypes = mutableListOf<Byte>()
        val session = DualSenseInputSession(
            isControllerReady = { true },
            reportBattery = { _, _ -> },
            reportTouch = { type, _, _, _ -> eventTypes += type }
        )

        session.accept(state(touch0 = DualSenseTouchContact(true, 12, 0.1f, 0.2f)))
        session.releaseTouches()

        assertEquals(
            listOf(MoonBridge.LI_TOUCH_EVENT_DOWN, MoonBridge.LI_TOUCH_EVENT_UP),
            eventTypes
        )
    }

    @Test
    fun replacesAReusedHardwareSlotWhenTrackingIdChanges() {
        val touches = mutableListOf<TouchEvent>()
        val session = DualSenseInputSession(
            isControllerReady = { true },
            reportBattery = { _, _ -> },
            reportTouch = { type, pointerId, x, y ->
                touches += TouchEvent(type, pointerId, x, y)
            }
        )

        session.accept(state(touch0 = DualSenseTouchContact(true, 4, 0.1f, 0.2f)))
        session.accept(state(touch0 = DualSenseTouchContact(true, 5, 0.3f, 0.4f)))

        assertEquals(
            listOf(
                MoonBridge.LI_TOUCH_EVENT_DOWN,
                MoonBridge.LI_TOUCH_EVENT_UP,
                MoonBridge.LI_TOUCH_EVENT_DOWN
            ),
            touches.map { it.type }
        )
        assertEquals(listOf(4, 4, 5), touches.map { it.pointerId })
    }

    private fun state(
        leftStickX: Int = 128,
        leftStickY: Int = 128,
        leftTrigger: Int = 0,
        touch0: DualSenseTouchContact = DualSenseTouchContact(false, 0, 0f, 0f),
        battery: DualSenseBattery = DualSenseBattery(DualSenseBatteryStatus.FULL, 100)
    ) = DualSenseInputState(
        leftStickX = leftStickX,
        leftStickY = leftStickY,
        rightStickX = 128,
        rightStickY = 128,
        leftTrigger = leftTrigger,
        rightTrigger = 0,
        buttonFlags = 0,
        gyro = DualSenseVector3(0f, 0f, 0f),
        acceleration = DualSenseVector3(0f, 0f, 0f),
        touches = listOf(touch0, DualSenseTouchContact(false, 0, 0f, 0f)),
        battery = battery
    )
}
