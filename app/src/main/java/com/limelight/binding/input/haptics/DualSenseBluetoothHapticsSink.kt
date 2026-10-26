package com.limelight.binding.input.haptics

import com.limelight.LimeLog
import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.Ds5HapticsState
import java.util.concurrent.locks.LockSupport

/** A connection-scoped PCM pump. The connection's existing writer owns every physical write. */
internal class DualSenseBluetoothHapticsSink(
    private val ready: () -> Boolean,
    private val write: (ByteArray) -> Boolean,
    private val pause: () -> Boolean,
    private val onFailure: () -> Unit = {},
    private val control: (Ds5HapticsState) -> Unit = {},
    private val release: () -> Boolean = pause
) : DualSenseNativeHapticsSink {
    private data class Packet(val samples: ByteArray, val receivedAt: Long)
    private val lock = Any()
    private val encoder = DualSenseBluetoothPcmEncoder()
    private val queue = ArrayDeque<Packet>()
    private val callbacks = mutableListOf<() -> Unit>()
    private var sequence: Int? = null
    private var lastInput = 0L
    private var ended = false
    @Volatile private var active = false
    @Volatile private var stopping = false
    private var finished = false
    private var started = false
    private var startupFailed = false
    private var worker: Thread? = null
    private var sent = 0L
    private var dropped = 0L
    private var underruns = 0L

    override val isOperational: Boolean get() = active && !stopping

    override fun updateControl(state: Ds5HapticsState) = synchronized(lock) {
        if (!stopping && !finished) control(state)
    }

    override fun start(): Boolean = synchronized(lock) {
        if (started || stopping) return@synchronized isOperational
        started = true
        if (!runCatching(ready).getOrDefault(false)) {
            startupFailed = true
            return@synchronized false
        }
        active = true
        worker = Thread(::run, "DualSenseBtPcm").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        true
    }

    override fun submit(frame: Ds5HapticsPcmFrame) {
        synchronized(lock) {
            if (!isOperational || !encoder.accepts(frame)) return
            val flags = frame.flags.toInt()
            val restart = flags and 5 != 0
            if (!restart && sequence?.let { frame.sequenceNumber - it <= 0 } == true) return
            val now = System.nanoTime()
            // Missing unreliable PCM is a gap, not a new stream; retain queued audio and FIR history.
            if (restart || lastInput != 0L && now - lastInput > IDLE_NS) {
                queue.clear()
                encoder.reset()
            }
            sequence = frame.sequenceNumber
            lastInput = now
            ended = flags and 2 != 0
            encoder.encode(frame) { enqueue(it, now) }
            if (ended) encoder.finish { enqueue(it, now) }
        }
        worker?.let(LockSupport::unpark)
    }

    private fun enqueue(samples: ByteArray, now: Long) {
        while (queue.size >= 3) { queue.removeFirst(); dropped++ }
        queue.addLast(Packet(samples, now))
    }

    override fun stop() = stopAndThen {}

    override fun stopAndThen(onStopped: () -> Unit) {
        val immediate = synchronized(lock) {
            if (finished) true else {
                callbacks.add(onStopped)
                stopping = true
                active = false
                queue.clear()
                if (worker == null) {
                    worker = Thread(::run, "DualSenseBtPcmStop").apply { start() }
                }
                false
            }
        }
        if (immediate) onStopped() else worker?.let(LockSupport::unpark)
    }

    private fun run() {
        var deadline = 0L
        var playing = false
        var failed = startupFailed
        try {
            while (!stopping) {
                val now = System.nanoTime()
                if (deadline > now) { LockSupport.parkNanos(minOf(deadline - now, FLUSH_NS)); continue }
                val packet = synchronized(lock) {
                    if (encoder.hasPendingSamples && now - lastInput >= FLUSH_NS) {
                        encoder.finish { enqueue(it, lastInput) }
                    }
                    while (queue.isNotEmpty() && now - queue.first().receivedAt > MAX_AGE_NS) {
                        queue.removeFirst(); dropped++
                    }
                    queue.removeFirstOrNull()?.samples ?: if (playing && !ended &&
                        lastInput != 0L && now - lastInput < IDLE_NS) {
                        underruns++
                        ByteArray(64)
                    } else null
                }
                if (packet == null) {
                    if (playing && !pause()) error("Native haptics mode release failed")
                    playing = false
                    deadline = 0L
                    LockSupport.parkNanos(FLUSH_NS)
                    continue
                }
                if (stopping) break
                if (!write(packet)) error("Native haptics report rejected")
                sent++
                playing = true
                // Never burst old packets to catch up with a blocked Android HID service.
                deadline = if (deadline == 0L) now + PERIOD_NS else deadline + PERIOD_NS
                val after = System.nanoTime()
                if (deadline <= after) deadline = after + PERIOD_NS
            }
        } catch (failure: Throwable) {
            active = false
            stopping = true
            LimeLog.warning("DualSense Bluetooth PCM failed: ${failure.message}")
            failed = true
        } finally {
            active = false
            runCatching { release() }
            val done = synchronized(lock) {
                stopping = true
                finished = true
                queue.clear()
                encoder.reset()
                callbacks.toList().also { callbacks.clear() }
            }
            LimeLog.info("DualSense Bluetooth PCM stopped: sent=$sent dropped=$dropped underruns=$underruns")
            done.forEach { runCatching(it) }
            // Recovery may create a new sink on the same writer; retire all old transport access first.
            if (failed) runCatching(onFailure)
        }
    }

    companion object {
        private const val PERIOD_NS = 10_666_667L
        private const val FLUSH_NS = 12_000_000L
        private const val IDLE_NS = 40_000_000L
        private const val MAX_AGE_NS = 40_000_000L
    }
}
