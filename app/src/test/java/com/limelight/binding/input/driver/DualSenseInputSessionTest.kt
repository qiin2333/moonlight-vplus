package com.limelight.binding.input.driver

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

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
    fun normalizesAllSixAxesWithoutSwappingChannels() {
        val session = DualSenseInputSession({ true }, { _, _ -> }, { _, _, _, _ -> })
        val input = state(leftStickX = 0, leftStickY = 255, leftTrigger = 128).copy(
            rightStickX = 64, rightStickY = 192, rightTrigger = 255,
            buttonFlags = 0x1234,
            gyro = DualSenseVector3(1f, -2f, 3f),
            acceleration = DualSenseVector3(-4f, 5f, -6f)
        )
        val normalized = session.accept(input)
        assertEquals(-1f, normalized.leftStickX, 0f)
        assertEquals(1f, normalized.leftStickY, 0f)
        assertEquals(-0.49803922f, normalized.rightStickX, 0.000001f)
        assertEquals(0.5058824f, normalized.rightStickY, 0.000001f)
        assertEquals(0.5019608f, normalized.leftTrigger, 0.000001f)
        assertEquals(1f, normalized.rightTrigger, 0f)
        assertEquals(input.buttonFlags, normalized.buttonFlags)
        assertEquals(input.gyro, normalized.gyro)
        assertEquals(input.acceleration, normalized.acceleration)
        for ((raw, stick, trigger) in listOf(
            Triple(0, -1f, 0f), Triple(128, 0.003921628f, 0.5019608f), Triple(255, 1f, 1f)
        )) {
            val boundary = session.accept(input.copy(
                leftStickX = raw, leftStickY = raw, rightStickX = raw, rightStickY = raw,
                leftTrigger = raw, rightTrigger = raw
            ))
            for (actual in listOf(boundary.leftStickX, boundary.leftStickY,
                boundary.rightStickX, boundary.rightStickY)) {
                assertEquals("stick raw=$raw", stick, actual, 0.000001f)
            }
            assertEquals("left trigger raw=$raw", trigger, boundary.leftTrigger, 0.000001f)
            assertEquals("right trigger raw=$raw", trigger, boundary.rightTrigger, 0.000001f)
        }
    }

    @Test
    fun reportsBatteryWireStatesAndCapacityBoundaries() {
        val events = mutableListOf<Pair<Byte, Byte>>()
        val session = DualSenseInputSession({ false }, { status, percent -> events += status to percent },
            { _, _, _, _ -> error("touch must wait for controller readiness") })
        // Fixed reports exercise both the parser and the host event mapping.
        val cases = listOf(
            Triple(0x00, MoonBridge.LI_BATTERY_STATE_DISCHARGING, 5),
            Triple(0x09, MoonBridge.LI_BATTERY_STATE_DISCHARGING, 95),
            Triple(0x0F, MoonBridge.LI_BATTERY_STATE_DISCHARGING, 100),
            Triple(0x10, MoonBridge.LI_BATTERY_STATE_CHARGING, 5),
            Triple(0x17, MoonBridge.LI_BATTERY_STATE_CHARGING, 75),
            Triple(0x1F, MoonBridge.LI_BATTERY_STATE_CHARGING, 100),
            Triple(0x20, MoonBridge.LI_BATTERY_STATE_FULL, 100),
            Triple(0x2F, MoonBridge.LI_BATTERY_STATE_FULL, 100),
            Triple(0xA6, MoonBridge.LI_BATTERY_STATE_NOT_CHARGING, 0),
            Triple(0xB6, MoonBridge.LI_BATTERY_STATE_NOT_CHARGING, 0),
            Triple(0x36, MoonBridge.LI_BATTERY_STATE_UNKNOWN,
                MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN.toInt())
        )
        val unknownStates = listOf(0x46, 0x56, 0x66, 0x76, 0x86, 0x96, 0xC6, 0xD6, 0xE6, 0xF6).map {
            Triple(it, MoonBridge.LI_BATTERY_STATE_UNKNOWN, MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN.toInt())
        }
        val allCases = cases + unknownStates
        allCases.forEach { (raw, status, percent) ->
            val report = ByteArray(64).apply { this[0] = 1; this[53] = raw.toByte() }
            session.accept(DualSenseInputReportParser.parseUsbReport(ByteBuffer.wrap(report))!!)
            assertEquals("battery=$raw", status to percent.toByte(), events.last())
        }
        assertEquals(allCases.size, events.size)
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
