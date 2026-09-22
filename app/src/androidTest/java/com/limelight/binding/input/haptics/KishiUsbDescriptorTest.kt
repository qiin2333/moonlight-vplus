package com.limelight.binding.input.haptics

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in XL hardware checks: passive descriptor read or explicit production channel test. */
@RunWith(AndroidJUnit4::class)
class KishiUsbDescriptorTest {
    @Test fun cancellingStartupReleasesUsbWithoutWaitingForMetadataBudget() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiRumbleLifecycle") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.single { it.vendorId == 0x1532 && it.productId == 0x0727 }
        val iface = (0 until device.interfaceCount).map(device::getInterface).single { it.id == 4 }
        val sink = KishiSensaHapticsSink(manager, device, iface,
            (0 until iface.endpointCount).map(iface::getEndpoint).single { it.address == 4 },
            strength = { 0.0 }, frequency = { 100.0 }, conversionEnabled = { true }, pcmEnabled = { true }) {}
        val caller = Thread { sink.start() }
        val released = java.util.concurrent.CountDownLatch(1)
        caller.start()
        SystemClock.sleep(5)
        sink.stopAndThen { released.countDown() }
        assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(500)
        assertTrue(!caller.isAlive)
        assertTrue(!sink.isOperational)
        assertTrue(sink.releaseFailure == null)
    }
    /** Silent transport check: conversion, live toggle, PCM with conversion off, and stop. */
    @Test fun testRumbleConversionLifecycle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiRumbleLifecycle") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.single { it.vendorId == 0x1532 && it.productId == 0x0727 }
        assertTrue(manager.hasPermission(device))
        val iface = (0 until device.interfaceCount).map(device::getInterface).single { it.id == 4 }
        val enabled = java.util.concurrent.atomic.AtomicBoolean(true)
        val pcm = java.util.concurrent.atomic.AtomicBoolean(true)
        val sink = KishiSensaHapticsSink(manager, device, iface,
            (0 until iface.endpointCount).map(iface::getEndpoint).single { it.address == 4 },
            strength = { 0.0 }, frequency = { 100.0 }, conversionEnabled = enabled::get, pcmEnabled = pcm::get) {}
        fun awaitPlayback(playing: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 1000
            while (sink.playbackActive != playing && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5)
            assertTrue("Unexpected playback state", sink.playbackActive == playing)
            assertTrue("USB output failed", sink.isOperational)
        }
        val stopped = java.util.concurrent.CountDownLatch(1)
        try {
            assertTrue(sink.start())
            sink.submitRumble(1f, 0.5f)
            awaitPlayback(true)
            enabled.set(false)
            awaitPlayback(false)
            // Authored haptics still run with conversion disabled.
            sink.submit(com.limelight.nvstream.Ds5HapticsPcmFrame(0, 0, 0, 0, 4000, 40, 2, 16, ByteArray(160)))
            awaitPlayback(true)
            awaitPlayback(false)
            enabled.set(true)
            awaitPlayback(true)
            sink.submitRumble(0f, 0f)
            awaitPlayback(false)
            pcm.set(false)
            sink.submit(com.limelight.nvstream.Ds5HapticsPcmFrame(0, 1, 0, 0, 4000, 40, 2, 16, ByteArray(160)))
            SystemClock.sleep(100)
            assertTrue("Rumble only must ignore authored PCM", !sink.playbackActive)
            sink.submitRumble(0.5f, 1f)
            awaitPlayback(true)
            sink.submitRumble(0f, 0f)
            awaitPlayback(false)
            sink.previewBoth()
            awaitPlayback(true)
            SystemClock.sleep(100)
            assertTrue("Preview ended before 250 ms", sink.isTesting)
            SystemClock.sleep(250)
            assertTrue("Preview did not expire", !sink.isTesting)
            awaitPlayback(false)
        } finally {
            sink.stopAndThen { stopped.countDown() }
            assertTrue(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(sink.releaseFailure == null)
    }

    @Test fun testIndependentSensa() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiIndependentPulse") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.single { it.vendorId == 0x1532 && it.productId == 0x0727 }
        assertTrue(manager.hasPermission(device))
        val iface = (0 until device.interfaceCount).map(device::getInterface).single { it.id == 4 }
        val sink = KishiSensaHapticsSink(manager, device, iface,
            (0 until iface.endpointCount).map(iface::getEndpoint).single { it.address == 4 },
            strength = { 1.0 }, frequency = { 100.0 }, conversionEnabled = { true }, pcmEnabled = { true }) {
            Log.i("KishiDescriptors", "Independent Sensa state=$it")
        }
        val stopped = java.util.concurrent.CountDownLatch(1)
        try {
            assertTrue("Sensa initialization failed", sink.start())
            sink.testChannels()
            SystemClock.sleep(3200)
            assertTrue("Sensa transport failed during pulse", sink.isOperational)
            assertTrue("Channel test did not stop", !sink.isTesting)
        } finally {
            sink.stopAndThen { stopped.countDown() }
            assertTrue("Sensa cleanup timed out", stopped.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        check(sink.releaseFailure == null) { "Sensa release failed: ${sink.releaseFailure}" }
    }

    @Test fun readReportDescriptors() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiDescriptors") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.single { it.vendorId == 0x1532 && it.productId == 0x0727 }
        assertTrue("Grant Moonlight USB permission first", manager.hasPermission(device))
        val connection = checkNotNull(manager.openDevice(device))
        try {
            val raw = connection.rawDescriptors
            Log.i("KishiDescriptors", "USB=" + raw.joinToString(" ") { "%02x".format(it.toInt() and 255) })
            var offset = 0
            var interfaceId = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 255
                val type = raw[offset + 1].toInt() and 255
                check(length >= 2 && offset + length <= raw.size)
                if (type == 4 && length >= 9) interfaceId = raw[offset + 2].toInt() and 255
                if (type == 0x21 && length >= 9 && (interfaceId == 3 || interfaceId == 4)) {
                    val reportLength = (raw[offset + 7].toInt() and 255) or
                        ((raw[offset + 8].toInt() and 255) shl 8)
                    check(reportLength in 1..4096)
                    val report = ByteArray(reportLength)
                    val iface = (0 until device.interfaceCount).map(device::getInterface).single { it.id == interfaceId }
                    val claimed = interfaceId == 4 && connection.claimInterface(iface, true)
                    val count = try {
                        connection.controlTransfer(0x81, 6, 0x2200, interfaceId, report, report.size, 1000)
                    } finally {
                        if (claimed) connection.releaseInterface(iface)
                    }
                    Log.i("KishiDescriptors", "interface=$interfaceId expected=$reportLength result=$count report=" +
                        report.take(count.coerceAtLeast(0)).joinToString(" ") { "%02x".format(it.toInt() and 255) })
                    assertTrue("Report descriptor read failed for interface $interfaceId", count == reportLength)
                }
                offset += length
            }
        } finally {
            connection.close()
        }
    }
}
