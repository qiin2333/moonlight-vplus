package com.limelight.computers;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.utils.NetHelper;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 网络诊断工具类 - 检测网络类型、质量和连接状态
 * 复用 {@link NetHelper} 提供的基础网络检测功能
 */
public class NetworkDiagnostics {
    
    public enum NetworkType {
        /**
         * LAN 本地网络 - 同一子网或局域网
         */
        LAN,
        /**
         * WAN 公网 - 跨域互联网连接
         */
        WAN,
        /**
         * VPN 虚拟专用网络
         */
        VPN,
        /**
         * 移动网络 - 蜂窝数据
         */
        MOBILE,
        /**
         * 未知网络
         */
        UNKNOWN
    }
    
    public enum NetworkQuality {
        /**
         * 优秀 - 低延迟、高带宽、稳定
         */
        EXCELLENT(3000),    // 3秒连接超时
        /**
         * 良好 - 中等延迟、合理带宽
         */
        GOOD(5000),          // 5秒连接超时
        /**
         * 一般 - 较高延迟、可变带宽
         */
        FAIR(8000),          // 8秒连接超时
        /**
         * 差 - 高延迟、低带宽、不稳定
         */
        POOR(12000),         // 12秒连接超时
        /**
         * 未知
         */
        UNKNOWN(5000);       // 5秒连接超时（默认）
        
        public final int suggestedConnectTimeout;
        
        NetworkQuality(int suggestedConnectTimeout) {
            this.suggestedConnectTimeout = suggestedConnectTimeout;
        }
    }
    
    private final Context context;
    private final AtomicReference<NetworkDiagnosticsSnapshot> lastSnapshot = new AtomicReference<>();
    
    /**
     * 网络诊断快照
     */
    public static class NetworkDiagnosticsSnapshot {
        public final NetworkType networkType;
        public final NetworkQuality networkQuality;
        public final boolean isVpn;
        public final boolean isMobile;
        public final boolean isWifi;
        public final boolean isStableConnection;
        public final long timestamp;
        
        public NetworkDiagnosticsSnapshot(NetworkType networkType, NetworkQuality networkQuality,
                                         boolean isVpn, boolean isMobile, boolean isWifi,
                                         boolean isStableConnection) {
            this.networkType = networkType;
            this.networkQuality = networkQuality;
            this.isVpn = isVpn;
            this.isMobile = isMobile;
            this.isWifi = isWifi;
            this.isStableConnection = isStableConnection;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("NetworkDiagnostics{type=%s, quality=%s, vpn=%b, mobile=%b, wifi=%b, stable=%b}",
                    networkType, networkQuality, isVpn, isMobile, isWifi, isStableConnection);
        }
    }
    
    public NetworkDiagnostics(Context context) {
        this.context = context;
    }
    
    /**
     * 诊断当前网络状态
     */
    public NetworkDiagnosticsSnapshot diagnoseNetwork() {
        try {
            // 复用 NetHelper 的基础检测方法
            boolean isVpn = NetHelper.isActiveNetworkVpn(context);
            boolean isMobile = NetHelper.isActiveNetworkMobile(context);
            boolean isWifi = NetHelper.isActiveNetworkWifi(context);
            boolean isEthernet = NetHelper.isActiveNetworkEthernet(context);
            
            NetworkType type = detectNetworkType(isVpn, isMobile, isWifi, isEthernet);
            NetworkQuality quality = estimateNetworkQuality(isMobile, isWifi || isEthernet);
            boolean isStable = isConnectionStable(quality);
            
            NetworkDiagnosticsSnapshot snapshot = new NetworkDiagnosticsSnapshot(
                    type, quality, isVpn, isMobile, isWifi, isStable);
            
            lastSnapshot.set(snapshot);
            LimeLog.info("Network diagnostics: " + snapshot);
            
            return snapshot;
        } catch (Exception e) {
            LimeLog.warning("Network diagnostics failed: " + e.getMessage());
            return new NetworkDiagnosticsSnapshot(NetworkType.UNKNOWN, NetworkQuality.UNKNOWN,
                    false, false, false, false);
        }
    }
    
    /**
     * 检测网络类型
     */
    private NetworkType detectNetworkType(boolean isVpn, boolean isMobile, boolean isWifi, boolean isEthernet) {
        if (isVpn) {
            return NetworkType.VPN;
        }
        
        // 即使是移动网络，如果存在本地网络接口（如热点开启），也应视为 LAN 以保证本地连接可用
        if (isMobile) {
            if (NetHelper.isLocalNetworkInterfaceAvailable()) {
                // 发现本地活跃接口（如热点），退回到 LAN/UNKNOWN
                LimeLog.info("Mobile data active but local interface found (Hotspot?), using LAN type");
                return NetworkType.LAN;
            }
            return NetworkType.WAN;
        }
        
        if (isWifi || isEthernet) {
            return NetworkType.LAN;
        }
        return NetworkType.UNKNOWN;
    }
    
    /**
     * 估计网络质量
     */
    private NetworkQuality estimateNetworkQuality(boolean isMobile, boolean isWifi) {
        int bandwidth = NetHelper.getDownstreamBandwidthKbps(context);
        
        if (bandwidth < 0) {
            return NetworkQuality.UNKNOWN;
        }
        
        // 移动网络质量评估
        if (isMobile) {
            if (bandwidth < 5000) {
                return NetworkQuality.POOR;
            } else if (bandwidth < 20000) {
                return NetworkQuality.FAIR;
            } else {
                return NetworkQuality.GOOD;
            }
        }
        
        // WiFi/以太网质量评估
        if (bandwidth < 5000) {
            return NetworkQuality.FAIR;
        } else if (bandwidth < 50000) {
            return NetworkQuality.GOOD;
        } else {
            return NetworkQuality.EXCELLENT;
        }
    }
    
    /**
     * 判断连接是否稳定
     */
    private boolean isConnectionStable(NetworkQuality quality) {
        return quality != NetworkQuality.POOR && quality != NetworkQuality.UNKNOWN;
    }
    
    /**
     * 获取最后一次诊断结果
     */
    public NetworkDiagnosticsSnapshot getLastDiagnostics() {
        NetworkDiagnosticsSnapshot snapshot = lastSnapshot.get();
        if (snapshot == null) {
            return diagnoseNetwork();
        }
        return snapshot;
    }
    
    /**
     * 判断地址是否为LAN地址
     * @deprecated 使用 {@link NetHelper#isLanAddress(String)} 代替
     */
    @Deprecated
    public static boolean isLanAddress(String addressStr) {
        return NetHelper.isLanAddress(addressStr);
    }
}
