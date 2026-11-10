package com.limelight.perfhud

/**
 * 性能覆盖层的显示样式。CLASSIC 保留旧版逐行文本覆盖层，其余三档为新版 HUD 的信息密度。
 */
enum class PerfHudDensity(val prefValue: String) {
    MINI("mini"),
    STANDARD("standard"),
    DETAILED("detailed"),
    CLASSIC("classic");

    val isHud: Boolean get() = this != CLASSIC

    /** 单击覆盖层时的循环顺序：迷你 → 标准 → 详细 → 迷你。经典样式不参与循环。 */
    fun next(): PerfHudDensity = when (this) {
        MINI -> STANDARD
        STANDARD -> DETAILED
        DETAILED -> MINI
        CLASSIC -> CLASSIC
    }

    companion object {
        val DEFAULT = STANDARD

        fun fromPreference(value: String?): PerfHudDensity =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

/** HUD 实际绘制的形态，由密度 + 横竖屏 + 可用空间共同决定。 */
enum class PerfHudLayout {
    /** 迷你胶囊：FPS + 估算延迟。 */
    CAPSULE,
    /** 横屏顶部信息条。 */
    BAR,
    /** 紧凑竖条（竖屏，或横屏下用户双击切到竖排）。 */
    COLUMN,
    /** 浮动详情面板。 */
    PANEL,
    /** 竖屏停靠在视频下方黑边里的卡片。 */
    DOCK
}

enum class PerfSeverity {
    OK, WARN, BAD;

    fun worst(other: PerfSeverity): PerfSeverity = if (other.ordinal > ordinal) other else this
}

/** 长按命中的指标，用来打开对应说明。 */
enum class PerfHudTarget {
    MOON, RESOLUTION, DECODER, FPS, ONE_PERCENT_LOW, LATENCY, NETWORK, DECODE, HOST, LOSS, BATTERY, CLOCK, DIAGNOSIS
}

/** 状态文案（复用项目里的 🌙 / 🥵 / 喵 / 投喂 等梗）。 */
enum class PerfMood {
    WARMING_UP, SMOOTH, FULL_MOON_SMOOTH, HOT_DECODE, LOSSY, FPS_DROP, LAGGY_NETWORK, LOW_BATTERY
}

object PerfHudThresholds {
    /** 与旧版 🥵 阈值保持一致。 */
    const val HOT_DECODE_MS = 15f
    const val LOSS_WARN_PERCENT = 1f
    /** 与旧版丢包变红阈值保持一致。 */
    const val LOSS_BAD_PERCENT = 5f
    const val RTT_WARN_MS = 40
    const val RTT_BAD_MS = 80
    const val BATTERY_WARN_LEVEL = 20
    const val BATTERY_BAD_LEVEL = 10

    fun fps(fps: Float, targetFps: Int): PerfSeverity = ratio(fps, targetFps, 0.95f, 0.80f)

    fun onePercentLow(lowFps: Float, targetFps: Int): PerfSeverity {
        if (lowFps <= 0f) return PerfSeverity.OK
        return ratio(lowFps, targetFps, 0.85f, 0.65f)
    }

    fun packetLoss(lossPercent: Float): PerfSeverity = when {
        lossPercent >= LOSS_BAD_PERCENT -> PerfSeverity.BAD
        lossPercent >= LOSS_WARN_PERCENT -> PerfSeverity.WARN
        else -> PerfSeverity.OK
    }

    fun decode(decodeMs: Float, targetFps: Int): PerfSeverity {
        if (decodeMs >= HOT_DECODE_MS) return PerfSeverity.BAD
        val frameMs = if (targetFps > 0) 1000f / targetFps else Float.MAX_VALUE
        return if (decodeMs > frameMs) PerfSeverity.WARN else PerfSeverity.OK
    }

    fun rtt(rttMs: Int): PerfSeverity = when {
        rttMs >= RTT_BAD_MS -> PerfSeverity.BAD
        rttMs >= RTT_WARN_MS -> PerfSeverity.WARN
        else -> PerfSeverity.OK
    }

    fun battery(level: Int, charging: Boolean): PerfSeverity = when {
        level < 0 || charging -> PerfSeverity.OK
        level <= BATTERY_BAD_LEVEL -> PerfSeverity.BAD
        level <= BATTERY_WARN_LEVEL -> PerfSeverity.WARN
        else -> PerfSeverity.OK
    }

    private fun ratio(value: Float, target: Int, warnBelow: Float, badBelow: Float): PerfSeverity {
        if (target <= 0 || value <= 0f) return PerfSeverity.OK
        val r = value / target
        return when {
            r < badBelow -> PerfSeverity.BAD
            r < warnBelow -> PerfSeverity.WARN
            else -> PerfSeverity.OK
        }
    }
}

/**
 * 端到端延迟的估算拆分。网络段取 RTT 的一半作为单程估计，主机段缺失时为 null（显示 🧋）。
 */
data class LatencyChain(
    val networkMs: Float,
    val hostMs: Float?,
    val decodeMs: Float,
    val renderMs: Float
) {
    val totalMs: Float get() = networkMs + (hostMs ?: 0f) + decodeMs + renderMs

    /** 网络 / 主机 / 解码 / 渲染 四段及其配色；HUD 分段条、图例和说明卡片共用同一顺序。主机缺失时按 0 计。 */
    fun segments(): List<ChainSegment> = listOf(
        ChainSegment(PerfHudPalette.NET, networkMs),
        ChainSegment(PerfHudPalette.HOST, hostMs ?: 0f),
        ChainSegment(PerfHudPalette.DEC, decodeMs),
        ChainSegment(PerfHudPalette.RND, renderMs)
    )

    companion object {
        fun estimate(rttMs: Int, hostMs: Float?, decodeMs: Float, renderMs: Float) = LatencyChain(
            networkMs = rttMs.coerceAtLeast(0) / 2f,
            hostMs = hostMs?.coerceAtLeast(0f),
            decodeMs = decodeMs.coerceAtLeast(0f),
            renderMs = renderMs.coerceAtLeast(0f)
        )
    }
}

/** 延迟链的一段：分类色 + 毫秒数。 */
data class ChainSegment(val color: Int, val ms: Float)

/** 固定容量的浮点环形缓冲，用于 FPS 历史曲线。 */
class FloatRing(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun add(value: Float) {
        if (size < capacity) {
            data[(start + size) % capacity] = value
            size++
        } else {
            data[start] = value
            start = (start + 1) % capacity
        }
    }

    operator fun get(index: Int): Float {
        require(index in 0 until size)
        return data[(start + index) % capacity]
    }

    fun clear() {
        start = 0
        size = 0
    }

    fun max(): Float {
        var m = 0f
        for (i in 0 until size) m = maxOf(m, get(i))
        return m
    }
}

data class PerfHudSnapshot(
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val codec: String,
    val hdrLabel: String?,
    val decoderName: String,
    val renderedFps: Float,
    val receivedFps: Float,
    val framegenFps: Float,
    val onePercentLowFps: Float,
    val packetLossPercent: Float,
    val rttMs: Int,
    val rttVarianceMs: Int,
    val bandwidth: String,
    val latency: LatencyChain,
    val batteryLevel: Int,
    val charging: Boolean,
    val drainPercentPerHour: Float?,
    val clock: String?,
    val moonIcon: String,
    val fullMoon: Boolean
) {
    val fpsSeverity get() = PerfHudThresholds.fps(renderedFps, targetFps)
    /** 主机送帧是否跟得上目标帧率。 */
    val rxSeverity get() = PerfHudThresholds.fps(receivedFps, targetFps)
    val onePercentLowSeverity get() = PerfHudThresholds.onePercentLow(onePercentLowFps, targetFps)
    val lossSeverity get() = PerfHudThresholds.packetLoss(packetLossPercent)
    val decodeSeverity get() = PerfHudThresholds.decode(latency.decodeMs, targetFps)
    val rttSeverity get() = PerfHudThresholds.rtt(rttMs)
    val batterySeverity get() = PerfHudThresholds.battery(batteryLevel, charging)
    val isDecodeHot get() = latency.decodeMs >= PerfHudThresholds.HOT_DECODE_MS

    /** 月相光圈颜色：只看影响串流体验的指标，电量不参与。 */
    val overallSeverity: PerfSeverity
        get() = fpsSeverity.worst(lossSeverity).worst(decodeSeverity).worst(rttSeverity)

    val mood: PerfMood
        get() = when {
            renderedFps <= 0f && receivedFps <= 0f -> PerfMood.WARMING_UP
            isDecodeHot -> PerfMood.HOT_DECODE
            lossSeverity != PerfSeverity.OK -> PerfMood.LOSSY
            fpsSeverity != PerfSeverity.OK -> PerfMood.FPS_DROP
            rttSeverity == PerfSeverity.BAD -> PerfMood.LAGGY_NETWORK
            batterySeverity == PerfSeverity.BAD -> PerfMood.LOW_BATTERY
            fullMoon -> PerfMood.FULL_MOON_SMOOTH
            else -> PerfMood.SMOOTH
        }

    companion object {
        /** 串流至少 5 分钟且未充电时才给出耗电速度，避免开局几秒的抖动数字。 */
        const val MIN_DRAIN_SAMPLE_MS = 5 * 60_000L

        fun drainPercentPerHour(startLevel: Int, level: Int, elapsedMs: Long, charging: Boolean): Float? {
            if (charging || startLevel < 0 || level < 0 || elapsedMs < MIN_DRAIN_SAMPLE_MS) return null
            val consumed = startLevel - level
            if (consumed <= 0) return 0f
            return consumed * 3_600_000f / elapsedMs
        }
    }
}

/** 由 Manager 根据性能显示项偏好生成，HUD 只绘制开启的指标。 */
data class PerfHudVisibility(
    val resolution: Boolean = true,
    val decoder: Boolean = true,
    val fps: Boolean = true,
    val onePercentLow: Boolean = false,
    val loss: Boolean = true,
    val network: Boolean = true,
    val decode: Boolean = true,
    val host: Boolean = true,
    val battery: Boolean = true,
    val clock: Boolean = false
) {
    val stream get() = resolution || decoder
    val latency get() = network || decode || host
}

object PerfHudLayoutPolicy {
    /**
     * @param dockSpaceAvailable 竖屏时视频下方黑边能否容纳停靠卡片（且未被虚拟手柄占用）。
     * 停靠不可用时，竖屏和横屏一样遵循用户双击切换的横/竖排偏好。
     */
    fun resolve(
        density: PerfHudDensity,
        portrait: Boolean,
        verticalPreferred: Boolean,
        dockSpaceAvailable: Boolean
    ): PerfHudLayout = when (density) {
        PerfHudDensity.MINI, PerfHudDensity.CLASSIC -> PerfHudLayout.CAPSULE
        PerfHudDensity.STANDARD -> when {
            portrait && dockSpaceAvailable -> PerfHudLayout.DOCK
            verticalPreferred -> PerfHudLayout.COLUMN
            else -> PerfHudLayout.BAR
        }
        PerfHudDensity.DETAILED ->
            if (portrait && dockSpaceAvailable) PerfHudLayout.DOCK else PerfHudLayout.PANEL
    }
}
