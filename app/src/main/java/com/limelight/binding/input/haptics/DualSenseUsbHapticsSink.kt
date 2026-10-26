package com.limelight.binding.input.haptics

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.limelight.LimeLog
import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.Ds5HapticsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

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
 * buffer. A dedicated sender thread keeps [IO_SLOTS] USBFS ISO URBs in flight;
 * the USB controller consumes one packet per 1 ms frame, so the queued lead
 * is the pacing clock. Consumption starts after a 10 ms prebuffer and emits
 * silence on underrun; discontinuity flushes the ring and stream-end drains its tail.
 *
 * ## Android isochronous caveat
 *
 * Android UsbRequest supports only bulk/interrupt. Native USBFS submits genuine
 * isochronous URBs on the already-authorized descriptor. HID uses synchronous
 * transfers, so this pump exclusively owns asynchronous completions on that connection.
 */
internal class DualSenseUsbHapticsSink(
    private val transport: DualSenseUsbHapticsTransport,
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val onControl: (Ds5HapticsState) -> Unit = {}
) : DualSenseNativeHapticsSink {
    constructor(connection: UsbDeviceConnection, streamingInterface: UsbInterface, isoEndpoint: UsbEndpoint,
                onActiveChanged: (Boolean) -> Unit = {}, onControl: (Ds5HapticsState) -> Unit = {}) :
        this(AndroidDualSenseUsbHapticsTransport(connection, streamingInterface, isoEndpoint), onActiveChanged, onControl)
    companion object {

        private const val SAMPLE_RATE = 48000
        private const val SPEAKER_CHANNELS = 2
        private const val HAPTIC_CHANNELS = 2
        private const val CHANNEL_COUNT = SPEAKER_CHANNELS + HAPTIC_CHANNELS
        private const val FRAMES_PER_PACKET = SAMPLE_RATE / 1000 // 48
        private const val PACKET_SIZE = FRAMES_PER_PACKET * CHANNEL_COUNT * 2 // 384

        private const val IO_SLOTS = 4
        private const val PREBUFFER_FRAMES = SAMPLE_RATE / 100 // 10 ms
        private const val RING_FRAMES = SAMPLE_RATE / 10 // 100 ms
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L

    }

    private class IoSlot {
        lateinit var buffer: ByteBuffer
    }

    // Ring of interleaved haptic L/R samples; indices count shorts.
    private val ring = ShortArray(RING_FRAMES * HAPTIC_CHANNELS)
    private var readIndex = 0
    private var writeIndex = 0
    private var prebuffered = false
    private var lastInputNs = 0L

    private val ringLock = Any()
    private val lifecycleLock = Any()
    private val callbackLock = Any()
    private val slots = Array(IO_SLOTS) { IoSlot() }
    private val active = AtomicBoolean(false)
    private val requestsClosed = AtomicBoolean(false)
    @Volatile override var releaseFailure: Throwable? = null
        private set
    private var startAttempted = false
    private var stopStarted = false
    private var stopCompleted = false
    private var stopFinishing = false
    private var stopWaiterStarted = false
    private val stopCallbacks = mutableListOf<() -> Unit>()
    private var sendThread: Thread? = null
    private var formatWarned = false
    private var lastSequence = 0
    private var hasSequence = false

    override val isOperational: Boolean get() = active.get()
    override fun updateControl(state: Ds5HapticsState) = synchronized(callbackLock) {
        val accepting = synchronized(lifecycleLock) { !stopStarted && !stopCompleted }
        if (accepting) onControl(state)
    }

    override fun start(): Boolean {
        val initialized = synchronized(lifecycleLock) {
            if (stopStarted || stopCompleted) return@synchronized false
            if (startAttempted) return@synchronized active.get()
            startAttempted = true
            active.set(true)

            if (!transport.open(IO_SLOTS, PACKET_SIZE)) {
                LimeLog.warning("Could not open DS5 USB isochronous output")
                return@synchronized false
            }
            for ((index, slot) in slots.withIndex()) {
                slot.buffer = ByteBuffer.allocateDirect(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                fillSlot(slot)
                val result = transport.submit(index, slot.buffer)
                if (result != 0) {
                    LimeLog.warning("Initial ISO submit failed: $result")
                    return@synchronized false
                }
            }

            sendThread = Thread({
                LimeLog.info("DS5 USB ISO sender started")
                try {
                    // Serialize HID callbacks, but never hold lifecycleLock across blocking USB I/O.
                    synchronized(callbackLock) {
                        if (synchronized(lifecycleLock) { !stopStarted && !stopCompleted }) {
                            onActiveChanged(true)
                        }
                    }
                    sendLoop()
                } finally {
                    LimeLog.info("DS5 USB ISO sender stopped")
                    completeStop()
                }
            }, "ds5-haptics-iso").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
            true
        }
        if (!initialized) completeStop()
        return initialized
    }

    /**
     * Stops the pump. Fast by design — it may be called from the USB broadcast
     * (main) thread: closing the requests cancels in-flight transfers and wakes
     * the sender, which parks the interface on alt 0 as it exits.
     */
    override fun stop() {
        stopAndThen {}
    }

    override fun stopAndThen(onStopped: () -> Unit) {
        var runImmediately = false
        val thread = synchronized(lifecycleLock) {
            if (stopCompleted) {
                runImmediately = true
                null
            } else {
                stopCallbacks += onStopped
                if (!stopStarted) {
                    stopStarted = true
                    active.set(false)
                    closeRequests()
                }
                sendThread
            }
        }

        if (runImmediately) {
            onStopped()
            return
        }
        if (thread == null) {
            completeStop()
            return
        }
        if (thread === Thread.currentThread()) return

        var interrupted = false
        try {
            thread.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
        if (thread.isAlive) {
            startStopWaiter(thread)
        } else {
            completeStop()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun startStopWaiter(thread: Thread) {
        val shouldStart = synchronized(lifecycleLock) {
            if (stopCompleted || stopWaiterStarted) {
                false
            } else {
                stopWaiterStarted = true
                true
            }
        }
        if (!shouldStart) return

        LimeLog.warning("DS5 ISO sender stop timed out; deferring USB transport close")
        Thread({
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            completeStop()
            if (interrupted) Thread.currentThread().interrupt()
        }, "ds5-haptics-stop").apply {
            isDaemon = true
            start()
        }
    }

    private fun completeStop() {
        synchronized(lifecycleLock) {
            if (stopCompleted || stopFinishing) return
            stopFinishing = true
            stopStarted = true
            active.set(false)
        }
        closeRequests()
        releaseRequests()
        runCatching { synchronized(callbackLock) { onActiveChanged(false) } }.onFailure {
            LimeLog.warning("Haptics output clear failed: ${it.message}")
        }
        val callbacks = synchronized(lifecycleLock) {
            // Completion includes the final HID callback, not just retirement of ISO buffers.
            stopCompleted = true
            sendThread = null
            synchronized(ringLock) {
                readIndex = 0
                writeIndex = 0
                prebuffered = false
            }
            hasSequence = false
            stopCallbacks.toList().also { stopCallbacks.clear() }
        }
        callbacks.forEach { callback ->
            runCatching(callback).onFailure {
                LimeLog.warning("Haptics stop callback failed: ${it.message}")
            }
        }
    }

    private fun closeRequests() {
        if (!requestsClosed.compareAndSet(false, true)) return
        runCatching { transport.cancel() }.onFailure {
            releaseFailure = it
            LimeLog.warning("ISO cancellation failed: ${it.message}")
        }
    }

    private fun releaseRequests() {
        runCatching {
            val result = transport.close()
            if (result != 0) {
                releaseFailure = IllegalStateException("ISO release failed: $result")
                LimeLog.warning("ISO buffers retained after uncertain release: $result")
                return@runCatching
            }
            if (!transport.restoreInterface()) {
                releaseFailure = IllegalStateException("Failed to restore DS5 audio interface alt 0")
                LimeLog.warning(releaseFailure!!.message!!)
            }
        }.onFailure {
            releaseFailure = it
            LimeLog.warning("ISO retirement failed: ${it.message}")
        }
    }

    /** Enqueues an authored PCM frame. Called on the control receive thread. */
    override fun submit(frame: Ds5HapticsPcmFrame) {
        if (!active.get()) return
        if (frame.sampleRate != SAMPLE_RATE ||
            frame.channelCount.toInt() != HAPTIC_CHANNELS ||
            frame.bitsPerSample.toInt() != 16 || frame.frameCount !in 0..480 ||
            frame.pcm.size != frame.frameCount * 4 || frame.flags.toInt() and 7.inv() != 0
        ) {
            if (!formatWarned) {
                LimeLog.warning("Unsupported PCM format: ${frame.sampleRate}Hz/" +
                        "${frame.channelCount}ch/${frame.bitsPerSample}bit")
                formatWarned = true
            }
            return
        }

        val flags = frame.flags.toInt()
        val flushMask = Ds5HapticsPcmFrame.FLAG_STREAM_START.toInt() or
                Ds5HapticsPcmFrame.FLAG_DISCONTINUITY.toInt()
        if (flags and flushMask != 0) {
            synchronized(ringLock) {
                readIndex = 0
                writeIndex = 0
                prebuffered = false
            }
            hasSequence = false
        }
        val streamEnded = flags and Ds5HapticsPcmFrame.FLAG_STREAM_END.toInt() != 0
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
            lastInputNs = System.nanoTime()
            if (streamEnded) prebuffered = true
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
            while (active.get()) {
                val done = transport.reap(500)
                if (done == -110) continue // ETIMEDOUT
                if (!active.get()) return
                if (done !in slots.indices) { LimeLog.warning("ISO completion failed: $done"); return }
                val slot = slots[done]
                fillSlot(slot)
                val result = transport.submit(done, slot.buffer)
                if (result != 0) {
                    LimeLog.warning("ISO submit failed: $result")
                    active.set(false)
                    return
                }
            }
        } finally {
            active.set(false)
        }
    }

    private fun fillSlot(slot: IoSlot) {
        val buffer = slot.buffer
        buffer.rewind()
        synchronized(ringLock) {
            if (!prebuffered && (bufferedFrames() >= PREBUFFER_FRAMES ||
                bufferedFrames() > 0 && System.nanoTime() - lastInputNs >= 8_000_000L)) {
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

}
