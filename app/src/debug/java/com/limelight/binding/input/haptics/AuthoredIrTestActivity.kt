package com.limelight.binding.input.haptics

import android.app.Activity
import android.media.AudioAttributes
import android.os.*
import android.widget.*
import com.limelight.LimeLog
import com.limelight.nvstream.*

/** Actual host analyzer wire fixtures, replayed through production timeline, policy and sink. */
class AuthoredIrTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var vibrator: Vibrator
    private var device: DeviceVibrationCoordinator? = null
    private var epoch = 0
    private var destroyed = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(25, 35, 25, 20) }
        status = TextView(this).apply { textSize = 18f; text = "IR 增强实际 PCM 分析回放\n持续层与事件分开，频率跟随源事件。\n计数只表示系统调用，不代表物理马达完整执行。" }
        layout.addView(status)
        fun button(label: String, asset: String) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { start(asset) } })
        }
        button("雨景（v2 + 扩展）", "rain-haptics.irextbin")
        button("50点/秒 PCM", "pulse_density_50.irextbin")
        button("持续250Hz PCM", "sustain_250hz_0.12.irextbin")
        button("持续300Hz PCM", "sustain_300hz_0.12.irextbin")
        button("持续＋短落点", "mixed_clean_body_0.12_separate_0.irextbin")
        layout.addView(Button(this).apply { text = "停止"; setOnClickListener { stop(); status.text = "已停止" } })
        setContentView(layout)
        // This initializes and verifies native callback method lookup in minified builds.
        Class.forName("com.limelight.nvstream.jni.MoonBridge")
        intent.getStringExtra("asset")?.let { asset ->
            val token = epoch
            ui.postDelayed({ if (!destroyed && epoch == token) start(asset) }, 600)
        }
    }

    private fun start(asset: String) {
        if (destroyed) return
        stop()
        if (!asset.matches(Regex("[A-Za-z0-9_.-]+"))) return
        val frames = try { assets.open("haptics/generated/$asset").use { Ds5HapticsIrExtendedDecoder.decode(it.readBytes()) } }
            catch (error: Exception) { status.text = "缺少或无效的主机 IR 增强分析资产：${error.message}"; return }
        if (frames.isEmpty()) return
        val token = epoch
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val waves = java.util.concurrent.atomic.AtomicInteger()
        var acceptedEvents = 0
        var inputFrames = 0
        val supportsTick = com.limelight.binding.input.ControllerRumbleManager.supportsDeviceDetailTick(vibrator)
        val sink = DeviceVibrationCoordinator(
            { callback, delay -> ui.postDelayed(callback, delay); Unit }, { ui.removeCallbacks(it) },
            { amplitude, duration ->
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude), attributes)
                waves.incrementAndGet()
            }, vibrator::cancel, playDeviceTick = if (supportsTick) ({
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attributes)
                calls.incrementAndGet()
                Unit
            }) else null, clockMs = SystemClock::elapsedRealtime)
        device = sink
        val timeline = AuthoredIrTimeline()
        var policy = AuthoredIrPhonePolicy()
        val origin = SystemClock.elapsedRealtime() + 400
        val sourceOrigin = frames.first().windowEndUs
        var pulse = 0f
        var pulseUntil = 0L
        var index = 0
        val pump = object : Runnable {
            override fun run() {
                if (destroyed || epoch != token) return
                val now = SystemClock.elapsedRealtime()
                while (index < frames.size && origin + (frames[index].windowEndUs - sourceOrigin) / 1000 <= now) {
                    val frame = frames[index++]
                    timeline.accept(frame, now * 1000)
                    inputFrames++
                }
                val output = timeline.poll(now * 1000)
                if (output != null) {
                    if (output.reset) {
                        sink.resetAuthoredIr()
                        policy = AuthoredIrPhonePolicy()
                        pulse = 0f
                        pulseUntil = 0L
                    }
                    if (output.event != null) {
                        acceptedEvents++
                        val event = output.event.value
                        pulse = AuthoredIrFallback.transientAmplitude(
                            maxOf(event.leftStrength, event.rightStrength) * event.confidence)
                        pulseUntil = now + 60
                    }
                    if (now >= pulseUntil || output.ended) pulse = 0f
                    val details = policy.useDetails(output.body, now, output.ended,
                        GameRumbleMode.DEVICE, false, supportsTick)
                    val body = if (details) ControllerRumbleState.ZERO else ControllerRumbleState(
                        output.body.lowFrequency, maxOf(output.body.highFrequency, pulse))
                    sink.submitAuthoredIrBody((body.lowFrequency * 65535).toInt().toShort(),
                        (body.highFrequency * 65535).toInt().toShort(), 100, !output.ended, output.bodyValidUntilUs / 1000)
                    if (details) sink.submitAuthoredIrDetail(output.event != null, 100,
                        output.event?.expiresUs?.div(1000) ?: now, !output.ended)
                    else sink.submitAuthoredIrDetail(false, 0, now, active = false)
                }
                if (index < frames.size || timeline.nextWakeUs() != null) ui.postDelayed(this, 1)
                else ui.postDelayed({
                    if (epoch != token || destroyed) return@postDelayed
                    status.text = "$asset\n帧=$inputFrames，事件=$acceptedEvents\n轻点调用=$calls，连续调用=$waves\n可重放比较；调用计数不代表马达逐个完成。"
                    LimeLog.info("ExtendedReplay asset=$asset frames=$inputFrames events=$acceptedEvents tickCalls=$calls waves=$waves")
                }, 250)
            }
        }
        status.text = "即将回放 $asset\nTICK能力=$supportsTick；单手机持续层优先，独立短落点用轻点。"
        ui.post(pump)
    }

    private fun stop() {
        epoch++
        ui.removeCallbacksAndMessages(null)
        device?.stop()
        device = null
    }

    override fun onPause() { stop(); super.onPause() }
    override fun onDestroy() { destroyed = true; stop(); super.onDestroy() }
}
