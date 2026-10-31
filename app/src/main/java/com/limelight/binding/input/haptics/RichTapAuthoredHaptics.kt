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
import kotlin.math.pow
import kotlin.math.roundToInt

/** A single finite HE2 voice for body and points. Body gain can update in place;
 * point envelopes never append work to the vendor timeline. Source expiry and ownership remain
 * with the existing IR coordinator and serial device worker.
 */
internal class RichTapAuthoredHaptics(
    private val play: (IntArray) -> Unit,
    private val cancel: () -> Unit,
    private val clockMs: () -> Long,
    private val processId: Int,
    private val startCarrier: ((IntArray, Int) -> Unit)? = null,
    private val updateAmplitude: ((Int) -> Unit)? = null
) : NativeAuthoredHaptics {
    override val backendName = "RichTap"
    @Volatile override var ready = true
        private set
    override val failed get() = !ready
    private var active = false
    private var sequence = 0
    private var pointUntil = 0L
    private var bodyUntil = 0L
    private var renewAt = 0L
    private var bodyLevel = 0
    private var parameterCarrier = false
    private var mixedUntil = 0L

    override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
        if (!ready) return null
        val now = clockMs()
        val pointValue = level(point, .6f)
        val requestedBody = level(body)
        if (pointValue > 0) mixedUntil = now + MIXED_HEADROOM_MS
        // Hysteresis keeps small analysis fluctuations from restarting the carrier.
        val requestedTarget = if (requestedBody > 0 && now < bodyUntil &&
            kotlin.math.abs(requestedBody - bodyLevel) < BODY_STEP) bodyLevel
            else (requestedBody + BODY_STEP / 2) / BODY_STEP * BODY_STEP
        // A single quantization step can be only a filtered impact tail. Do not
        // start a long carrier for it or turn a dominant point into a mixed voice.
        val target = if (requestedTarget <= BODY_STEP && (pointValue > 0 || now >= bodyUntil)) 0
            else if (now < mixedUntil) minOf(requestedTarget, MIXED_BODY_LIMIT) else requestedTarget
        if (pointValue == 0 && now < pointUntil) return pointUntil
        return try {
            // Use global gain only for continuous levels. Keep points in a native
            // envelope and protect them from intervening parameter writes.
            if (target > 0 && pointValue == 0 && startCarrier != null && updateAmplitude != null &&
                (parameterCarrier || target != bodyLevel || now >= renewAt)) {
                return renderParameters(target, now)
            }
            when {
                target > 0 -> {
                    val from = if (now < bodyUntil) bodyLevel else 0
                    if (pointValue > 0 || target != bodyLevel || now >= renewAt) {
                        val peak = mixedPeak(target, pointValue)
                        val curve = if (pointValue > 0) intArrayOf(
                            0, minOf(from, target), 0, 2, peak, 0, 12, target, 0,
                            BODY_DURATION_MS - 2, target, 0, BODY_DURATION_MS, target, 0
                        ) else intArrayOf(
                            0, from, 0, 2, target, 0,
                            BODY_DURATION_MS - 2, target, 0, BODY_DURATION_MS, target, 0
                        )
                        active = true
                        parameterCarrier = false
                        play(pattern(intArrayOf(4096, 6 + curve.size, 0, 0, 100,
                            FREQUENCY, BODY_DURATION_MS, curve.size / 3) + curve))
                        bodyLevel = target
                        bodyUntil = now + BODY_DURATION_MS
                        renewAt = now + BODY_RENEW_MS
                        if (pointValue > 0) pointUntil = now + MIXED_POINT_HOLD_MS
                    }
                    nextWake(now)
                }
                pointValue > 0 -> {
                    val hadBody = now < bodyUntil
                    bodyLevel = 0
                    bodyUntil = 0
                    renewAt = 0
                    active = true
                    parameterCarrier = false
                    play(pattern(intArrayOf(4097, 5, 0, 0, pointValue, FREQUENCY, 0)))
                    pointUntil = now + if (hadBody) MIXED_POINT_HOLD_MS else POINT_HOLD_MS
                    pointUntil
                }
                else -> { stop(); null }
            }
        } catch (error: Exception) {
            ready = false
            LimeLog.warning("RichTap authored haptics disabled: ${error.message}")
            stop()
            null
        }
    }

    private fun renderParameters(target: Int, now: Long): Long {
        if (!parameterCarrier || now >= bodyUntil || now >= renewAt) {
            // A constant native carrier is started with the requested global gain;
            // subsequent gain changes do not replace its five-second envelope.
            // HE2 continuous events require at least four curve points.
            val curve = intArrayOf(0, 0, 0, 2, 100, 0,
                BODY_DURATION_MS - 2, 100, 0, BODY_DURATION_MS, 100, 0)
            active = true
            startCarrier!!.invoke(pattern(intArrayOf(4096, 6 + curve.size, 0, 0, 100,
                FREQUENCY, BODY_DURATION_MS, curve.size / 3) + curve), amplitude(target))
            parameterCarrier = true
            bodyLevel = target
            bodyUntil = now + BODY_DURATION_MS
            renewAt = now + BODY_RENEW_MS
        } else if (target != bodyLevel) {
            updateAmplitude!!.invoke(amplitude(target))
            bodyLevel = target
        }
        return nextWake(now)
    }

    private fun nextWake(now: Long): Long = minOf(renewAt,
        pointUntil.takeIf { it > now } ?: Long.MAX_VALUE,
        mixedUntil.takeIf { it > now } ?: Long.MAX_VALUE)

    private fun pattern(event: IntArray): IntArray = intArrayOf(
        2, 2, processId, (1 shl 16) or (++sequence and 65535), 0x10001, 0, 0, 1
    ) + event

    override fun stop() {
        pointUntil = 0
        bodyUntil = 0
        renewAt = 0
        bodyLevel = 0
        parameterCarrier = false
        mixedUntil = 0
        if (active) { active = false; cancel() }
    }

    override fun close() { ready = false; stop() }

    companion object {
        private const val FREQUENCY = 56
        private const val BODY_STEP = 5
        private const val BODY_DURATION_MS = 5_000
        private const val BODY_RENEW_MS = 4_000L
        private const val POINT_HOLD_MS = 40L
        private const val MIXED_POINT_HOLD_MS = 100L
        private const val MIXED_HEADROOM_MS = 1_500L
        private const val MIXED_BODY_LIMIT = 70
        // Device rendering gain, not a physical-force percentage. User strength is applied
        // upstream; use the tested HE range without changing the host's energy evidence.
        private fun level(gain: Float, fullScale: Float = .3f) =
            (ControllerRumbleState.normalizeAmplitude(gain) / fullScale * 100).roundToInt().coerceIn(0, 100)
        private fun amplitude(level: Int): Int {
            // HE curve intensity is nonlinear; the external amplitude is linear.
            // Match the same body level when switching between the two controls.
            val intensity = level / 100.0
            return (intensity * 1.14.pow(10 * (intensity - 1)) * 255).roundToInt()
        }
        private fun mixedPeak(body: Int, point: Int) = body + ((100 - body) * point + 50) / 100

        private fun api(): Pair<Method, Method>? {
            if (Build.VERSION.SDK_INT < 26) return null
            return try {
                val type = Class.forName("android.os.RichTapVibrationEffect")
                val support = type.getMethod("checkIfRichTapSupport").invoke(null) as? Int ?: return null
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

        @Suppress("DEPRECATION")
        fun create(context: Context): NativeAuthoredHaptics? {
            if (Build.VERSION.SDK_INT < 26) return null
            val (factory, parameter) = api() ?: return null
            return try {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (!vibrator.hasVibrator()) return null
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                val start: (IntArray, Int) -> Unit = { raw, gain ->
                    vibrator.vibrate(factory.invoke(null, raw, 1, 0, gain, 0) as VibrationEffect, attributes)
                }
                RichTapAuthoredHaptics(
                    play = { raw -> start(raw, 255) },
                    cancel = {
                        // Parameter zero stops the native voice without the blocking cancel/restart
                        // sequence seen on vendor services. Use framework cancellation only
                        // if that native stop is rejected.
                        try { vibrator.vibrate(parameter.invoke(null, 0, 0, 0) as VibrationEffect, attributes) }
                        catch (_: Exception) { vibrator.cancel() }
                    }, clockMs = SystemClock::elapsedRealtime, processId = Process.myPid(),
                    startCarrier = start,
                    updateAmplitude = { gain ->
                        vibrator.vibrate(parameter.invoke(null, -1, gain, -1) as VibrationEffect, attributes)
                    })
            } catch (error: Exception) {
                LimeLog.warning("RichTap authored haptics unavailable: ${error.message}")
                null
            }
        }
    }
}
