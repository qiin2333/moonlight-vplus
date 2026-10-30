package com.limelight.binding.input.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.limelight.LimeLog
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** Graded HE2 transients. Continuous renewal failed device acceptance, so it stays on the
 * existing fallback (or on the physical controller in coordinated mode).
 * No SDK exemptions, unbounded loops or separate competing motor voices.
 * Owned by the existing serial device worker; all source timing/expiry stays in the IR coordinator.
 */
internal class RichTapAuthoredHaptics(
    private val play: (IntArray) -> Unit,
    private val cancel: () -> Unit,
    private val clockMs: () -> Long,
    private val processId: Int
) : NativeAuthoredHaptics {
    override val backendName = "RichTap"
    override val supportsContinuous = false
    @Volatile override var ready = true
        private set
    private var active = false
    private var sequence = 0
    private var pointUntil = 0L

    override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
        if (!ready) return null
        val now = clockMs()
        val pointValue = level(point)
        if (pointValue == 0 && now < pointUntil) return pointUntil
        if (pointValue == 0) { stop(); return null }
        return try {
            check(ControllerRumbleState.normalizeAmplitude(body) == 0f) { "RichTap continuous output unavailable" }
            val header = intArrayOf(2, 2, processId, (1 shl 16) or (++sequence and 65535),
                0x10001, 0, 0, 1)
            active = true
            play(header + intArrayOf(4097, 5, 0, 0, pointValue, FREQUENCY, 0))
            pointUntil = now + 40L
            pointUntil
        } catch (error: Exception) {
            ready = false
            LimeLog.warning("RichTap authored haptics disabled: ${error.message}")
            stop()
            null
        }
    }

    override fun stop() {
        pointUntil = 0
        if (active) { active = false; cancel() }
    }

    override fun close() { ready = false; stop() }

    companion object {
        private const val FREQUENCY = 56
        // Device rendering gain, not a physical-force percentage. User strength is applied
        // upstream; use the tested HE range without changing the host's energy evidence.
        private fun level(gain: Float) =
            (ControllerRumbleState.normalizeAmplitude(gain) / .3f * 100).roundToInt().coerceIn(0, 100)

        private fun api(): Pair<Method, Method>? {
            if (Build.VERSION.SDK_INT < 26) return null
            return try {
                val type = Class.forName("android.os.RichTapVibrationEffect")
                val support = type.getMethod("checkIfRichTapSupport").invoke(null) as Int
                if (support <= 2 || (support shr 8 and 255) < 24) null else
                    type.getMethod("createPatternHeWithParam", IntArray::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) to
                        type.getMethod("createPatternHeParameter", Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (_: ReflectiveOperationException) { null }
            catch (_: SecurityException) { null }
            catch (_: LinkageError) { null }
        }

        fun supported(): Boolean = api() != null

        fun create(context: Context): NativeAuthoredHaptics? {
            if (Build.VERSION.SDK_INT < 26) return null
            val (factory, parameter) = api() ?: return null
            return try {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (!vibrator.hasVibrator()) return null
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                RichTapAuthoredHaptics(
                    play = { raw -> vibrator.vibrate(factory.invoke(null, raw, 1, 0, 255, 0) as VibrationEffect, attributes) },
                    cancel = {
                        try { vibrator.vibrate(parameter.invoke(null, 0, 0, 0) as VibrationEffect, attributes) }
                        finally { vibrator.cancel() }
                    }, clockMs = SystemClock::elapsedRealtime, processId = Process.myPid())
            } catch (error: Exception) {
                LimeLog.warning("RichTap authored haptics unavailable: ${error.message}")
                null
            }
        }
    }
}
