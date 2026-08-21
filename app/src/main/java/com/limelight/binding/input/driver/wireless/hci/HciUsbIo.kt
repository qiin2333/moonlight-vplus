package com.limelight.binding.input.driver.wireless.hci

import java.io.Closeable

internal enum class HciUsbInputChannel {
    EVENT,
    ACL
}

internal sealed class HciUsbReadResult {
    class Packet(
        val channel: HciUsbInputChannel,
        val bytes: ByteArray
    ) : HciUsbReadResult()

    object Timeout : HciUsbReadResult()
    object Closed : HciUsbReadResult()

    class Failure(val failure: HciTransportFailure) : HciUsbReadResult()
}

/** Testable I/O seam around Android's UsbDeviceConnection and UsbRequest APIs. */
internal interface HciUsbIo : Closeable {
    fun open(): Boolean
    fun read(): HciUsbReadResult
    fun sendCommand(encodedCommand: ByteArray): Boolean
    fun sendAcl(encodedPacket: ByteArray): Boolean
    override fun close()
}
