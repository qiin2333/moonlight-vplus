package com.limelight.binding.input.haptics

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import com.limelight.nvstream.Ds5HapticsPcmFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

/**
 * Renders authored DualSense haptics PCM into the UAC isochronous OUT endpoint
 * of a physical DualSense controller connected over USB.
 *
 * ## USB audio topology (per the HIDMaestro byte-exact DualSense profile)
 *
 * Interface 1 (audioStreamingOut) alt 1: 4 channels, 16-bit, 48 kHz;
 * channel roles [speakerLeft, speakerRight, hapticLeft, hapticRight];
 * isochronous OUT endpoint 0x01, adaptive sync, maxPacket 392, 1 ms interval.
 * Payload is raw interleaved PCM (UAC 1.0 carries no per-packet header);
 * 48 frames x 4 ch x 2 B = 384 bytes per packet, speaker lanes silent.
 *
 * ## Pipeline
 *
 * [submit] runs on the common-c control receive thread and appends to a ring
 * buffer. A dedicated sender thread keeps [IO_SLOTS] UsbRequests in flight;
 * the USB controller consumes one packet per 1 ms frame, so the queued lead
 * is the pacing clock. Consumption starts after a 10 ms prebuffer and emits
 * silence on underrun; DISCONTINUITY/STREAM_END flush the ring.
 *
 * ## Android isochronous caveat
 *
 * UsbRequest on isochronous endpoints is not reliable on every ROM. A failed
 * initialize/queue parks the pump and logs; teardown still restores alt 0.
 */
internal class DualSenseUsbHapticsSink(
    private val connection: UsbDeviceConnection,
    private val streamingInterface: UsbInterface,
    private val isoEndpoint: UsbEndpoint
) : DualSenseNativeHapticsSink {
    companion object {
        private const val TAG = "DualSenseUsbHaptics"

        private const val SAMPLE_RATE = 48000
        private const val SPEAKER_CHANNELS = 2
        private const val HAPTIC_CHANNELS = 2
        private const val CHANNEL_COUNT = SPEAKER_CHANNELS + HAPTIC_CHANNELS
        private const val FRAMES_PER_PACKET = SAMPLE_RATE / 1000 // 48
        private const val PACKET_SIZE = FRAMES_PER_PACKET * CHANNEL_COUNT * 2 // 384

        private const val IO_SLOTS = 4
        private const val PREBUFFER_FRAMES = SAMPLE_RATE / 100 // 10 ms
        private const val RING_FRAMES = SAMPLE_RATE / 10 // 100 ms

        // UAC 1.0 control requests
        private const val UAC_REQTYPE_INTERFACE_SET = 0x21
        private const val UAC_REQTYPE_ENDPOINT_SET = 0x22
        private const val UAC_SET_CUR = 0x01
        private const val UAC_CS_SAM_FREQ = 0x01
        private const val UAC_CS_VOLUME = 0x02
        private const val AUDIO_CONTROL_INTERFACE = 0
        private const val UAC_FEATURE_UNIT_SPEAKER = 2

        // Standard SET_INTERFACE for restoring bandwidth-less alt 0 on stop.
        private const val USB_REQTYPE_INTERFACE_SET = 0x01
        private const val USB_REQ_SET_INTERFACE = 0x0B
    }

    private class IoSlot {
        val request = UsbRequest()
        lateinit var buffer: ByteBuffer
    }

    // Ring of interleaved haptic L/R samples; indices count shorts.
    private val ring = ShortArray(RING_FRAMES * HAPTIC_CHANNELS)
    private var readIndex = 0
    private var writeIndex = 0
    private var prebuffered = false

    private val ringLock = Any()
    private val slots = Array(IO_SLOTS) { IoSlot() }
    private val active = AtomicBoolean(false)
    private val requestsClosed = AtomicBoolean(false)
    private var sendThread: Thread? = null
    private var formatWarned = false
    private var lastSequence = 0
    private var hasSequence = false

    override fun start(): Boolean {
        if (!active.compareAndSet(false, true)) return active.get()

        if (isoEndpoint.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            Log.w(TAG, "Endpoint is not isochronous; pump disabled")
            active.set(false)
            return false
        }

        // Select alt 1 with the iso endpoint. The passed UsbInterface comes
        // from endpoint enumeration, which only surfaces non-zero alts.
        if (!connection.setInterface(streamingInterface)) {
            Log.w(TAG, "setInterface(alt 1) failed; pump disabled")
            active.set(false)
            return false
        }

        configureUac()

        for (slot in slots) {
            slot.buffer = ByteBuffer.allocateDirect(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            if (!slot.request.initialize(connection, isoEndpoint)) {
                Log.w(TAG, "UsbRequest.initialize failed (iso unsupported on this ROM?)")
                active.set(false)
                shutdownUnstarted()
                return false
            }
        }

        sendThread = Thread({
            Log.i(TAG, "Iso sender started")
            try {
                sendLoop()
            } finally {
                Log.i(TAG, "Iso sender stopped")
            }
        }, "ds5-haptics-iso").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        return true
    }

    /**
     * Stops the pump. Fast by design — it may be called from the USB broadcast
     * (main) thread: closing the requests cancels in-flight transfers and wakes
     * the sender, which parks the interface on alt 0 as it exits.
     */
    override fun stop() {
        active.set(false)
        closeRequests()
        val thread = sendThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        sendThread = null
        synchronized(ringLock) {
            readIndex = 0
            writeIndex = 0
            prebuffered = false
        }
        hasSequence = false
    }

    private fun closeRequests() {
        if (!requestsClosed.compareAndSet(false, true)) return
        slots.forEach { it.request.close() }
    }

    /** Cleanup for the start() failure paths, where the sender never ran. */
    private fun shutdownUnstarted() {
        closeRequests()
        restoreAlt0()
    }

    private fun restoreAlt0() {
        val result = connection.controlTransfer(
            USB_REQTYPE_INTERFACE_SET, USB_REQ_SET_INTERFACE,
            0, streamingInterface.id, null, 0, 100
        )
        if (result < 0) {
            Log.w(TAG, "Failed to restore audio interface alt 0; iso bandwidth may stay reserved")
        }
    }

    /** Enqueues an authored PCM frame. Called on the control receive thread. */
    override fun submit(frame: Ds5HapticsPcmFrame) {
        if (!active.get()) return
        if (frame.sampleRate != SAMPLE_RATE ||
            frame.channelCount.toInt() != HAPTIC_CHANNELS ||
            frame.bitsPerSample.toInt() != 16
        ) {
            if (!formatWarned) {
                Log.w(TAG, "Unsupported PCM format: ${frame.sampleRate}Hz/" +
                        "${frame.channelCount}ch/${frame.bitsPerSample}bit")
                formatWarned = true
            }
            return
        }

        val flags = frame.flags.toInt()
        val flushMask = Ds5HapticsPcmFrame.FLAG_STREAM_END.toInt() or
                Ds5HapticsPcmFrame.FLAG_DISCONTINUITY.toInt()
        if (flags and flushMask != 0) {
            synchronized(ringLock) {
                readIndex = 0
                writeIndex = 0
                prebuffered = false
            }
            hasSequence = false
            if (flags and Ds5HapticsPcmFrame.FLAG_STREAM_END.toInt() != 0) {
                return
            }
        }
        if (flags and Ds5HapticsPcmFrame.FLAG_STREAM_START.toInt() != 0) {
            hasSequence = false
        }

        // The control stream delivers these frames unreliably: drops and
        // reordering are expected. Stale or duplicate sequences are discarded;
        // a gap just means lost PCM, which appends seamlessly.
        if (hasSequence && frame.sequenceNumber - lastSequence <= 0) {
            return
        }
        lastSequence = frame.sequenceNumber
        hasSequence = true

        val source = ByteBuffer.wrap(frame.pcm).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = (frame.pcm.size / 2 / HAPTIC_CHANNELS) * HAPTIC_CHANNELS

        synchronized(ringLock) {
            var remaining = shorts
            while (remaining > 0) {
                // Round the chunk down to a whole L/R frame so writeIndex stays
                // sample-pair aligned.
                var chunk = minOf(remaining, freeShorts())
                chunk -= chunk % HAPTIC_CHANNELS
                if (chunk <= 0) {
                    // Overrun: drop the oldest half of the ring.
                    readIndex = (readIndex + RING_FRAMES * HAPTIC_CHANNELS / 2) % ring.size
                    continue
                }
                var i = 0
                while (i < chunk) {
                    ring[writeIndex] = source.short
                    writeIndex = (writeIndex + 1) % ring.size
                    i++
                }
                remaining -= chunk
            }
        }
    }

    private fun sendLoop() {
        try {
            for (slot in slots) {
                if (!active.get()) return
                fillSlot(slot)
                if (!slot.request.queue(slot.buffer, PACKET_SIZE)) {
                    Log.w(TAG, "Initial iso queue failed; stopping pump")
                    active.set(false)
                    return
                }
            }

            while (active.get()) {
                val done = try {
                    awaitCompletion()
                } catch (_: TimeoutException) {
                    continue
                }
                if (done == null) return
                val slot = slots.firstOrNull { it.request === done } ?: continue
                if (!active.get()) return
                fillSlot(slot)
                if (!slot.request.queue(slot.buffer, PACKET_SIZE)) {
                    Log.w(TAG, "Iso queue failed; stopping pump")
                    active.set(false)
                    return
                }
            }
        } finally {
            active.set(false)
            // The sender is the last user of the endpoint; park it on alt 0 to
            // release the isochronous bandwidth.
            restoreAlt0()
        }
    }

    /**
     * Waits for one request completion. Uses the timeout overload where
     * available (API 26+) so the loop can observe shutdown even if a close
     * does not wake requestWait(); below that, closing a request is the wake.
     */
    private fun awaitCompletion(): UsbRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connection.requestWait(500)
        } else {
            connection.requestWait()
        }

    private fun fillSlot(slot: IoSlot) {
        val buffer = slot.buffer
        buffer.rewind()
        synchronized(ringLock) {
            if (!prebuffered && bufferedFrames() >= PREBUFFER_FRAMES) {
                prebuffered = true
            }
            for (frame in 0 until FRAMES_PER_PACKET) {
                buffer.putShort(0) // speaker left
                buffer.putShort(0) // speaker right
                if (prebuffered && bufferedFrames() > 0) {
                    buffer.putShort(ring[readIndex])
                    buffer.putShort(ring[readIndex + 1])
                    readIndex = (readIndex + 2) % ring.size
                } else {
                    buffer.putShort(0) // haptic left (underrun silence)
                    buffer.putShort(0) // haptic right
                }
            }
        }
    }

    private fun bufferedFrames(): Int = ((writeIndex - readIndex + ring.size) % ring.size) / 2

    private fun freeShorts(): Int {
        val free = (readIndex - writeIndex + ring.size) % ring.size
        // Keep one slot unwritten so full != empty.
        return if (free == 0) ring.size - 1 else free - 1
    }

    /**
     * UAC 1.0 setup: endpoint sample rate 48 kHz, and the speaker feature
     * unit to 0 dB — the controller ships with a near-muted default volume,
     * which would scale the haptic lanes into silence if they route through
     * the feature unit.
     */
    private fun configureUac() {
        val rate = byteArrayOf(0x80.toByte(), 0xBB.toByte(), 0x00.toByte()) // 48000 LE
        var res = connection.controlTransfer(
            UAC_REQTYPE_ENDPOINT_SET, UAC_SET_CUR,
            (UAC_CS_SAM_FREQ shl 8), isoEndpoint.address, rate, 3, 100
        )
        if (res < 0) {
            // Some devices implement the interface-based variant instead.
            res = connection.controlTransfer(
                UAC_REQTYPE_INTERFACE_SET, UAC_SET_CUR,
                (UAC_CS_SAM_FREQ shl 8), streamingInterface.id, rate, 3, 100
            )
        }
        if (res < 0) {
            Log.w(TAG, "Sample-rate SET_CUR rejected; relying on stream default")
        }

        val volume = byteArrayOf(0x00, 0x00) // 0 dB
        connection.controlTransfer(
            UAC_REQTYPE_INTERFACE_SET, UAC_SET_CUR,
            (UAC_CS_VOLUME shl 8), AUDIO_CONTROL_INTERFACE or (UAC_FEATURE_UNIT_SPEAKER shl 8),
            volume, 2, 100
        )
    }
}
