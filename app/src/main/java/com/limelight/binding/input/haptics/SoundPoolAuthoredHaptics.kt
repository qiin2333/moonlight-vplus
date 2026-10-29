package com.limelight.binding.input.haptics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import androidx.annotation.ChecksSdkIntAtLeast
import com.limelight.LimeLog
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Optional energy renderer, not PCM passthrough. L/R audio channels in these assets are silent.
 * Strength is encoded in the haptic samples: SoundPool.setVolume does not scale haptic channels.
 * A point and its background share one stream, because separate voices can mask the point.
 */
internal class SoundPoolAuthoredHaptics private constructor(context: Context, attributes: AudioAttributes) : NativeAuthoredHaptics {
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NativeHapticsAssets").apply { isDaemon = true }
    }
    private val loadLock = Any()
    private val tones = IntArray(BODY_LEVELS + 1)
    private val mixed = Array(BODY_LEVELS + 1) { IntArray(POINT_LEVELS + 1) }
    private var loaded = 0
    private var loadingFinished = false
    @Volatile private var closed = false
    @Volatile override var ready = false
        private set
    private var stream = 0
    private var tone = -1
    private var toneRefreshAt = 0L
    private var pointUntil = 0L

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            synchronized(loadLock) {
                if (status != 0) {
                    closed = true
                    ready = false
                    LimeLog.warning("Native authored haptics asset decoding failed: $status")
                } else loaded++
                updateReady()
            }
        }
        val assets = context.applicationContext.assets
        loader.execute {
            try {
                fun load(name: String): Int = synchronized(loadLock) {
                    check(!closed)
                    assets.openFd("haptics/native/$name.ogg").use { pool.load(it, 1) }.also {
                        check(it != 0) { "SoundPool rejected $name" }
                    }
                }
                for (body in 0..BODY_LEVELS) tones[body] = load("body_$body")
                for (body in 0..BODY_LEVELS) for (point in 1..POINT_LEVELS)
                    mixed[body][point] = load("mixed_${body}_$point")
                synchronized(loadLock) { loadingFinished = true; updateReady() }
            } catch (error: Exception) {
                if (!closed) LimeLog.warning("Native authored haptics unavailable: ${error.message}")
                synchronized(loadLock) { ready = false }
            } finally { loader.shutdown() }
        }
    }

    private fun updateReady() {
        ready = !closed && loadingFinished && loaded == (BODY_LEVELS + 1) * (POINT_LEVELS + 1)
    }

    override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
        if (!ready) { stop(); return null }
        return try { renderReady(body, point, keepWarm) } catch (error: Exception) {
            ready = false
            stop()
            LimeLog.warning("Native authored haptics disabled: ${error.message}")
            null
        }
    }

    private fun renderReady(body: Float, point: Float, keepWarm: Boolean): Long? {
        val bodyLevel = level(body, BODY_LEVELS)
        val pointLevel = level(point, POINT_LEVELS)
        val now = SystemClock.elapsedRealtime()
        if (pointLevel > 0) {
            replace(mixed[bodyLevel][pointLevel])
            tone = -1
            pointUntil = now + POINT_HOLD_MS
            return pointUntil
        }
        if (now < pointUntil) return pointUntil
        // Zero samples keep the transport ready without driving the motor. Use the same
        // replace path: a parallel silent stream can mask a separate haptic point.
        if (bodyLevel == 0 && !keepWarm) stop()
        else if (tone != bodyLevel || now >= toneRefreshAt) {
            replace(tones[bodyLevel])
            tone = bodyLevel
            toneRefreshAt = now + TONE_REFRESH_MS
        }
        return toneRefreshAt.takeIf { stream != 0 && tone >= 0 }
    }

    private fun replace(sound: Int) {
        // All assets are finite. Renew the one-second body before EOF, so a stalled
        // worker cannot leave an indefinitely looping actuator after source expiry.
        val next = pool.play(sound, 1f, 1f, 1, 0, 1f)
        if (next == 0) throw IllegalStateException("Native authored haptics playback rejected")
        if (stream != 0) pool.stop(stream)
        stream = next
    }

    override fun stop() {
        pool.autoPause()
        stream = 0
        tone = -1
        toneRefreshAt = 0
        pointUntil = 0
    }

    override fun close() {
        synchronized(loadLock) {
            ready = false
            closed = true
            loader.shutdownNow()
            pool.release()
        }
    }

    companion object {
        private const val BODY_LEVELS = 16
        private const val POINT_LEVELS = 8
        private const val MAX_GAIN = 0.6f
        private const val POINT_HOLD_MS = 180L
        private const val TONE_REFRESH_MS = 750L

        private fun level(value: Float, levels: Int) =
            (ControllerRumbleState.normalizeAmplitude(value) / MAX_GAIN * levels)
                .roundToInt().coerceIn(0, levels)

        @ChecksSdkIntAtLeast(api = 29)
        fun supported(): Boolean = Build.VERSION.SDK_INT >= 29 && AudioManager.isHapticPlaybackSupported()

        fun create(context: Context): NativeAuthoredHaptics? = if (!supported()) null else try {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setHapticChannelsMuted(false).build()
            SoundPoolAuthoredHaptics(context, attributes)
        } catch (error: Exception) {
            LimeLog.warning("Native authored haptics initialization failed: ${error.message}")
            null
        }
    }
}
