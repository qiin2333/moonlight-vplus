package com.limelight.binding.input.haptics

import android.hardware.usb.*
import android.os.SystemClock
import com.limelight.nvstream.Ds5HapticsPcmFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.locks.LockSupport

/** Owns the haptics interface on a dedicated USB connection. Gamepad input interfaces are untouched. */
@android.annotation.SuppressLint("NewApi") // Created only on API 26+, required for bounded requestWait.
internal class KishiUsbHapticsSink(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val iface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val status: (ControllerHapticsCapability) -> Unit
) : WaveformHapticsSink, WaveformPlaybackControl, WaveformChannelTest {
    override val isOperational: Boolean get() = ready && !stopping && !finished
    override val playbackControl: WaveformPlaybackControl get() = this
    override val channelTest: WaveformChannelTest get() = this
    private data class Packet(val data: ByteArray, val queuedAt: Long)
    private val lock = Any()
    private val queue = ArrayDeque<Packet>()
    private val encoder = KishiPcmEncoder()
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
    @Volatile private var ready = false
    @Volatile override var releaseFailure: Throwable? = null
        private set
    @Volatile private var endRequested = false
    private var epoch = 0L
    @Volatile private var testUntil = 0L
    override val isTesting: Boolean get() = testUntil > SystemClock.elapsedRealtime()
    override val canTest: Boolean get() = ready && !stopping && !finished
    private var sentPackets = 0L
    private var droppedPackets = 0L
    private var silencePackets = 0L

    override fun testChannels() = synchronized(lock) {
        if (!ready || stopping || finished) return@synchronized
        queue.clear()
        encoder.reset()
        sequence = null
        epoch++
        endRequested = false
        testUntil = SystemClock.elapsedRealtime() + 800
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
            worker = Thread(::run, "KishiPcmOutput").apply { start() }
        }
        val success = try {
            initialized.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) && ready
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!success) stopping = true
        return success
    }

    override fun submit(frame: Ds5HapticsPcmFrame) {
        synchronized(lock) {
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
                    worker = Thread(::run, "KishiPcmCleanup").apply { start() }
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
        var connection: UsbDeviceConnection? = null
        var request: UsbRequest? = null
        var claimed = false
        var enabled = false
        var stage = "open"
        try {
            if (stopping) return
            val usb = manager.openDevice(device) ?: error("USB open failed")
            connection = usb
            stage = "claim"
            claimed = usb.claimInterface(iface, false)
            check(claimed) { "Haptic interface claim failed" }
            stage = "initialize request"
            val output = UsbRequest()
            request = output
            check(output.initialize(usb, endpoint)) { "Interrupt request initialization failed" }
            stage = "disable waveform"
            setLevel(usb, 0)
            if (stopping) return
            ready = true
            initialized.countDown()
            status(KishiUsbHapticProfile.capability(HapticAvailability.READY, "Experimental transport; hardware validation pending"))
            com.limelight.LimeLog.info("Kishi PCM ready: pid=${device.productId.toString(16)} interface=${iface.id} endpoint=${endpoint.address}")
            val buffer = ByteBuffer.allocateDirect(64)
            var deadline = System.nanoTime()
            var lastPacketAt = 0L
            fun write(bytes: ByteArray) {
                stage = "write PCM"
                buffer.clear()
                buffer.put(bytes).flip()
                check(output.queue(buffer)) { "USB queue failed" }
                check(usb.requestWait(100) === output && buffer.position() == 64) { "USB short or failed transfer" }
            }
            while (!stopping) {
                var packetEpoch = 0L
                val packet = synchronized(lock) {
                    packetEpoch = epoch
                    val now = SystemClock.elapsedRealtime()
                    if (testUntil != 0L) {
                        if (now >= testUntil) cancelTest()
                        else {
                            val channel = if (testUntil - now > 400) 0 else 1
                            val pcm = ByteArray(48)
                            for (i in 0 until 12) {
                                val value = (3000 * kotlin.math.sin(2 * Math.PI * 150 * (now * 4 + i) / 4000)).toInt()
                                pcm[i * 4 + channel * 2] = value.toByte()
                                pcm[i * 4 + channel * 2 + 1] = (value shr 8).toByte()
                            }
                            encoder.encode(Ds5HapticsPcmFrame(0, 0, 0, 0, 4000, 12, 2, 16, pcm)) {
                                queue.addLast(Packet(it, now))
                            }
                        }
                    }
                    while (queue.isNotEmpty() && now - queue.first.queuedAt > 30) {
                        queue.removeFirst()
                        droppedPackets++
                    }
                    queue.pollFirst()
                }
                if (packet == null) {
                    if (enabled && (endRequested || SystemClock.elapsedRealtime() - lastPacketAt >= 30)) {
                        write(KishiPcmEncoder.silence())
                        silencePackets++
                        setLevel(usb, 0)
                        enabled = false
                        playback(false)
                    } else if (enabled) {
                        write(KishiPcmEncoder.silence())
                        silencePackets++
                        LockSupport.parkNanos(3_000_000)
                    }
                    LockSupport.parkNanos(1_000_000)
                    deadline = System.nanoTime()
                    continue
                }
                if (!enabled) {
                    stage = "enable waveform"
                    playback(true)
                    setLevel(usb, 1)
                    setLevel(usb, 0x21)
                    enabled = true
                }
                if (stopping || synchronized(lock) { packetEpoch != epoch } ||
                    SystemClock.elapsedRealtime() - packet.queuedAt > 30) continue
                write(packet.data)
                sentPackets++
                lastPacketAt = SystemClock.elapsedRealtime()
                deadline += 3_000_000
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) LockSupport.parkNanos(remaining)
                else deadline = System.nanoTime() // Never burst old packets to catch up.
            }
        } catch (error: Exception) {
            com.limelight.LimeLog.warning("Kishi PCM failed: stage=$stage pid=${device.productId.toString(16)} " +
                "interface=${iface.id} endpoint=${endpoint.address} stopping=$stopping\n${android.util.Log.getStackTraceString(error)}")
            if (!stopping) status(KishiUsbHapticProfile.capability(HapticAvailability.FAILED, error.message))
        } finally {
            ready = false
            initialized.countDown()
            runCatching { request?.cancel() }
            runCatching { request?.close() }
            if (claimed) {
                runCatching { connection?.let { setLevel(it, 0) } }
                runCatching { check(connection?.releaseInterface(iface) == true) { "Haptic interface release failed" } }
                    .onFailure { com.limelight.LimeLog.warning("Kishi PCM cleanup: ${it.message}") }
            }
            runCatching { connection?.close() }.onFailure { releaseFailure = it }
            runCatching { playback(false) }
            val callbacks = synchronized(lock) {
                finished = true
                com.limelight.LimeLog.info("Kishi PCM stopped: sent=$sentPackets dropped=$droppedPackets silence=$silencePackets")
                queue.clear()
                stoppedCallbacks.toList().also { stoppedCallbacks.clear() }
            }
            callbacks.forEach { runCatching(it) }
        }
    }

    private fun setLevel(connection: UsbDeviceConnection, level: Int) {
        for (channel in 1..2) {
            val data = byteArrayOf(0, channel.toByte(), level.toByte())
            val transferred = connection.controlTransfer(0x21, 9, 0x300, iface.id, data, 3, 100)
            check(transferred == 3) {
                "Feature report failed: interface=${iface.id} channel=$channel level=$level transferred=$transferred expected=3"
            }
        }
    }
}
