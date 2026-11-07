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
import com.limelight.binding.input.haptics.SoundPoolAuthoredPlayback.Companion.BODY_LEVELS
import com.limelight.binding.input.haptics.SoundPoolAuthoredPlayback.Companion.POINT_LEVELS
import com.limelight.binding.input.haptics.SoundPoolAuthoredPlayback.Companion.CARRIERS

/** Optional energy renderer, not PCM passthrough. L/R audio channels in these assets are silent.
 * Strength is encoded in the haptic samples: SoundPool.setVolume does not scale haptic channels.
 * A point and its background share one stream, because separate voices can mask the point.
 */
internal class SoundPoolAuthoredHaptics private constructor(context: Context, attributes: AudioAttributes) : NativeAuthoredHaptics {
    override val backendName = "SoundPool"
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NativeHapticsAssets").apply { isDaemon = true }
    }
    // Completion callbacks may run on the UI looper. Serialize load/release
    // separately so callbacks only take the short readiness-state lock.
    private val poolLock = Any()
    private val stateLock = Any()
    private val sounds = mutableMapOf<String, Int>()
    private var loaded = 0
    private var loadingFinished = false
    @Volatile private var closed = false
    @Volatile override var ready = false
        private set
    @Volatile override var failed = false
        private set
    private val playback = SoundPoolAuthoredPlayback(
        play = { asset -> pool.play(sounds.getValue(asset.name), 1f, 1f, 1, 0, 1f) },
        stopStream = pool::stop, clockMs = SystemClock::elapsedRealtime)

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            synchronized(stateLock) {
                if (status != 0) {
                    failed = true
                    LimeLog.warning("Native authored haptics asset decoding failed: $status")
                } else loaded++
                updateReady()
            }
        }
        val assets = context.applicationContext.assets
        loader.execute {
            try {
                fun load(name: String): Int = synchronized(poolLock) {
                    check(!closed && !failed)
                    assets.openFd("haptics/native/$name.ogg").use { pool.load(it, 1) }.also {
                        check(it != 0) { "SoundPool rejected $name" }
                    }
                }
                for (hz in CARRIERS) for (body in 0..BODY_LEVELS) for (point in 0..POINT_LEVELS) {
                    val name = SoundPoolAuthoredPlayback.Asset(hz, body, point).name
                    sounds.getOrPut(name) { load(name) }
                }
                synchronized(stateLock) { loadingFinished = true; updateReady() }
            } catch (error: Exception) {
                if (!closed) LimeLog.warning("Native authored haptics unavailable: ${error.message}")
                synchronized(stateLock) { failed = true; ready = false }
            } finally { loader.shutdown() }
        }
    }

    private fun updateReady() {
        ready = !closed && !failed && loadingFinished && loaded == sounds.size
    }

    override fun render(body: NativeAuthoredBody, point: Float, keepWarm: Boolean): Long? {
        if (!ready) { stop(); return null }
        return try { playback.render(body, point, keepWarm) } catch (error: Exception) {
            failed = true
            ready = false
            stop()
            LimeLog.warning("Native authored haptics disabled: ${error.message}")
            null
        }
    }

    override fun stop() = playback.stop()

    override fun close() {
        synchronized(poolLock) {
            synchronized(stateLock) {
                closed = true
                ready = false
            }
            loader.shutdownNow()
            pool.release()
        }
    }

    companion object {
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
