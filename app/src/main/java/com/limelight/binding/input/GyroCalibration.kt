package com.limelight.binding.input

import android.os.Handler
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zero-rate (bias) calibration for the handheld's own gyroscope, in the style of Steam's
 * "Calibrate" and GamepadMotionHelpers' continuous calibration (Copyright (c) 2020-2023 Julian
 * "Jibb" Smart, MIT): while the device is held still, average every raw gyro sample; that average
 * is the zero point and is subtracted from every later sample.
 *
 * Where it applies: ControllerGyroManager's device-sensor listener calls [apply] on every sample
 * of the Thor's gyroscope, before V+ uses it for anything (DS4 motion to the PC, gyro-to-mouse,
 * gyro-to-stick). The sensor is Android's *uncalibrated* gyroscope, so Android's own moving bias
 * estimate (which can shift while you play and show up as drift) is not applied on top:
 *  - with a stored calibration: output = raw - calibration (fixed, never changes on its own)
 *  - without one: output = raw - Android's estimate (same as stock V+)
 * If the device has no uncalibrated gyroscope, the calibration is applied to the normal one.
 *
 * Units: rad/s in the device frame used by the listener. Results are reported in deg/s.
 * Started from the bottom-screen OPTIONS wheel (GYRO CALIBRATE / GYRO TEST / GYRO RESET).
 */
object GyroCalibration {
    @Volatile var hasCalibration = false
        private set
    @Volatile var biasX = 0f
        private set
    @Volatile var biasY = 0f
        private set
    @Volatile var biasZ = 0f
        private set

    /** True once a sample from the uncalibrated (raw) sensor has been seen. */
    @Volatile var usingRawSensor = false
        private set

    @Volatile private var lastSampleUptime = 0L

    class Stats(val samples: Int, val meanDps: FloatArray, val sdDps: FloatArray, val seconds: Float) {
        val maxMeanDps: Float get() = max(max(abs(meanDps[0]), abs(meanDps[1])), abs(meanDps[2]))
        val maxSdDps: Float get() = max(max(sdDps[0], sdDps[1]), sdDps[2])
    }

    private class Window {
        var n = 0
        val sum = DoubleArray(3)
        val sq = DoubleArray(3)
        var first = 0L
        var last = 0L

        fun add(x: Float, y: Float, z: Float, now: Long) {
            if (n == 0) first = now
            last = now
            n++
            sum[0] += x; sum[1] += y; sum[2] += z
            sq[0] += x.toDouble() * x; sq[1] += y.toDouble() * y; sq[2] += z.toDouble() * z
        }

        fun stats(): Stats {
            val mean = FloatArray(3)
            val sd = FloatArray(3)
            for (i in 0..2) {
                val m = sum[i] / max(n, 1)
                mean[i] = (m * RAD_TO_DEG).toFloat()
                sd[i] = (sqrt(max(0.0, sq[i] / max(n, 1) - m * m)) * RAD_TO_DEG).toFloat()
            }
            return Stats(n, mean, sd, (last - first) / 1000f)
        }
    }

    // Raw samples while calibrating; corrected output samples while testing.
    @Volatile private var rawWindow: Window? = null
    @Volatile private var outWindow: Window? = null

    fun setCalibration(x: Float, y: Float, z: Float) {
        biasX = x
        biasY = y
        biasZ = z
        hasCalibration = true
    }

    fun clear() {
        hasCalibration = false
        biasX = 0f
        biasY = 0f
        biasZ = 0f
    }

    /**
     * Corrects one sample of the device gyroscope. [rx]..[rz] are the sensor's rate values;
     * [ax]..[az] Android's bias estimate (values[3..5] of the uncalibrated sensor, else 0).
     */
    fun apply(rx: Float, ry: Float, rz: Float, ax: Float, ay: Float, az: Float, raw: Boolean, now: Long, out: FloatArray) {
        usingRawSensor = raw
        lastSampleUptime = now
        rawWindow?.let { w -> synchronized(w) { w.add(rx, ry, rz, now) } }
        if (hasCalibration) {
            out[0] = rx - biasX
            out[1] = ry - biasY
            out[2] = rz - biasZ
        } else {
            out[0] = rx - ax
            out[1] = ry - ay
            out[2] = rz - az
        }
        outWindow?.let { w -> synchronized(w) { w.add(out[0], out[1], out[2], now) } }
    }

    /** Whether the gyro has delivered a sample in the last second (gyro on and streaming). */
    fun isLive(now: Long) = now - lastSampleUptime < 1_000L

    /**
     * Averages raw samples for [durationMs] with the device still, then stores the average as
     * the zero point. Rejected if too few samples arrived (gyro off) or the spread of the samples
     * shows the device moved: a gyro at rest varies by a small fraction of [MAX_STILL_SD_DPS];
     * holding it in your hands typically doesn't.
     */
    fun calibrate(durationMs: Long, handler: Handler, onDone: (ok: Boolean, stats: Stats) -> Unit) {
        val w = Window()
        rawWindow = w
        handler.postDelayed({
            rawWindow = null
            val s = synchronized(w) { w.stats() }
            val ok = s.samples >= MIN_SAMPLES && s.maxSdDps <= MAX_STILL_SD_DPS
            if (ok) {
                synchronized(w) {
                    setCalibration(
                        (w.sum[0] / w.n).toFloat(),
                        (w.sum[1] / w.n).toFloat(),
                        (w.sum[2] / w.n).toFloat()
                    )
                }
            }
            onDone(ok, s)
        }, durationMs)
    }

    /** Measures the corrected output (what V+ sends on) for [durationMs]. Hold the device still. */
    fun test(durationMs: Long, handler: Handler, onDone: (Stats) -> Unit) {
        val w = Window()
        outWindow = w
        handler.postDelayed({
            outWindow = null
            onDone(synchronized(w) { w.stats() })
        }, durationMs)
    }

    const val MIN_SAMPLES = 50
    const val MAX_STILL_SD_DPS = 1.0f
    private const val RAD_TO_DEG = 57.29577951308232
}
