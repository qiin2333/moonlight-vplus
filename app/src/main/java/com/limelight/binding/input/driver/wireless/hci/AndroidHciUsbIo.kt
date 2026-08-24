package com.limelight.binding.input.driver.wireless.hci

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one opened UsbDeviceConnection and the claimed standard Bluetooth HCI interface. */
internal class AndroidHciUsbIo(
    private val connection: UsbDeviceConnection,
    private val descriptor: HciUsbDeviceDescriptor
) : HciUsbIo {
    private val endpoints = descriptor.endpoints

    private class InputSlot(
        val channel: HciUsbInputChannel,
        val endpoint: UsbEndpoint,
        capacity: Int
    ) {
        val request = UsbRequest()
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacity)
    }

    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val requestsClosed = AtomicBoolean(false)
    private val eventSlot = InputSlot(
        HciUsbInputChannel.EVENT,
        endpoints.eventIn,
        if (descriptor.behavior.eventInputUsesEndpointPacketSize) {
            endpoints.eventIn.maxPacketSize.coerceAtLeast(HCI_EVENT_HEADER_LENGTH)
        } else {
            MAX_EVENT_TRANSFER_LENGTH
        }
    )
    private val aclSlots = List(descriptor.behavior.aclInputRequestCount) {
        InputSlot(
            HciUsbInputChannel.ACL,
            endpoints.aclIn,
            MAX_ACL_TRANSFER_LENGTH
        )
    }
    private val inputSlots = listOf(eventSlot) + aclSlots

    override fun open(): Boolean {
        if (!opened.compareAndSet(false, true)) {
            return !closed.get()
        }

        if (!connection.claimInterface(endpoints.usbInterface, true)) {
            close()
            return false
        }

        if (!inputSlots.all(::initializeAndQueue)) {
            close()
            return false
        }
        return true
    }

    override fun read(): HciUsbReadResult {
        if (!opened.get() || closed.get()) {
            return HciUsbReadResult.Closed
        }

        val completed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connection.requestWait(READ_WAIT_TIMEOUT_MS)
            } else {
                connection.requestWait()
            }
        } catch (_: TimeoutException) {
            return HciUsbReadResult.Timeout
        } catch (e: RuntimeException) {
            return if (closed.get()) {
                HciUsbReadResult.Closed
            } else {
                HciUsbReadResult.Failure(
                    HciTransportFailure(
                        HciTransportErrorCode.EVENT_TRANSFER_FAILED,
                        "USB requestWait failed",
                        e
                    )
                )
            }
        }

        if (completed == null) {
            return if (closed.get()) HciUsbReadResult.Closed else HciUsbReadResult.Timeout
        }

        val slot = inputSlots.firstOrNull { it.request === completed }
            ?: return HciUsbReadResult.Failure(
                HciTransportFailure(
                    HciTransportErrorCode.EVENT_TRANSFER_FAILED,
                    "Unexpected USB request completion"
                )
            )

        val transferred = slot.buffer.position().coerceIn(0, slot.buffer.capacity())
        slot.buffer.flip()
        val bytes = ByteArray(transferred)
        slot.buffer.get(bytes)
        slot.buffer.clear()

        if (!closed.get() && !queue(slot)) {
            return HciUsbReadResult.Failure(
                HciTransportFailure(
                    if (slot.channel == HciUsbInputChannel.EVENT) {
                        HciTransportErrorCode.EVENT_TRANSFER_FAILED
                    } else {
                        HciTransportErrorCode.ACL_TRANSFER_FAILED
                    },
                    "Unable to requeue USB ${slot.channel.name.lowercase()} request"
                )
            )
        }

        return if (closed.get()) {
            HciUsbReadResult.Closed
        } else if (bytes.isEmpty()) {
            // Some controllers complete a request with no payload during reset or cancellation.
            HciUsbReadResult.Timeout
        } else {
            HciUsbReadResult.Packet(slot.channel, bytes)
        }
    }

    override fun sendCommand(encodedCommand: ByteArray): Boolean {
        if (closed.get()) {
            return false
        }
        for (recipient in descriptor.behavior.commandRecipients) {
            val requestType = when (recipient) {
                HciUsbCommandRecipient.DEVICE -> HCI_DEVICE_COMMAND_REQUEST_TYPE
                HciUsbCommandRecipient.INTERFACE -> HCI_INTERFACE_COMMAND_REQUEST_TYPE
            }
            val index = when (recipient) {
                HciUsbCommandRecipient.DEVICE -> 0
                HciUsbCommandRecipient.INTERFACE -> endpoints.usbInterface.id
            }
            if (connection.controlTransfer(
                    requestType,
                    HCI_COMMAND_REQUEST,
                    0,
                    index,
                    encodedCommand,
                    encodedCommand.size,
                    OUTPUT_TIMEOUT_MS
                ) == encodedCommand.size
            ) {
                return true
            }
        }
        return false
    }

    override fun sendAcl(encodedPacket: ByteArray): Boolean {
        if (closed.get()) {
            return false
        }
        return connection.bulkTransfer(
            endpoints.aclOut,
            encodedPacket,
            encodedPacket.size,
            OUTPUT_TIMEOUT_MS
        ) == encodedPacket.size
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        inputSlots.forEach { runCatching { it.request.cancel() } }
        if (opened.get()) {
            runCatching { connection.releaseInterface(endpoints.usbInterface) }
        }
        runCatching { connection.close() }
    }

    override fun finishClose() {
        if (!requestsClosed.compareAndSet(false, true)) return
        inputSlots.forEach { runCatching { it.request.close() } }
    }

    private fun initializeAndQueue(slot: InputSlot): Boolean {
        return slot.request.initialize(connection, slot.endpoint) && queue(slot)
    }

    private fun queue(slot: InputSlot): Boolean {
        slot.buffer.clear()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            slot.request.queue(slot.buffer)
        } else {
            @Suppress("DEPRECATION")
            slot.request.queue(slot.buffer, slot.buffer.capacity())
        }
    }

    companion object {
        private const val HCI_DEVICE_COMMAND_REQUEST_TYPE = 0x20
        private const val HCI_INTERFACE_COMMAND_REQUEST_TYPE = 0x21
        private const val HCI_COMMAND_REQUEST = 0x00
        private const val MAX_EVENT_TRANSFER_LENGTH = 2 + HciPacketCodec.MAX_COMMAND_PARAMETERS
        private const val HCI_EVENT_HEADER_LENGTH = 2
        private const val MAX_ACL_TRANSFER_LENGTH = 8192
        private const val READ_WAIT_TIMEOUT_MS = 500L
        private const val OUTPUT_TIMEOUT_MS = 1000
    }
}
