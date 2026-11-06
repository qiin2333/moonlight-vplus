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
    private val play: (IntArray, Int) -> Unit,
    private val cancel: () -> Unit,
    private val clockMs: () -> Long,
    private val processId: Int,
    private val updateAmplitude: (Int) -> Unit
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
    private var bodyFrequency = 56
    private var attackUntil = 0L
    private var frequencyRampUntil = 0L
    private val textureFrequency = NativeAuthoredFrequency()

    override fun render(body: NativeAuthoredBody, point: Float, keepWarm: Boolean): Long? {
        if (!ready) return null
        val now = clockMs()
        val pointValue = level(point, .6f)
        val requestedBody = level(body.gain)
        val requestedTarget = if (requestedBody > 0 && now < bodyUntil &&
            kotlin.math.abs(requestedBody - bodyLevel) < BODY_STEP) bodyLevel
            else (requestedBody + BODY_STEP / 2) / BODY_STEP * BODY_STEP
        // Suppress a filtered point tail only when no continuous voice is established.
        val target = if (requestedTarget <= BODY_STEP && (pointValue > 0 || now >= bodyUntil)) 0
            else requestedTarget
        val frequency = frequency(textureFrequency.advance(body.frequencyHintHz, now),
            bodyFrequency.takeIf { now < bodyUntil })
        return try {
            if (pointValue > 0) {
                active = true
                parameterCarrier = false
                attackUntil = now + MIXED_ATTACK_MS
                if (target > 0) {
                    val from = if (now < bodyUntil) bodyLevel else target
                    val fromFrequency = if (now < bodyUntil) bodyFrequency else frequency
                    // Keep the short 2/12 ms attack, then immediately return to the source
                    // background. There is no persistent ducking/headroom window.
                    val curve = intArrayOf(
                        0, from, fromFrequency - frequency,
                        2, mixedPeak(target, pointValue), POINT_FREQUENCY - frequency,
                        MIXED_ATTACK_MS.toInt(), target, 0,
                        BODY_DURATION_MS - 2, target, 0, BODY_DURATION_MS, target, 0)
                    play(pattern(intArrayOf(4096, 6 + curve.size, 0, 0, 100,
                        frequency, BODY_DURATION_MS, curve.size / 3) + curve), 255)
                    bodyLevel = target
                    bodyFrequency = frequency
                    bodyUntil = now + BODY_DURATION_MS
                    renewAt = now + BODY_RENEW_MS
                    pointUntil = attackUntil
                } else {
                    bodyLevel = 0
                    bodyUntil = 0
                    renewAt = 0
                    play(pattern(intArrayOf(4097, 5, 0, 0, pointValue, POINT_FREQUENCY, 0)), 255)
                    // Only keep a silent heartbeat from cancelling an isolated native preset.
                    // A new background may start after its compact attack, not after this tail.
                    pointUntil = now + POINT_TAIL_MS
                }
                pointUntil
            } else if (now < attackUntil) {
                attackUntil
            } else if (target > 0) {
                renderParameters(target, frequency, now)
            } else if (now < pointUntil && bodyUntil == 0L) {
                pointUntil
            } else {
                stop(); null
            }
        } catch (error: Exception) {
            ready = false
            LimeLog.warning("RichTap authored haptics disabled: ${error.message}")
            stop()
            null
        }
    }

    private fun renderParameters(target: Int, frequency: Int, now: Long): Long {
        // Let the previous 20 ms frequency ramp reach its endpoint before replacing
        // it. Otherwise the next curve would start at a target never yet reached.
        // Amplitude updates and fresh points remain immediate.
        if (!parameterCarrier || now >= renewAt ||
            frequency != bodyFrequency && now >= frequencyRampUntil) {
            val continuing = now < bodyUntil
            // Frequency is expressed in HE's normalized units, not Hz. A frequency
            // change uses a continuous curve starting at the previous frequency and
            // nonzero level, rather than another zero-to-peak attack. The vendor's
            // global frequency parameter has no portable Hz mapping, so leave it alone.
            val curve = intArrayOf(0, if (continuing) 100 else 0,
                if (continuing) bodyFrequency - frequency else 0,
                FREQUENCY_RAMP_MS, 100, 0,
                BODY_DURATION_MS - 2, 100, 0, BODY_DURATION_MS, 100, 0)
            active = true
            play(pattern(intArrayOf(4096, 6 + curve.size, 0, 0, 100,
                frequency, BODY_DURATION_MS, curve.size / 3) + curve), amplitude(target))
            parameterCarrier = true
            bodyUntil = now + BODY_DURATION_MS
            renewAt = now + BODY_RENEW_MS
            frequencyRampUntil = now + FREQUENCY_RAMP_MS
            bodyFrequency = frequency
        } else if (target != bodyLevel) {
            updateAmplitude(amplitude(target))
        }
        bodyLevel = target
        pointUntil = 0
        attackUntil = 0
        return if (frequency != bodyFrequency) minOf(renewAt, frequencyRampUntil) else renewAt
    }

    private fun pattern(event: IntArray): IntArray = intArrayOf(
        2, 2, processId, (1 shl 16) or (++sequence and 65535), 0x10001, 0, 0, 1
    ) + event

    override fun stop() {
        pointUntil = 0
        bodyUntil = 0
        renewAt = 0
        bodyLevel = 0
        parameterCarrier = false
        attackUntil = 0
        frequencyRampUntil = 0
        bodyFrequency = POINT_FREQUENCY
        textureFrequency.reset()
        if (active) { active = false; cancel() }
    }

    override fun close() { ready = false; stop() }

    companion object {
        private const val POINT_FREQUENCY = 56
        private const val FREQUENCY_RAMP_MS = 20
        private const val BODY_STEP = 5
        private const val BODY_DURATION_MS = 5_000
        private const val BODY_RENEW_MS = 4_000L
        private const val POINT_TAIL_MS = 40L
        private const val MIXED_ATTACK_MS = 12L
        // Conservative relative timbre mapping; these are not calibrated motor Hz.
        // Quantize in HE units to avoid replacing a carrier for tiny ZCR variations.
        internal fun frequency(hz: Float, previous: Int? = null): Int {
            val value = 35f + (hz.coerceIn(100f, 260f) - 100f) * 40f / 160f
            return if (previous != null && kotlin.math.abs(value - previous) < 5f) previous
                else ((value / 5f).roundToInt() * 5).coerceIn(35, 75)
        }
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
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (!vibrator.hasVibrator()) return null
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                RichTapAuthoredHaptics(
                    play = { raw, gain ->
                        vibrator.vibrate(factory.invoke(null, raw, 1, 0, gain, 0) as VibrationEffect, attributes)
                    },
                    cancel = {
                        // Parameter zero stops the native voice without the blocking cancel/restart
                        // sequence seen on vendor services. Use framework cancellation only
                        // if that native stop is rejected.
                        try { vibrator.vibrate(parameter.invoke(null, 0, 0, 0) as VibrationEffect, attributes) }
                        catch (_: Exception) { vibrator.cancel() }
                    }, clockMs = SystemClock::elapsedRealtime, processId = Process.myPid(),
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
