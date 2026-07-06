package com.limelight.binding.video

/**
 * host-cadence 去抖呈现时钟（alpha-beta / PI 时钟恢复）。
 *
 * 将主机 host PTS（以首个被捕获帧为原点、源自 common-c RtpVideoQueue 的 90kHz RTP 时间戳）
 * 恢复为本地单调钟（System.nanoTime 基准）的目标呈现时刻，滤除网络抖动与主机/客户端时钟频差。
 * 与鸿蒙 native_render::CalculatePresentTime 同一模型、同一常数（Kp=1/64、Ki=1/2048），已离线仿真验证。
 *
 * 用法：每帧调用 [presentTimeNs]；流不连续（重连/seek/编码器重启）时内部自动重锚，
 * 也可显式 [reset] 强制重锚。返回值可能早于 now：调用方按“过去时间戳=立即呈现”处理，
 * 网格保持刚性连续（勿把网格拽到到达时刻）。
 *
 * 线程约束：非线程安全，持有内部滤波状态；每条呈现路径应各自持有独立实例。
 *
 * cushion 由构造方注入而非写死，因为其安全下限取决于下游是否有 vsync snap 兜底：
 *  - 直渲染路径（MAX_SMOOTHNESS/CAP_FPS，无 snap）：一帧迟到即可见 hitch，需较大 cushion
 *    （[cushionMul]=3.0、[cushionFloorNs]=1ms），覆盖整个抖动分布。
 *  - PRECISE_SYNC 路径（有 vsync snap）：snap 向上取整自带 ~0.5 vsync 隐性余量，迟到帧只落到
 *    下一个 vsync 槽（= 正常帧行为），故 cushion 可低至 0.5×MAD floor0（延迟增量 +1~3ms）。
 *
 * @param cushionMul    自适应 cushion = cushionMul × 抖动估计(MAD)。
 * @param cushionFloorNs cushion 下限（纳秒）；上限恒为一帧间隔。
 */
class HostCadenceClock(
    private val cushionMul: Double = 0.5,
    private val cushionFloorNs: Long = 0L,
) {
    private var initialized = false
    private var estimatedOffsetNs = 0L   // 平滑后的 (本地单调钟 - host PTS) 偏移均值(纳秒)
    private var skewNs = 0L              // 每帧频差估计(纳秒/帧)，消除时钟 skew 斜坡滞后
    private var jitterEstNs = 0.0        // 在线抖动估计(平均绝对偏差, 纳秒)，驱动自适应 cushion
    private var lastHostPtsUs = 0L       // 上一帧 host PTS(微秒)，检测不连续(重连/跳变)

    /** 强制下一帧重锚（如流重启/surface 重建后调用）。 */
    fun reset() {
        initialized = false
    }

    /**
     * 计算本帧目标呈现时刻。
     *
     * @param hostPtsUs        host PTS（微秒，以首个被捕获帧为原点）。
     * @param frameIntervalNs  一帧标称间隔（纳秒），用于 cushion 上限与抖动初值；调用方按显示刷新率给出。
     * @return 本地单调钟(nanoTime 基准)的目标呈现时刻(纳秒)；可能早于当前时刻。
     */
    fun presentTimeNs(hostPtsUs: Long, frameIntervalNs: Long): Long {
        val nowNs = System.nanoTime()
        val hostNs = hostPtsUs * 1000L
        val instOffset = nowNs - hostNs

        val discontinuity = initialized &&
            (hostPtsUs < lastHostPtsUs || (hostPtsUs - lastHostPtsUs) > 2_000_000L)

        if (!initialized || discontinuity) {
            estimatedOffsetNs = instOffset
            skewNs = 0
            jitterEstNs = frameIntervalNs / 16.0
            initialized = true
        } else {
            val pred = estimatedOffsetNs + skewNs
            val e = instOffset - pred
            var ec = e
            if (ec > 8_000_000L) ec = 8_000_000L else if (ec < -8_000_000L) ec = -8_000_000L
            estimatedOffsetNs = pred + (ec / 64)    // Kp=1/64: 跟踪偏移均值、网格近似刚性
            skewNs += (ec / 2048)                    // Ki=1/2048: 跟踪频差，消除斜坡滞后
            val ae = if (e < 0) (-e).toDouble() else e.toDouble()
            jitterEstNs += (ae - jitterEstNs) / 32.0
        }
        lastHostPtsUs = hostPtsUs

        var cushionNs = (cushionMul * jitterEstNs).toLong()
        if (cushionNs < cushionFloorNs) cushionNs = cushionFloorNs
        else if (cushionNs > frameIntervalNs) cushionNs = frameIntervalNs

        return hostNs + estimatedOffsetNs + cushionNs
    }
}
