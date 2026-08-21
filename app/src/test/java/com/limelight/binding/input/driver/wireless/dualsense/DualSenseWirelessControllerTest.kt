package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.AbstractController
import com.limelight.binding.input.driver.ControllerDriverListener
import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class DualSenseWirelessControllerTest {
    @Test
    fun announcesOnlyAfterFirstValidatedInputAndUsesStandardLifecycle() {
        val driverListener = Listener()
        var hidpListener: DualSenseHidpListener? = null
        var closed = false
        val outputReports = mutableListOf<ByteArray>()
        val controller = DualSenseWirelessController(
            deviceId = 12,
            listener = driverListener,
            openSession = { hidpListener = it; true },
            closeSession = { closed = true; true },
            sendOutputReport = { outputReports += it; true }
        )

        assertTrue(controller.start())
        assertTrue(driverListener.added.isEmpty())

        val result = DualSenseBluetoothInputCodec().decode(
            DualSenseBluetoothInputCodecTest.report(1)
        )
        hidpListener!!.onInput(result.state!!, result)
        assertTrue(driverListener.firstInput.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(controller), driverListener.added)
        assertEquals(1, driverListener.states)
        assertEquals(2, driverListener.motionEvents)
        assertTrue(controller.supportsAdaptiveTriggers)

        controller.rumble(0x5500, 0x3300)

        controller.stop()
        assertTrue(closed)
        assertEquals(listOf(controller), driverListener.removed)
        assertFalse(controller.start())
        assertTrue(outputReports.isNotEmpty())
        val lightbarSetup = outputReports.first()
        assertEquals(0x02, lightbarSetup[41].toInt() and 0xFF)
        assertEquals(0x02, lightbarSetup[44].toInt() and 0xFF)
        val neutral = outputReports.last()
        assertEquals(0, neutral[5].toInt() and 0xFF)
        assertEquals(0, neutral[6].toInt() and 0xFF)
        assertEquals(0x05, neutral[13].toInt() and 0xFF)
        assertEquals(0x05, neutral[24].toInt() and 0xFF)
    }

    @Test
    fun collapsesBacklogToTheNewestCompleteSnapshot() {
        val driverListener = BlockingListener()
        var hidpListener: DualSenseHidpListener? = null
        val controller = DualSenseWirelessController(
            deviceId = 13,
            listener = driverListener,
            openSession = { hidpListener = it; true },
            closeSession = { true },
            sendOutputReport = { true }
        )
        assertTrue(controller.start())
        val decoded = DualSenseBluetoothInputCodec().decode(
            DualSenseBluetoothInputCodecTest.report(1)
        )
        val base = decoded.state!!

        hidpListener!!.onInput(base.copy(leftStickX = 10), decoded)
        assertTrue(driverListener.firstDispatchEntered.await(2, TimeUnit.SECONDS))
        hidpListener!!.onInput(base.copy(leftStickX = 20), decoded)
        hidpListener!!.onInput(base.copy(leftStickX = 30), decoded)
        hidpListener!!.onInput(base.copy(leftStickX = 40), decoded)
        driverListener.releaseFirstDispatch.countDown()

        assertTrue(driverListener.secondDispatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(10, 40), driverListener.leftStickValues)
        controller.stop()
    }

    private class Listener : ControllerDriverListener {
        val added = mutableListOf<AbstractController>()
        val removed = mutableListOf<AbstractController>()
        var states = 0
        var motionEvents = 0
        val firstInput = CountDownLatch(1)

        override fun reportControllerState(
            controllerId: Int,
            buttonFlags: Int,
            leftStickX: Float,
            leftStickY: Float,
            rightStickX: Float,
            rightStickY: Float,
            leftTrigger: Float,
            rightTrigger: Float
        ) {
            states++
        }

        override fun deviceRemoved(controller: AbstractController) {
            removed += controller
        }

        override fun deviceAdded(controller: AbstractController) {
            added += controller
        }

        override fun reportControllerMotion(
            controllerId: Int,
            motionType: Byte,
            x: Float,
            y: Float,
            z: Float
        ) {
            motionEvents++
            if (motionEvents == 2) firstInput.countDown()
        }

        override fun reportControllerBattery(
            controllerId: Int,
            batteryState: Byte,
            batteryPercentage: Byte
        ) = Unit

        override fun reportControllerTouch(
            controllerId: Int,
            eventType: Byte,
            pointerId: Int,
            x: Float,
            y: Float
        ) = Unit

        override fun onDualSenseNativeHapticsSinkAvailable(
            controllerId: Int,
            sink: DualSenseNativeHapticsSink
        ) = Unit
    }

    private class BlockingListener : ControllerDriverListener {
        val firstDispatchEntered = CountDownLatch(1)
        val releaseFirstDispatch = CountDownLatch(1)
        val secondDispatch = CountDownLatch(1)
        val leftStickValues = mutableListOf<Int>()

        override fun reportControllerState(
            controllerId: Int,
            buttonFlags: Int,
            leftStickX: Float,
            leftStickY: Float,
            rightStickX: Float,
            rightStickY: Float,
            leftTrigger: Float,
            rightTrigger: Float
        ) {
            val value = ((leftStickX + 1f) * 255f / 2f).roundToInt()
            synchronized(leftStickValues) { leftStickValues += value }
            if (leftStickValues.size == 1) {
                firstDispatchEntered.countDown()
                releaseFirstDispatch.await(2, TimeUnit.SECONDS)
            } else {
                secondDispatch.countDown()
            }
        }

        override fun deviceRemoved(controller: AbstractController) = Unit
        override fun deviceAdded(controller: AbstractController) = Unit
        override fun reportControllerMotion(
            controllerId: Int,
            motionType: Byte,
            x: Float,
            y: Float,
            z: Float
        ) = Unit
        override fun reportControllerBattery(
            controllerId: Int,
            batteryState: Byte,
            batteryPercentage: Byte
        ) = Unit
        override fun reportControllerTouch(
            controllerId: Int,
            eventType: Byte,
            pointerId: Int,
            x: Float,
            y: Float
        ) = Unit
        override fun onDualSenseNativeHapticsSinkAvailable(
            controllerId: Int,
            sink: DualSenseNativeHapticsSink
        ) = Unit
    }
}
