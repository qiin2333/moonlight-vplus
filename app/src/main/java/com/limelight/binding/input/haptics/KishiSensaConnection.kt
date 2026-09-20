package com.limelight.binding.input.haptics

import android.hardware.usb.*
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Transport for the XL's dedicated Sensa HID interface, not its gamepad input interface.
 * One output worker owns both requests and consumes each command's echo before sending
 * another command. A successful USB write alone does not prove that firmware accepted it.
 * Initialization validates the device-reported layout before enabling design-mode output;
 * a firmware with different actuator or band semantics must fail instead of being guessed.
 */
@android.annotation.SuppressLint("NewApi")
internal class KishiSensaConnection(private val usb: UsbDeviceConnection, private val iface: UsbInterface) : AutoCloseable {
    private val input = UsbRequest()
    private val output = UsbRequest()
    private var claimed = false
    private var originalMode: Int? = null
    private var modeChanged = false

    fun initialize() {
        // Force-claim only the already matched haptics interface if Android owns it.
        // Claiming the input interface here would disconnect ordinary controller input.
        claimed = usb.claimInterface(iface, false) || usb.claimInterface(iface, true)
        check(claimed) { "Sensa interface busy" }
        val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
        check(input.initialize(usb, endpoints.single { it.address == 0x84 }))
        check(output.initialize(usb, endpoints.single { it.address == 0x04 }))
        // Metadata length and chunk offsets are big-endian. Keep chunks below the
        // 64-byte report limit, including the echoed offset/count and framing bytes.
        val size = exchange(0x90, byteArrayOf(0, 0))
        check(size.size == 2)
        val length = ((size[0].toInt() and 255) shl 8) or (size[1].toInt() and 255)
        check(length in 1..4096) { "Invalid Sensa metadata size" }
        val metadata = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = minOf(50, length - offset)
            val reply = exchange(0x91, byteArrayOf((offset shr 8).toByte(), offset.toByte(), count.toByte(), 0, 0))
            check(reply.size == count + 3 && reply[0] == (offset shr 8).toByte() &&
                reply[1] == offset.toByte() && reply[2] == count.toByte()) { "Invalid Sensa metadata chunk" }
            reply.copyInto(metadata, offset, 3)
            offset += count
        }
        val json = JSONObject(metadata.toString(Charsets.UTF_8).trimEnd('\u0000'))
        val bodies = json.getJSONArray("Bodypart")
        check(bodies.length() == 2 && json.getInt("StreamBodyType") == 1) { "Unsupported Sensa body layout" }
        for (index in 0..1) {
            val body = bodies.getJSONObject(index)
            check(body.getInt("BodypartID") == if (index == 0) 216 else 116)
            val stream = body.getJSONObject("StreamCharacteristics")
            check(stream.getInt("Bands") == 3 && stream.getInt("Points") == 4 && stream.getInt("Transients") == 2)
            val values = body.getJSONObject("Characteristics").getJSONArray("ValueReport")
            check(values.length() == 1 && values.getJSONObject(0).getInt("FrequencyMin") == 30 &&
                values.getJSONObject(0).getInt("FrequencyMax") == 400) { "Unsupported Sensa frequency range" }
        }
        // Mode 0 accepts designed Sensa effects; mode 2 is the firmware's ERM mode.
        // Remember the previous mode so another app can resume after our session ends.
        val mode = exchange(0x87, byteArrayOf(0))
        check(mode.size == 1 && mode[0].toInt() in listOf(0, 2)) { "Unsupported Sensa mode" }
        originalMode = mode[0].toInt()
        if (mode[0] != 0.toByte()) {
            modeChanged = true // Restore even if the write succeeds but its acknowledgement is lost.
            check(exchange(7, byteArrayOf(0)).contentEquals(byteArrayOf(0)))
        }
        write(KishiSensaPacket.silence())
    }

    private fun exchange(command: Int, payload: ByteArray): ByteArray = transfer(KishiSensaPacket.report(command, payload))

    fun write(report: ByteArray) {
        val reply = transfer(report)
        check(reply.contentEquals(report.copyOfRange(5, (report[1].toInt() and 255) + 1))) { "Sensa stream acknowledgement mismatch" }
    }

    private fun transfer(report: ByteArray): ByteArray {
        // Arm IN before OUT to catch immediate acknowledgements. Both completions
        // share one deadline, including any unrelated replies discarded below.
        val incoming = ByteBuffer.allocateDirect(64)
        val outgoing = ByteBuffer.allocateDirect(64).apply { put(report); flip() }
        check(input.queue(incoming))
        check(output.queue(outgoing))
        val deadline = SystemClock.elapsedRealtime() + 150
        var sent = false
        var reply: ByteArray? = null
        while (!sent || reply == null) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Sensa response timeout" }
            when (usb.requestWait(remaining)) {
                output -> { check(outgoing.position() == 64); sent = true }
                input -> {
                    val count = incoming.position()
                    incoming.flip()
                    val bytes = ByteArray(count).also(incoming::get)
                    check(count >= 6 && bytes[0] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) { "Malformed Sensa reply" }
                    val length = bytes[1].toInt() and 255
                    check(length in 5 until count)
                    if (bytes[4] == report[4]) reply = bytes.copyOfRange(5, length + 1)
                    else { incoming.clear(); check(input.queue(incoming)) }
                }
                else -> error("Sensa USB request failed")
            }
        }
        return checkNotNull(reply)
    }

    override fun close() {
        // Failed asynchronous requests cannot be reused for cleanup. Cancel them first,
        // then attempt bounded writes; release/close still run if restoration fails.
        input.cancel(); output.cancel()
        input.close(); output.close()
        try {
            if (claimed && originalMode != null) {
                val endpoint = (0 until iface.endpointCount).map(iface::getEndpoint).single { it.address == 4 }
                // Bounded best-effort silence and restoration also work after request failures.
                val silenced = usb.bulkTransfer(endpoint, KishiSensaPacket.silence(), 64, 150) == 64
                if (modeChanged) {
                    val mode = KishiSensaPacket.report(7, byteArrayOf(checkNotNull(originalMode).toByte()))
                    check(usb.bulkTransfer(endpoint, mode, 64, 150) == 64) { "Sensa mode restore failed" }
                }
                check(silenced) { "Sensa stop failed" }
            }
        } finally {
            try { if (claimed) check(usb.releaseInterface(iface)) { "Sensa interface release failed" } }
            finally { usb.close() }
        }
    }
}
