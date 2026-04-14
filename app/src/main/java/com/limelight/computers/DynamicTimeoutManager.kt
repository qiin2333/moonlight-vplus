package com.limelight.computers

import com.limelight.LimeLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 动态超时管理器 - 根据网络类型和历史连接结果动态调整超时时间
 */
class DynamicTimeoutManager(private val networkDiagnostics: NetworkDiagnostics) {

    /**
     * 基础超时配置
     */
    class TimeoutConfig(
        var connectTimeout: Int,
        var readTimeout: Int,
        var stunTimeout: Int
    ) {
        override fun toString(): String {
            return String.format("TimeoutConfig{connect=%dms, read=%dms, stun=%dms}",
                connectTimeout, readTimeout, stunTimeout)
        }
    }

    private val addressStats = ConcurrentHashMap<String, ConnectionStats>()

    @Volatile
    private var isNetworkUnstable = false
    @Volatile
    private var lastUnstableTime: Long = 0

    /**
     * 单个地址的连接统计
     */
    private class ConnectionStats {
        private val successCount = AtomicInteger(0)
        private val failureCount = AtomicInteger(0)

        @Volatile
        var lastSuccessTime: Long = 0

        @Volatile
        var lastFailureTime: Long = 0

        @Volatile
        var consecutiveFailures: Int = 0

        @Volatile
        var averageResponseTimeMs: Long = 0

        fun recordSuccess(responseTime: Long) {
            successCount.incrementAndGet()
            lastSuccessTime = System.currentTimeMillis()
            consecutiveFailures = 0

            val total = averageResponseTimeMs * (successCount.get() - 1) + responseTime
            averageResponseTimeMs = total / successCount.get()
        }

        fun recordFailure() {
            failureCount.incrementAndGet()
            lastFailureTime = System.currentTimeMillis()
            consecutiveFailures++
        }

        val successRate: Double
            get() {
                val total = successCount.get() + failureCount.get()
                return if (total == 0) 1.0 else successCount.get().toDouble() / total
            }

        val isHealthy: Boolean
            get() = successRate >= 0.5 && consecutiveFailures < 3

        override fun toString(): String {
            return String.format("Stats{success=%d, failure=%d, rate=%.1f%%, consecutive_failures=%d, avg_response=%dms}",
                successCount.get(), failureCount.get(), successRate * 100, consecutiveFailures, averageResponseTimeMs)
        }
    }

    /**
     * 获取动态超时配置
     */
    fun getDynamicTimeoutConfig(address: String?, isLikelyOnline: Boolean): TimeoutConfig {
        val diagnostics = networkDiagnostics.getLastDiagnostics()

        if (isNetworkUnstable) {
            if (isNetworkUnstableRecovered()) {
                isNetworkUnstable = false
                LimeLog.info("Network recovered from unstable state")
            } else {
                LimeLog.info("Network is unstable, using extended timeouts")
                return DEFAULT_UNSTABLE_CONFIG
            }
        }

        val stats = if (address != null) addressStats[address] else null

        val baseConfig = getBaseConfig(diagnostics)

        if (stats != null && !stats.isHealthy) {
            LimeLog.warning("Address $address is unhealthy: $stats")
            return increaseTimeouts(baseConfig, 1.5)
        }

        return baseConfig
    }

    /**
     * 获取用于快速查询的超时配置 - 如果失败要快速放弃
     */
    fun getFastFailConfig(): TimeoutConfig {
        val diagnostics = networkDiagnostics.getLastDiagnostics()

        return when (diagnostics.networkType) {
            NetworkDiagnostics.NetworkType.LAN -> FAST_FAIL_LAN_CONFIG
            NetworkDiagnostics.NetworkType.WAN,
            NetworkDiagnostics.NetworkType.MOBILE -> FAST_FAIL_WAN_CONFIG
            NetworkDiagnostics.NetworkType.VPN -> FAST_FAIL_WAN_CONFIG
            else -> FAST_FAIL_WAN_CONFIG
        }
    }

    /**
     * 获取STUN超时配置
     */
    val stunTimeout: Int
        get() = getDynamicTimeoutConfig(null, false).stunTimeout

    /**
     * 获取基础超时配置
     */
    private fun getBaseConfig(diagnostics: NetworkDiagnostics.NetworkDiagnosticsSnapshot): TimeoutConfig {
        return when (diagnostics.networkType) {
            NetworkDiagnostics.NetworkType.LAN -> DEFAULT_LAN_CONFIG
            NetworkDiagnostics.NetworkType.MOBILE -> DEFAULT_MOBILE_CONFIG
            NetworkDiagnostics.NetworkType.WAN -> {
                when {
                    diagnostics.networkQuality == NetworkDiagnostics.NetworkQuality.POOR ->
                        increaseTimeouts(DEFAULT_WAN_CONFIG, 1.5)
                    diagnostics.networkQuality == NetworkDiagnostics.NetworkQuality.EXCELLENT ->
                        DEFAULT_LAN_CONFIG
                    else -> DEFAULT_WAN_CONFIG
                }
            }
            NetworkDiagnostics.NetworkType.VPN -> increaseTimeouts(DEFAULT_WAN_CONFIG, 1.2)
            else -> DEFAULT_WAN_CONFIG
        }
    }

    /**
     * 增加超时时间
     */
    private fun increaseTimeouts(original: TimeoutConfig, factor: Double): TimeoutConfig {
        return TimeoutConfig(
            (original.connectTimeout * factor).toInt(),
            (original.readTimeout * factor).toInt(),
            (original.stunTimeout * factor).toInt()
        )
    }

    /**
     * 记录连接成功
     */
    fun recordSuccess(address: String, responseTimeMs: Long) {
        val stats = addressStats.computeIfAbsent(address) { ConnectionStats() }
        stats.recordSuccess(responseTimeMs)

        isNetworkUnstable = false
        lastUnstableTime = 0

        LimeLog.info("Connection success for $address: $stats")
    }

    /**
     * 记录连接失败
     */
    fun recordFailure(address: String) {
        val stats = addressStats.computeIfAbsent(address) { ConnectionStats() }
        stats.recordFailure()

        if (stats.consecutiveFailures >= 3) {
            markNetworkUnstable()
        }

        LimeLog.warning("Connection failure for $address: $stats")
    }

    /**
     * 标记网络不稳定
     */
    private fun markNetworkUnstable() {
        if (!isNetworkUnstable) {
            isNetworkUnstable = true
            lastUnstableTime = System.currentTimeMillis()
            LimeLog.warning("Network marked as unstable, will extend timeouts")
        }
    }

    /**
     * 检查网络是否已从不稳定状态恢复
     */
    private fun isNetworkUnstableRecovered(): Boolean {
        return System.currentTimeMillis() - lastUnstableTime >= UNSTABLE_RECOVERY_TIME_MS
    }

    /**
     * 重置所有统计信息
     */
    fun resetStatistics() {
        addressStats.clear()
        isNetworkUnstable = false
        lastUnstableTime = 0
        LimeLog.info("Connection statistics reset")
    }

    /**
     * 获取地址统计信息（调试用）
     */
    fun getStatisticsDebugInfo(): String {
        val sb = StringBuilder()
        sb.append("DynamicTimeoutManager Statistics:\n")
        for ((key, value) in addressStats) {
            sb.append("  ").append(key).append(": ").append(value).append("\n")
        }
        sb.append("  Network unstable: ").append(isNetworkUnstable).append("\n")
        return sb.toString()
    }

    companion object {
        // 默认超时配置
        private val DEFAULT_LAN_CONFIG = TimeoutConfig(3000, 7000, 2000)
        private val DEFAULT_WAN_CONFIG = TimeoutConfig(8000, 12000, 5000)
        private val DEFAULT_MOBILE_CONFIG = TimeoutConfig(10000, 15000, 8000)
        private val DEFAULT_UNSTABLE_CONFIG = TimeoutConfig(15000, 20000, 10000)

        // LAN环境中的最快超时 - 用于快速失败
        private val FAST_FAIL_LAN_CONFIG = TimeoutConfig(1000, 2000, 500)
        // WAN环境中的快速超时
        private val FAST_FAIL_WAN_CONFIG = TimeoutConfig(3000, 5000, 1500)

        private const val UNSTABLE_RECOVERY_TIME_MS: Long = 30000 // 30秒后恢复
    }
}
