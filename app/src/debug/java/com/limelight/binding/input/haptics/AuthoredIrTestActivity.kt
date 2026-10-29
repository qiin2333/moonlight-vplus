package com.limelight.binding.input.haptics

import android.app.Activity
import android.media.AudioAttributes
import android.os.*
import android.widget.*
import com.limelight.LimeLog
import com.limelight.nvstream.*

/** Host analyzer fixtures fed into the production coordinator and device sink. */
class AuthoredIrTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var vibrator: Vibrator
    private var stopPlayback: (() -> Unit)? = null
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
        val resultFile = java.io.File(filesDir, "authored-ir-replay.json")
        resultFile.writeText(org.json.JSONObject().put("state", "preparing").put("asset", asset)
            .put("nativeRequested", intent.getBooleanExtra("native", false)).toString())
        val token = epoch
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val waves = java.util.concurrent.atomic.AtomicInteger()
        val nativeCalls = java.util.concurrent.atomic.AtomicInteger()
        val nativePoints = java.util.concurrent.atomic.AtomicInteger()
        var acceptedEvents = 0
        var inputFrames = 0
        var index = 0
        val active = { !destroyed && epoch == token }
        val postDelayed: (Runnable, Long) -> Unit = { task, delay -> ui.postDelayed(task, delay); Unit }
        val supportsTick = com.limelight.binding.input.ControllerRumbleManager.supportsDeviceDetailTick(vibrator)
        val native = if (Build.VERSION.SDK_INT >= 29 && intent.getBooleanExtra("native", false))
            SoundPoolAuthoredHaptics.create(this) else null
        val nativeSink = native?.let { backend -> object : NativeAuthoredHaptics {
            override val ready get() = backend.ready
            override fun render(body: Float, point: Float, keepWarm: Boolean): Long? {
                nativeCalls.incrementAndGet()
                if (point > 0) nativePoints.incrementAndGet()
                return backend.render(body, point, keepWarm)
            }
            override fun stop() = backend.stop()
            override fun close() = backend.close()
        } }
        val sink = DeviceVibrationCoordinator(
            postDelayed, ui::removeCallbacks,
            { amplitude, duration ->
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude), attributes)
                else vibrator.vibrate(duration, attributes)
                waves.incrementAndGet()
            }, vibrator::cancel, playDeviceTick = if (supportsTick) ({
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attributes)
                calls.incrementAndGet()
                Unit
            }) else null, clockMs = SystemClock::elapsedRealtime, nativeHaptics = nativeSink)
        val mixer = ControllerHapticsMixer()
        val renderer = RumbleOutputRenderer(
            mixer, ControllerHapticsMixer(), SystemClock::elapsedRealtime, postDelayed, ui::removeCallbacks,
            controllerAvailable = { false }, controllerIntervalMs = { 20L }, writeController = {},
            writeDeviceAmplitude = {
                sink.submitGameRumble(DeviceVibrationCoordinator.GameSource.ROUTED_GAME, it, 100)
            }, enabled = active)
        val coordinator = AuthoredIrCoordinator(
            mixer, renderer, sink,
            context = { GameRumbleContext(GameRumbleMode.DEVICE, hasController = false, hasDevice = vibrator.hasVibrator()) },
            supportsTick = { supportsTick }, strengthPercent = { 100 }, clockMs = SystemClock::elapsedRealtime,
            postDelayed = postDelayed, removeCallbacks = ui::removeCallbacks,
            // Device-only replay has no controller sources to expire.
            scheduleExpiry = {}, enabled = active,
            onOutput = { _, output -> if (output.event != null) acceptedEvents++ })
        stopPlayback = {
            coordinator.stop()
            renderer.stop()
            sink.stop()
        }
        var origin = SystemClock.elapsedRealtime() + 400
        val sourceOrigin = frames.first().windowEndUs
        val report = Runnable {
            if (!active()) return@Runnable
            status.text = "$asset\n帧=$inputFrames，事件=$acceptedEvents\n原生渲染=$nativeCalls，原生短点=$nativePoints\n轻点调用=$calls，连续调用=$waves\n调用计数不代表马达逐个完成。"
            resultFile.writeText(org.json.JSONObject().put("state", "completed").put("asset", asset)
                .put("frames", inputFrames).put("events", acceptedEvents)
                .put("nativeRenders", nativeCalls.get()).put("nativePoints", nativePoints.get())
                .put("nativeReady", nativeSink?.ready == true).put("ticks", calls.get())
                .put("waveforms", waves.get()).toString())
            LimeLog.info("ExtendedReplay asset=$asset frames=$inputFrames events=$acceptedEvents tickCalls=$calls waves=$waves nativeCalls=$nativeCalls nativePoints=$nativePoints")
        }
        val pump = object : Runnable {
            override fun run() {
                if (destroyed || epoch != token) return
                val now = SystemClock.elapsedRealtime()
                while (index < frames.size && origin + (frames[index].windowEndUs - sourceOrigin) / 1000 <= now) {
                    coordinator.submit(frames[index++])
                    inputFrames++
                }
                if (index < frames.size) {
                    val next = origin + (frames[index].windowEndUs - sourceOrigin) / 1000
                    ui.postDelayed(this, (next - now).coerceAtLeast(1))
                } else ui.postDelayed(report, 250)
            }
        }
        status.text = "即将回放 $asset\nTICK能力=$supportsTick；单手机持续层优先，独立短落点用轻点。"
        if (nativeSink == null) ui.post(pump)
        else {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            val awaitReady = object : Runnable {
                override fun run() {
                    if (!active()) return
                    if (nativeSink.ready) {
                        origin = SystemClock.elapsedRealtime() + 400
                        ui.post(pump)
                    } else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed(this, 50)
                    else { status.text = "原生触觉资产未就绪，本轮未播放"; stop() }
                }
            }
            status.text = "正在准备原生设备触觉：$asset"
            ui.post(awaitReady)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("asset")?.let(::start)
    }

    private fun stop() {
        epoch++
        ui.removeCallbacksAndMessages(null)
        stopPlayback?.invoke()
        stopPlayback = null
    }

    override fun onPause() { stop(); super.onPause() }
    override fun onDestroy() { destroyed = true; stop(); super.onDestroy() }
}
