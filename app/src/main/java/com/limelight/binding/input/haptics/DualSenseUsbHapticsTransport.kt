package com.limelight.binding.input.haptics

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import java.nio.ByteBuffer

/** The pump owns completions; cancellation never frees buffers still owned by the kernel. */
internal interface DualSenseUsbHapticsTransport {
    fun open(slots: Int, packetSize: Int): Boolean
    fun submit(slot: Int, buffer: ByteBuffer): Int
    fun reap(timeoutMs: Int): Int
    fun cancel()
    fun close(): Int
    fun restoreInterface(): Boolean
}

internal class AndroidDualSenseUsbHapticsTransport(
    private val connection: UsbDeviceConnection,
    private val streamingInterface: UsbInterface,
    private val endpoint: UsbEndpoint
) : DualSenseUsbHapticsTransport {
    private val lock = Any()
    @Volatile private var handle = 0L
    private var selected = false

    override fun open(slots: Int, packetSize: Int): Boolean {
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_ISOC || endpoint.maxPacketSize < packetSize ||
            !connection.setInterface(streamingInterface)) return false
        selected = true
        configureUac()
        synchronized(lock) {
            handle = MoonBridge.createUsbIsochronousOutput(connection.fileDescriptor, endpoint.address, slots, packetSize)
        }
        return handle != 0L
    }

    override fun submit(slot: Int, buffer: ByteBuffer): Int =
        MoonBridge.submitUsbIsochronousOutput(handle, slot, buffer)

    override fun reap(timeoutMs: Int): Int = MoonBridge.reapUsbIsochronousOutput(handle, timeoutMs)

    override fun cancel() = synchronized(lock) {
        if (handle != 0L) MoonBridge.cancelUsbIsochronousOutput(handle)
    }

    override fun close(): Int = synchronized(lock) {
        if (handle == 0L) return@synchronized 0
        MoonBridge.closeUsbIsochronousOutput(handle).also { if (it == 0) handle = 0 }
    }

    override fun restoreInterface(): Boolean {
        if (!selected) return true
        // Standard SET_INTERFACE, alt 0: release the audio endpoint's reserved bandwidth.
        return (connection.controlTransfer(0x01, 0x0B, 0, streamingInterface.id, null, 0, 100) >= 0)
            .also { if (it) selected = false }
    }

    private fun configureUac() {
        val rate = byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0) // 48 kHz LE
        // UAC SET_CUR / sampling frequency; some devices use the interface recipient.
        var result = connection.controlTransfer(0x22, 1, 0x0100, endpoint.address, rate, 3, 100)
        if (result < 0) result = connection.controlTransfer(0x21, 1, 0x0100, streamingInterface.id, rate, 3, 100)
        if (result < 0) LimeLog.warning("DS5 USB sampling-rate SET_CUR rejected; using default")
        // Speaker feature unit 2, control interface 0, master volume 0 dB.
        connection.controlTransfer(0x21, 1, 0x0200, 0x0200, byteArrayOf(0, 0), 2, 100)
    }
}
