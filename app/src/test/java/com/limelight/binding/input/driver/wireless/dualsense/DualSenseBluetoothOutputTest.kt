package com.limelight.binding.input.driver.wireless.dualsense

import com.limelight.binding.input.driver.DualSenseAdaptiveTriggerEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class DualSenseBluetoothOutputTest {
    @Test
    fun encodesVerifiedBluetoothLayoutSequenceAndCrc() {
        val left = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE) {
            (0x20 + it).toByte()
        }
        val right = ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE) {
            (0x40 + it).toByte()
        }
        val report = DualSenseBluetoothOutputEncoder.encode(
            DualSenseBluetoothOutputSnapshot(
                validFlag0 = 0x0F,
                validFlag1 = 0x15,
                validFlag2 = 0,
                motorRight = 0x33,
                motorLeft = 0x55,
                rightTriggerType = 0x22,
                rightTrigger = right,
                leftTriggerType = 0x11,
                leftTrigger = left,
                muteLed = 1,
                playerLeds = 0x04,
                lightbarRed = 0x66,
                lightbarGreen = 0x77,
                lightbarBlue = 0x88
            ),
            sequence = 0x0A
        )

        assertEquals(78, report.size)
        assertEquals(0x31, report[0].toInt() and 0xFF)
        assertEquals(0xA0, report[1].toInt() and 0xFF)
        assertEquals(0x10, report[2].toInt() and 0xFF)
        assertEquals(0x0F, report[3].toInt() and 0xFF)
        assertEquals(0x15, report[4].toInt() and 0xFF)
        assertEquals(0x33, report[5].toInt() and 0xFF)
        assertEquals(0x55, report[6].toInt() and 0xFF)
        assertEquals(1, report[11].toInt() and 0xFF)
        assertEquals(0x22, report[13].toInt() and 0xFF)
        assertArrayEquals(right, report.copyOfRange(14, 24))
        assertEquals(0x11, report[24].toInt() and 0xFF)
        assertArrayEquals(left, report.copyOfRange(25, 35))
        assertEquals(0x04, report[46].toInt() and 0xFF)
        assertEquals(0x66, report[47].toInt() and 0xFF)
        assertEquals(0x77, report[48].toInt() and 0xFF)
        assertEquals(0x88, report[49].toInt() and 0xFF)

        val crc = CRC32().apply {
            update(0xA2)
            update(report, 0, 74)
        }.value
        val encodedCrc = (report[74].toLong() and 0xFF) or
            ((report[75].toLong() and 0xFF) shl 8) or
            ((report[76].toLong() and 0xFF) shl 16) or
            ((report[77].toLong() and 0xFF) shl 24)
        assertEquals(crc, encodedCrc)
    }

    @Test
    fun writerKeepsLatestStateAndClearsRumbleAndTriggersOnClose() {
        val reports = mutableListOf<ByteArray>()
        val events = mutableListOf<DualSenseBluetoothOutputEvent>()
        val firstSend = CountDownLatch(1)
        val writer = DualSenseBluetoothOutputWriter(
            sendReport = {
                synchronized(reports) { reports += it }
                firstSend.countDown()
                true
            },
            onOutputEvent = { synchronized(events) { events += it } }
        )

        assertTrue(writer.updateRumble(0x5500, 0x3300))
        assertTrue(firstSend.await(1, TimeUnit.SECONDS))
        assertTrue(writer.updateLightbar(0x11, 0x22, 0x33))
        writer.close(sendNeutral = true)

        val captured = synchronized(reports) { reports.toList() }
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.any {
            (it[5].toInt() and 0xFF) == 0x33 && (it[6].toInt() and 0xFF) == 0x55
        })
        val neutral = captured.last()
        assertEquals(0x0F, neutral[3].toInt() and 0xFF)
        assertEquals(0, neutral[5].toInt() and 0xFF)
        assertEquals(0, neutral[6].toInt() and 0xFF)
        assertEquals(DualSenseAdaptiveTriggerEffect.TYPE_OFF, neutral[13])
        assertEquals(DualSenseAdaptiveTriggerEffect.TYPE_OFF, neutral[24])
        val capturedEvents = synchronized(events) { events.toList() }
        assertEquals(3, capturedEvents.count { it == DualSenseBluetoothOutputEvent.SUBMITTED })
        assertTrue(capturedEvents.count { it == DualSenseBluetoothOutputEvent.SENT } >= 2)
        assertEquals(0, capturedEvents.count { it == DualSenseBluetoothOutputEvent.FAILED })
    }

    @Test
    fun lightbarSetupUsesTheOneShotSonyInitializationFields() {
        val report = DualSenseBluetoothOutputEncoder.lightbarSetup(sequence = 3)

        assertEquals(0x31, report[0].toInt() and 0xFF)
        assertEquals(0x30, report[1].toInt() and 0xFF)
        assertEquals(0x10, report[2].toInt() and 0xFF)
        assertEquals(0x02, report[41].toInt() and 0xFF)
        assertEquals(0x02, report[44].toInt() and 0xFF)
        assertEquals(0, report[4].toInt() and 0xFF)
    }

    @Test
    fun writerRetainsFailedStateUntilTransportIsReady() {
        val reports = mutableListOf<ByteArray>()
        val firstFailure = CountDownLatch(1)
        val retrySent = CountDownLatch(1)
        var attempts = 0
        val writer = DualSenseBluetoothOutputWriter(
            sendReport = {
                synchronized(reports) { reports += it }
                attempts++
                if (attempts == 1) false else true.also { retrySent.countDown() }
            },
            onOutputEvent = {
                if (it == DualSenseBluetoothOutputEvent.FAILED) firstFailure.countDown()
            }
        )

        assertTrue(writer.updateRumble(0x5500, 0x3300))
        assertTrue(firstFailure.await(1, TimeUnit.SECONDS))
        assertTrue(writer.retryPending())
        assertTrue(retrySent.await(1, TimeUnit.SECONDS))
        writer.close(sendNeutral = false)

        val captured = synchronized(reports) { reports.toList() }
        assertEquals(2, captured.size)
        assertEquals(0x33, captured[0][5].toInt() and 0xFF)
        assertEquals(0x55, captured[0][6].toInt() and 0xFF)
        assertEquals(0x33, captured[1][5].toInt() and 0xFF)
        assertEquals(0x55, captured[1][6].toInt() and 0xFF)
    }

    @Test
    fun failedLightbarInitializationCanBeRetried() {
        val firstFailure = CountDownLatch(1)
        val retrySent = CountDownLatch(1)
        var attempts = 0
        val writer = DualSenseBluetoothOutputWriter(
            sendReport = {
                attempts++
                if (attempts == 1) false else true.also { retrySent.countDown() }
            },
            onOutputEvent = {
                if (it == DualSenseBluetoothOutputEvent.FAILED) firstFailure.countDown()
            }
        )

        assertTrue(writer.initializeLightbar())
        assertTrue(firstFailure.await(1, TimeUnit.SECONDS))
        assertTrue(writer.initializeLightbar())
        assertTrue(retrySent.await(1, TimeUnit.SECONDS))
        writer.close(sendNeutral = false)

        assertEquals(2, attempts)
    }

    @Test
    fun directRouteOnlyAcceptsBluetoothDualSenseDevices() {
        assertTrue(DirectDualSenseBluetoothOutput.isDualSenseProduct(0x054C, 0x0CE6))
        assertTrue(DirectDualSenseBluetoothOutput.isDualSenseProduct(0x054C, 0x0DF2))
        assertFalse(DirectDualSenseBluetoothOutput.isDualSenseProduct(0x054C, 0x09CC))
        assertTrue(DirectDualSenseBluetoothOutput.supports(
            vendorId = 0x054C,
            productId = 0x0CE6,
            descriptor = "bluetooth:12:34:56:78:9A:BC",
            name = "Wireless Controller",
            description = ""
        ))
        assertTrue(DirectDualSenseBluetoothOutput.supports(
            vendorId = 0x054C,
            productId = 0x0DF2,
            descriptor = "descriptor",
            name = "DualSense Edge Wireless Controller",
            description = "bluetoothAddress=12:34:56:78:9A:BC"
        ))
        assertFalse(DirectDualSenseBluetoothOutput.supports(
            vendorId = 0x054C,
            productId = 0x09CC,
            descriptor = "bluetooth",
            name = "Wireless Controller",
            description = ""
        ))
        assertFalse(DirectDualSenseBluetoothOutput.supports(
            vendorId = 0x054C,
            productId = 0x0CE6,
            descriptor = "usb",
            name = "DualSense Wireless Controller",
            description = ""
        ))
    }

    @Test
    fun triggerRumbleMatchesVerifiedDualSenseEffectEncoding() {
        val (offType, offPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble(0)
        val (fullType, fullPayload) = DualSenseAdaptiveTriggerEffect.triggerRumble((-1).toShort())

        assertEquals(DualSenseAdaptiveTriggerEffect.TYPE_OFF, offType)
        assertArrayEquals(ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE), offPayload)
        assertEquals(0x27.toByte(), fullType)
        assertEquals(0xFF.toByte(), fullPayload[0])
        assertEquals(0x03.toByte(), fullPayload[1])
        assertEquals(0x3F.toByte(), fullPayload[2])
    }
}
