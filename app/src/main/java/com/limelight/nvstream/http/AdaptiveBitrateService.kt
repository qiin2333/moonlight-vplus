/*
 * Moonlight for Android - Adaptive Bitrate Service
 *
 * 智能码率调节，移植自 HarmonyOS 版本：
 *   1. 优先让 Sunshine 服务端做码率决策（ABR API feedback 模式）
 *   2. 服务端不支持时回退到客户端本地控制器（PID 风格）
 *
 * 客户端每秒上报网络指标 → 服务端提交码率目标 → 客户端同步目标镜像
 */
package com.limelight.nvstream.http

import com.limelight.LimeLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AdaptiveBitrateService internal constructor(
    private val transportFactory: () -> AbrTransport,
    private val statsProvider: () -> AbrStats?,
    /** 旧 API 已提交的目标镜像。仅更新本地配置，不代表编码器生效回执。 */
    private val onBitrateChanged: (bitrateKbps: Int, reason: String) -> Unit,
    private val executor: ScheduledExecutorService
) {
    constructor(
        nvHttpFactory: () -> NvHTTP,
        statsProvider: () -> AbrStats?,
        onBitrateChanged: (bitrateKbps: Int, reason: String) -> Unit
    ) : this(
        { NvHttpAbrTransport(nvHttpFactory()) },
        statsProvider,
        onBitrateChanged,
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AdaptiveBitrateService").apply { isDaemon = true }
        }
    )

    data class AbrStats(
        val packetLoss: Float,    // %
        val rttMs: Int,           // 网络 RTT
        val decodeFps: Float,     // 解码 FPS
        val droppedFrames: Int    // 累计丢帧
    )

    private var future: ScheduledFuture<*>? = null

    @Volatile private var transport: AbrTransport? = null
    @Volatile private var closed = false
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var serverSupported: Boolean = false
        private set
    @Volatile var currentBitrate: Int = 0
        private set

    /** UI 可订阅码率变更事件（在 service 线程上回调，召者需自行 post 到主线程）。*/
    @Volatile var bitrateListener: ((bitrateKbps: Int, reason: String) -> Unit)? = null

    private var initialBitrate: Int = 0
    private var mode: String = MODE_BALANCED
    private var minBitrate: Int = 3000
    private var maxBitrate: Int = 100_000

    // 本地 fallback 控制器状态
    private var stableSeconds = 0
    private var lossStreak = 0
    private var lastAdjustWallClock = 0L
    private var lastDirection = DIR_NONE

    // 服务端启用重试
    private var serverEnableRetries = 0
    private var serverRetryTickCounter = 0

    /**
     * 启动 ABR。会在后台线程探测服务端能力。
     * @param initialBitrate 当前码率（kbps），ABR 围绕此值上下浮动
     */
    @Synchronized fun start(initialBitrate: Int, mode: String) {
        if (enabled || closed) return
        this.initialBitrate = initialBitrate
        this.currentBitrate = initialBitrate
        this.mode = mode
        applyModePreset(mode)
        resetState()
        enabled = true

        executor.execute {
            try {
                if (!enabled) return@execute
                val http = transportFactory()
                transport = http
                val caps = http.getAbrCapabilities()
                synchronized(this) {
                    if (!enabled) return@execute
                    serverSupported = caps.supported
                    if (caps.supported) serverEnableRetries = 1
                }
                LimeLog.info("[ABR] 启动: bitrate=${initialBitrate}kbps, mode=$mode, server=${caps.supported} (v${caps.version})")
            } catch (e: Exception) {
                LimeLog.warning("[ABR] 服务端能力探测失败: ${e.message}")
            }
        }

        // 使用 scheduleWithFixedDelay 而非 scheduleAtFixedRate：
        // Android 进程被 cached 后唤醒时，fixedRate 会"补跑"积压的几百上千次 tick，
        // 而 fixedDelay 只在每次执行完成后再等待 1 秒，避免突发风暴。
        if (closed) return
        future = executor.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                LimeLog.warning("[ABR] tick 异常: ${e.message}")
            }
        }, START_DELAY_SECONDS, 1, TimeUnit.SECONDS)
    }

    /** 用户手动调了码率（如游戏菜单滑块），ABR 同步基准并重置探测状态。*/
    @Synchronized fun notifyManualOverride(kbps: Int) {
        if (!enabled) return
        currentBitrate = kbps
        stableSeconds = 0
        lossStreak = 0
        lastAdjustWallClock = System.currentTimeMillis()
        LimeLog.info("[ABR] 手动覆盖码率 -> ${kbps}kbps")
    }

    @Synchronized fun stop() {
        if (closed) return
        closed = true
        val wasServerSupported = serverSupported
        enabled = false
        serverSupported = false
        bitrateListener = null
        future?.cancel(false)
        future = null

        // 通知服务端关闭并恢复初始码率
        executor.execute {
            try {
                if (wasServerSupported) {
                    transport?.setAbrMode(AbrConfig(false, 0, 0, MODE_BALANCED))
                }
                if (currentBitrate != initialBitrate) {
                    applyBitrateInternal(initialBitrate, "restore", allowStopped = true)
                }
            } catch (e: Exception) {
                LimeLog.warning("[ABR] stop 异常: ${e.message}")
            }
            LimeLog.info("[ABR] 停止，恢复码率: ${initialBitrate}kbps")
        }
        executor.shutdown()
    }

    /** Teardown waits off the UI thread before allowing another host negotiation. */
    fun awaitStopped(timeout: Long, unit: TimeUnit): Boolean = executor.awaitTermination(timeout, unit)

    /** 用于性能面板显示当前 ABR 状态。*/
    fun getStatusText(): String {
        if (!enabled) return ""
        val sub = when {
            serverSupported && serverEnableRetries == 0 -> "server"
            serverSupported && serverEnableRetries > 0 -> "connecting"
            else -> "local"
        }
        return "ABR:$sub ${currentBitrate / 1000}M"
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun applyModePreset(mode: String) {
        when (mode) {
            MODE_QUALITY -> {
                minBitrate = maxOf(5000, (initialBitrate * 0.5).toInt())
                maxBitrate = minOf(150_000, (initialBitrate * 1.5).toInt())
            }
            MODE_LOW_LATENCY -> {
                minBitrate = 2000
                maxBitrate = (initialBitrate * 1.2).toInt()
            }
            else -> {
                minBitrate = maxOf(3000, (initialBitrate * 0.3).toInt())
                maxBitrate = minOf(150_000, initialBitrate * 2)
            }
        }
    }

    private fun resetState() {
        stableSeconds = 0
        lossStreak = 0
        lastAdjustWallClock = 0L
        lastDirection = DIR_NONE
        serverEnableRetries = 0
        serverRetryTickCounter = 0
    }

    private fun tick() {
        if (!enabled) return
        val http = transport ?: return
        val stats = statsProvider() ?: return
        if (!enabled) return

        // 服务端启用惰性重试
        if (serverSupported && serverEnableRetries > 0) {
            if (++serverRetryTickCounter >= SERVER_RETRY_INTERVAL_TICKS) {
                serverRetryTickCounter = 0
                val ok = http.setAbrMode(AbrConfig(true, minBitrate, maxBitrate, mode))
                if (!enabled) return
                if (ok) {
                    LimeLog.info("[ABR] 服务端 ABR 启用成功（第 $serverEnableRetries 次）")
                    serverEnableRetries = 0
                } else if (++serverEnableRetries > MAX_SERVER_ENABLE_RETRIES) {
                    serverSupported = false
                    LimeLog.warning("[ABR] 服务端重试 $MAX_SERVER_ENABLE_RETRIES 次失败，降级到本地控制器")
                }
            }
        }

        if (!enabled) return
        if (serverSupported && serverEnableRetries == 0) {
            tickServer(http, stats)
        } else {
            tickLocal(stats)
        }
    }

    private fun tickServer(http: AbrTransport, stats: AbrStats) {
        val feedback = NetworkFeedback(
            packetLoss = stats.packetLoss,
            rttMs = stats.rttMs,
            decodeFps = stats.decodeFps,
            droppedFrames = stats.droppedFrames,
            currentBitrate = currentBitrate
        )
        val action = http.reportNetworkFeedback(feedback) ?: return
        if (!enabled) return
        val newBitrate = action.newBitrate ?: return
        if (action.bitrateApplied == false || newBitrate <= 0 || newBitrate == currentBitrate) return
        // /api/abr/feedback 已向会话提交此目标；再次 setBitrate 会重复执行。
        // 主机上限可能低于本地 minBitrate，镜像须保留主机实际返回的目标。
        updateBitrateMirror(newBitrate, action.reason ?: "server", source = "server")
    }

    /** 内部统一码率应用：复用缓存的 nvHttp 实例，避免每次新建 OkHttpClient + TLS。*/
    private fun applyBitrateInternal(kbps: Int, reason: String, source: String = "local", allowStopped: Boolean = false): Boolean {
        if (!enabled && !allowStopped) return false
        val http = transport ?: return false
        return try {
            if (http.setBitrate(kbps)) {
                updateBitrateMirror(kbps, reason, source, allowStopped)
                true
            } else false
        } catch (e: Exception) {
            LimeLog.warning("[ABR] setBitrate 失败: ${e.message}")
            false
        }
    }

    @Synchronized private fun updateBitrateMirror(kbps: Int, reason: String, source: String, allowStopped: Boolean = false) {
        if (!enabled && !allowStopped) return
        val from = currentBitrate
        currentBitrate = kbps
        LimeLog.info("[ABR][$source] target ${from}kbps -> ${kbps}kbps ($reason)")
        onBitrateChanged(kbps, reason)
        try { bitrateListener?.invoke(kbps, reason) } catch (_: Exception) {}
    }

    private fun tickLocal(stats: AbrStats) {
        val now = System.currentTimeMillis()
        val cooldown = if (mode == MODE_LOW_LATENCY) 1500 else 2000
        if (now - lastAdjustWallClock < cooldown) return

        var newBitrate = currentBitrate.toDouble()
        var reason = ""

        when {
            stats.packetLoss > 5f -> {
                newBitrate = currentBitrate * 0.7
                reason = "loss=%.1f%% emergency".format(stats.packetLoss)
                stableSeconds = 0
                lossStreak++
            }
            stats.packetLoss > 2f -> {
                lossStreak++
                if (lossStreak >= 2) {
                    newBitrate = currentBitrate * 0.9
                    reason = "loss=%.1f%% sustained".format(stats.packetLoss)
                    stableSeconds = 0
                }
            }
            stats.packetLoss > 0.5f -> {
                lossStreak++
                stableSeconds = 0
                if (lossStreak >= 4) {
                    newBitrate = currentBitrate * 0.95
                    reason = "loss=%.1f%% mild".format(stats.packetLoss)
                }
            }
            else -> {
                lossStreak = 0
                stableSeconds++
                val probeThreshold = if (mode == MODE_QUALITY) 3 else 5
                if (stableSeconds >= probeThreshold && currentBitrate < maxBitrate) {
                    val step = if (lastDirection == DIR_DOWN) 1.02 else 1.05
                    newBitrate = currentBitrate * step
                    reason = "stable ${stableSeconds}s probe"
                    stableSeconds = 0
                }
            }
        }

        val target = newBitrate.toInt().coerceIn(minBitrate, maxBitrate)
        if (target != currentBitrate) {
            val direction = if (target > currentBitrate) DIR_UP else DIR_DOWN
            if (applyBitrateInternal(target, reason, source = "local")) {
                lastDirection = direction
                lastAdjustWallClock = now
            }
        }
    }

    companion object {
        const val MODE_QUALITY = "quality"
        const val MODE_BALANCED = "balanced"
        const val MODE_LOW_LATENCY = "lowLatency"

        private const val START_DELAY_SECONDS = 3L
        private const val SERVER_RETRY_INTERVAL_TICKS = 5
        private const val MAX_SERVER_ENABLE_RETRIES = 10

        private const val DIR_NONE = 0
        private const val DIR_UP = 1
        private const val DIR_DOWN = -1
    }
}

internal interface AbrTransport {
    fun getAbrCapabilities(): AbrCapabilities
    fun setAbrMode(config: AbrConfig): Boolean
    fun reportNetworkFeedback(feedback: NetworkFeedback): AbrAction?
    fun setBitrate(kbps: Int): Boolean
}

private class NvHttpAbrTransport(private val http: NvHTTP) : AbrTransport {
    override fun getAbrCapabilities() = http.getAbrCapabilities()
    override fun setAbrMode(config: AbrConfig) = http.setAbrMode(config)
    override fun reportNetworkFeedback(feedback: NetworkFeedback) = http.reportNetworkFeedback(feedback)
    override fun setBitrate(kbps: Int) = http.setBitrate(kbps)
}
