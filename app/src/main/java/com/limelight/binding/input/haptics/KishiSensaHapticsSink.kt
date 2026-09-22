package com.limelight.binding.input.haptics

import android.hardware.usb.*
import android.os.SystemClock
import com.limelight.nvstream.Ds5HapticsPcmFrame
import java.util.ArrayDeque
import java.util.concurrent.locks.LockSupport

/**
 * Serializes Sensa output on one USB worker; callers only update state under [lock].
 * Source priority is local test, fresh authored PCM, then sustained game rumble.
 * PCM is time-sensitive and expires after 30 ms; rumble is a latched motor command
 * that remains valid until replaced or stopped. Neither source owns another USB handle.
 * This backend is XL-specific; the older Kishi PCM transport remains separate.
 */
@android.annotation.SuppressLint("NewApi") // Created only on API 26+, required for bounded requestWait.
internal class KishiSensaHapticsSink(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val iface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val strength: () -> Double = { 1.0 },
    private val frequency: () -> Double = { 100.0 },
    private val conversionEnabled: () -> Boolean = { true },
    private val pcmEnabled: () -> Boolean = { true },
    private val status: (ControllerHapticsCapability) -> Unit
) : WaveformHapticsSink, WaveformPlaybackControl, WaveformChannelTest, WaveformRumbleOutput {
    override val rumbleOutput: WaveformRumbleOutput get() = this
    override val enabled: Boolean get() = conversionEnabled()
    override val isOperational: Boolean get() = ready && !stopping && !finished
    override val playbackControl: WaveformPlaybackControl get() = this
    override val channelTest: WaveformChannelTest get() = this
    private data class Packet(val data: ByteArray, val queuedAt: Long)
    private val lock = Any()
    private val queue = ArrayDeque<Packet>()
    private val encoder = KishiSensaEncoder()
    private val rumbleEncoder = SensaRumbleEncoder()
    private var rumbleLow = 0f
    private var rumbleHigh = 0f
    private var acceptsPcm = true

    private fun refreshPcmMode() {
        // Switching to/from Rumble only must not replay the previous mode's buffered
        // samples. Preserve an explicit user test, which is independent of source mode.
        val next = pcmEnabled()
        if (acceptsPcm == next) return
        acceptsPcm = next
        if (testUntil == 0L) {
            queue.clear()
            encoder.reset()
            sequence = null
            lastInputAt = 0L
            endRequested = true
            epoch++
        }
    }

    override fun submitRumble(low: Float, high: Float) = synchronized(lock) {
        // Retain the latest state even in Haptic only, so enabling conversion while
        // a game is holding a motor on does not require a new host rumble event.
        if (stopping || finished || !low.isFinite() || !high.isFinite()) return@synchronized
        rumbleLow = low.coerceIn(0f, 1f)
        rumbleHigh = high.coerceIn(0f, 1f)
        worker?.let(LockSupport::unpark)
    }
    private val stoppedCallbacks = mutableListOf<() -> Unit>()
    @Volatile private var stopping = false
    @Volatile private var started = false
    @Volatile private var finished = false
    @Volatile override var playbackActive = false
        private set
    override var onPlaybackChanged: ((Boolean) -> Unit)? = null
    private var worker: Thread? = null
    private var sequence: Int? = null
    private var lastInputAt = 0L
    private val initialized = java.util.concurrent.CountDownLatch(1)
    @Volatile private var startupDeadline = 0L
    @Volatile private var ready = false
    @Volatile override var releaseFailure: Throwable? = null
        private set
    @Volatile private var endRequested = false
    private var epoch = 0L
    // Every queue invalidation advances epoch. The worker checks it again after
    // leaving the lock, preventing an already-dequeued obsolete packet from playing.
    @Volatile private var testUntil = 0L
    private var testBoth = false
    override val isTesting: Boolean get() = testUntil > SystemClock.elapsedRealtime()
    override val canTest: Boolean get() = ready && !stopping && !finished
    private var sentPackets = 0L
    private var droppedPackets = 0L
    private var silencePackets = 0L

    override fun testChannels() = beginTest(3000, false)
    override fun previewBoth() = beginTest(250, true)

    private fun beginTest(durationMs: Long, both: Boolean) = synchronized(lock) {
        if (!ready || stopping || finished) return@synchronized
        queue.clear()
        encoder.reset()
        rumbleEncoder.reset()
        sequence = null
        epoch++
        endRequested = false
        testBoth = both
        testUntil = SystemClock.elapsedRealtime() + durationMs
        worker?.let(LockSupport::unpark)
    }

    override fun cancelTest() = synchronized(lock) {
        if (testUntil == 0L) return@synchronized
        testUntil = 0
        queue.clear()
        encoder.reset()
        sequence = null
        epoch++
        endRequested = true
    }

    override fun start(): Boolean {
        synchronized(lock) {
            if (stopping || finished) return false
            if (started) return ready
            started = true
            startupDeadline = SystemClock.elapsedRealtime() + SensaStartupBudget.maximumMs
            worker = Thread(::run, "KishiSensaOutput").apply { start() }
        }
        val success = try {
            // The metadata reply tightens this budget to the actual transfer count.
            // Short waits let cancellation release the caller without waiting for that budget.
            while (!stopping && !finished && initialized.count != 0L) {
                val remaining = startupDeadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                initialized.await(minOf(remaining, 100), java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            initialized.count == 0L && ready && !stopping
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!success) stopping = true
        return success
    }

    override fun submit(frame: Ds5HapticsPcmFrame) {
        synchronized(lock) {
            refreshPcmMode()
            if (!acceptsPcm) return
            if (!started || stopping || finished || !encoder.accepts(frame)) return
            if (testUntil != 0L) return
            val now = SystemClock.elapsedRealtime()
            val flags = frame.flags.toInt()
            val restart = flags and (Ds5HapticsPcmFrame.FLAG_STREAM_START.toInt() or
                Ds5HapticsPcmFrame.FLAG_DISCONTINUITY.toInt()) != 0
            if (!restart && sequence?.let { frame.sequenceNumber - it <= 0 } == true) return
            if (flags and (Ds5HapticsPcmFrame.FLAG_STREAM_START.toInt() or
                    Ds5HapticsPcmFrame.FLAG_DISCONTINUITY.toInt()) != 0 ||
                sequence?.let { frame.sequenceNumber != it + 1 } == true || now - lastInputAt > 30) {
                queue.clear()
                encoder.reset()
                epoch++
            }
            sequence = frame.sequenceNumber
            lastInputAt = now
            if (flags and Ds5HapticsPcmFrame.FLAG_STREAM_END.toInt() != 0) {
                queue.clear()
                encoder.reset()
                epoch++
                endRequested = true
                return
            }
            endRequested = false
            encoder.setStrength(strength())
            encoder.encode(frame) { packet ->
                while (queue.size >= 10) { queue.removeFirst(); droppedPackets++ }
                queue.addLast(Packet(packet, now))
            }
        }
    }

    override fun stop() = stopAndThen {}

    override fun stopAndThen(onStopped: () -> Unit) {
        val immediate = synchronized(lock) {
            if (finished) true else {
                stopping = true
                queue.clear()
                stoppedCallbacks.add(onStopped)
                if (!started) {
                    started = true
                    worker = Thread(::run, "KishiSensaCleanup").apply { start() }
                }
                false
            }
        }
        if (immediate) onStopped()
        worker?.let(LockSupport::unpark)
    }

    private fun playback(value: Boolean) {
        if (playbackActive == value) return
        playbackActive = value
        onPlaybackChanged?.invoke(value)
    }

    private fun run() {
        var connection: KishiSensaConnection? = null
        try {
            if (stopping) return
            check(device.vendorId == 0x1532 && device.productId == 0x0727 && iface.id == 4)
            val usb = manager.openDevice(device) ?: error("Sensa USB open failed")
            val transport = KishiSensaConnection(usb, iface)
            connection = transport
            transport.initialize({ stopping }) { bytes ->
                startupDeadline = SystemClock.elapsedRealtime() + SensaStartupBudget.remainingAfterSize(bytes)
            }
            if (stopping) return
            ready = true
            initialized.countDown()
            status(KishiSensaHapticProfile.capability(HapticAvailability.READY,
                "Experimental Sensa spectral approximation"))
            var lastPacketAt = 0L
            while (!stopping) {
                var packetEpoch = 0L
                val packet = synchronized(lock) {
                    refreshPcmMode()
                    packetEpoch = epoch
                    val now = SystemClock.elapsedRealtime()
                    if (testUntil != 0L) {
                        if (now >= testUntil) cancelTest()
                        else {
                            val remaining = testUntil - now
                            queue.addLast(Packet(rumbleEncoder.encode(
                                if (testBoth || remaining > 2000) 1f else 0f,
                                if (testBoth || remaining <= 1000) 1f else 0f,
                                strength(), frequency()), now))
                        }
                    }
                    while (queue.isNotEmpty() && now - queue.first.queuedAt > 30) {
                        queue.removeFirst(); droppedPackets++
                    }
                    // PCM freshness, rather than nonzero amplitude, determines priority:
                    // authored silence is also meaningful and must suppress rumble.
                    queue.pollFirst() ?: if (testUntil == 0L && enabled &&
                        (endRequested || now - lastInputAt > 30) && (rumbleLow > 0f || rumbleHigh > 0f)) {
                        Packet(rumbleEncoder.encode(rumbleLow, rumbleHigh, strength(), frequency()), now)
                    } else null
                }
                if (packet == null) {
                    if (playbackActive && (endRequested || SystemClock.elapsedRealtime() - lastPacketAt >= 30)) {
                        transport.write(KishiSensaPacket.silence())
                        silencePackets++
                        rumbleEncoder.reset()
                        playback(false)
                    }
                    LockSupport.parkNanos(1_000_000)
                    continue
                }
                if (stopping || synchronized(lock) { packetEpoch != epoch } ||
                    SystemClock.elapsedRealtime() - packet.queuedAt > 30) continue
                val start = System.nanoTime()
                playback(true)
                transport.write(packet.data)
                sentPackets++
                lastPacketAt = SystemClock.elapsedRealtime()
                val remaining = 10_000_000 - (System.nanoTime() - start)
                if (remaining > 0) LockSupport.parkNanos(remaining)
            }
        } catch (error: Exception) {
            com.limelight.LimeLog.warning("Kishi Sensa failed: ${android.util.Log.getStackTraceString(error)}")
            if (!stopping) status(KishiSensaHapticProfile.capability(HapticAvailability.FAILED, error.message))
        } finally {
            ready = false
            initialized.countDown()
            runCatching { connection?.close() }.onFailure { releaseFailure = it }
            runCatching { playback(false) }
            val callbacks = synchronized(lock) {
                finished = true
                com.limelight.LimeLog.info("Kishi Sensa stopped: sent=$sentPackets dropped=$droppedPackets silence=$silencePackets")
                queue.clear()
                stoppedCallbacks.toList().also { stoppedCallbacks.clear() }
            }
            callbacks.forEach { runCatching(it) }
        }
    }
}
