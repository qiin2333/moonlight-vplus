package com.limelight.binding.input.haptics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/** Opt-in debug capture on the same clock as IR/native output; never enabled by streaming. */
internal class AuthoredIrMeasurement(context: Context) : SensorEventListener, AutoCloseable {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("IrLatencySensor").apply { start() }
    private val files = context.filesDir
    private val lock = Any()
    private val samples = StringBuilder("time_ns,x,y,z\n")
    private val events = StringBuilder()
    private var closed = false
    private var count = 0

    init {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        check(sensor != null && sensors.registerListener(this, sensor, 2000, 0, Handler(thread.looper))) {
            thread.quitSafely()
            "Accelerometer unavailable for latency capture"
        }
    }

    fun mark(kind: String, body: Float = 0f, point: Float = 0f, eventId: Int = 0) = synchronized(lock) {
        if (!closed) events.append(JSONObject().put("kind", kind).put("input_ns", SystemClock.elapsedRealtimeNanos())
            .put("body", body).put("point", point).put("event_id", eventId)).append('\n')
        Unit
    }

    override fun onSensorChanged(event: SensorEvent) = synchronized(lock) {
        if (!closed && count++ < 60_000) samples.append(event.timestamp).append(',')
            .append(event.values[0]).append(',').append(event.values[1]).append(',').append(event.values[2]).append('\n')
        Unit
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        sensors.unregisterListener(this)
        thread.quitSafely()
        synchronized(lock) {
            File(files, "authored-ir-sensor.csv").writeText(samples.toString())
            File(files, "authored-ir-events.jsonl").writeText(events.toString())
        }
    }
}
