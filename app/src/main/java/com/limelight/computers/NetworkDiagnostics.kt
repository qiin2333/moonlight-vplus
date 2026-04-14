package com.limelight.computers

import android.content.Context
import com.limelight.LimeLog
import com.limelight.utils.NetHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * 网络诊断工具类 - 检测网络类型、质量和连接状态
 * 复用 [NetHelper] 提供的基础网络检测功能
 */
class NetworkDiagnostics(private val context: Context) {

    enum class NetworkType {
        /** LAN 本地网络 - 同一子网或局域网 */
        LAN,
        /** WAN 公网 - 跨域互联网连接 */
        WAN,
        /** VPN 虚拟专用网络 */
        VPN,
        /** 移动网络 - 蜂窝数据 */
        MOBILE,
        /** 未知网络 */
        UNKNOWN
    }

    enum class NetworkQuality(val suggestedConnectTimeout: Int) {
        /** 优秀 - 低延迟、高带宽、稳定 */
        EXCELLENT(3000),
        /** 良好 - 中等延迟、合理带宽 */
        GOOD(5000),
        /** 一般 - 较高延迟、可变带宽 */
        FAIR(8000),
        /** 差 - 高延迟、低带宽、不稳定 */
        POOR(12000),
        /** 未知 */
        UNKNOWN(5000)
    }

    private val lastSnapshot = AtomicReference<NetworkDiagnosticsSnapshot>()

    /**
     * 网络诊断快照
     */
    class NetworkDiagnosticsSnapshot(
        val networkType: NetworkType,
        val networkQuality: NetworkQuality,
        val isVpn: Boolean,
        val isMobile: Boolean,
        val isWifi: Boolean,
        val isStableConnection: Boolean
    ) {
        val timestamp: Long = System.currentTimeMillis()

        override fun toString(): String {
            return "NetworkDiagnostics{type=$networkType, quality=$networkQuality, vpn=$isVpn, mobile=$isMobile, wifi=$isWifi, stable=$isStableConnection}"
        }
    }

    /**
     * 诊断当前网络状态
     */
    fun diagnoseNetwork(): NetworkDiagnosticsSnapshot {
        return try {
            val isVpn = NetHelper.isActiveNetworkVpn(context)
            val isMobile = NetHelper.isActiveNetworkMobile(context)
            val isWifi = NetHelper.isActiveNetworkWifi(context)
            val isEthernet = NetHelper.isActiveNetworkEthernet(context)

            val type = detectNetworkType(isVpn, isMobile, isWifi, isEthernet)
            val quality = estimateNetworkQuality(isMobile, isWifi || isEthernet)
            val isStable = isConnectionStable(quality)

            val snapshot = NetworkDiagnosticsSnapshot(type, quality, isVpn, isMobile, isWifi, isStable)
            lastSnapshot.set(snapshot)
            LimeLog.info("Network diagnostics: $snapshot")
            snapshot
        } catch (e: Exception) {
            LimeLog.warning("Network diagnostics failed: ${e.message}")
            NetworkDiagnosticsSnapshot(
                NetworkType.UNKNOWN, NetworkQuality.UNKNOWN,
                isVpn = false, isMobile = false, isWifi = false, isStableConnection = false
            )
        }
    }

    private fun detectNetworkType(isVpn: Boolean, isMobile: Boolean, isWifi: Boolean, isEthernet: Boolean): NetworkType {
        if (isVpn) return NetworkType.VPN

        if (isMobile) {
            if (NetHelper.isLocalNetworkInterfaceAvailable()) {
                LimeLog.info("Mobile data active but local interface found (Hotspot?), using LAN type")
                return NetworkType.LAN
            }
            return NetworkType.WAN
        }

        return if (isWifi || isEthernet) NetworkType.LAN else NetworkType.UNKNOWN
    }

    private fun estimateNetworkQuality(isMobile: Boolean, isWifi: Boolean): NetworkQuality {
        val bandwidth = NetHelper.getDownstreamBandwidthKbps(context)

        if (bandwidth < 0) return NetworkQuality.UNKNOWN

        if (isMobile) {
            return when {
                bandwidth < 5000 -> NetworkQuality.POOR
                bandwidth < 20000 -> NetworkQuality.FAIR
                else -> NetworkQuality.GOOD
            }
        }

        return when {
            bandwidth < 5000 -> NetworkQuality.FAIR
            bandwidth < 50000 -> NetworkQuality.GOOD
            else -> NetworkQuality.EXCELLENT
        }
    }

    private fun isConnectionStable(quality: NetworkQuality): Boolean {
        return quality != NetworkQuality.POOR && quality != NetworkQuality.UNKNOWN
    }

    fun getLastDiagnostics(): NetworkDiagnosticsSnapshot {
        return lastSnapshot.get() ?: diagnoseNetwork()
    }

    companion object {
        @Deprecated("使用 NetHelper.isLanAddress(String) 代替", ReplaceWith("NetHelper.isLanAddress(addressStr)"))
        fun isLanAddress(addressStr: String): Boolean {
            return NetHelper.isLanAddress(addressStr)
        }
    }
}
