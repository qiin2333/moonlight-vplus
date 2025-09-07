package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.annotation.SuppressLint;

public class NetHelper {
    public static boolean isActiveNetworkVpn(Context context) {
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(activeNetwork);
                if (netCaps != null) {
                    return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                            !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
                }
            }
        }
        else {
            NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            if (activeNetworkInfo != null) {
                return activeNetworkInfo.getType() == ConnectivityManager.TYPE_VPN;
            }
        }

        return false;
    }

    /**
     * 计算并格式化网络带宽
     * @param currentRxBytes 当前接收字节数
     * @param previousRxBytes 上次接收字节数
     * @param timeInterval 时间间隔（毫秒）
     * @return 格式化后的带宽字符串
     */
    @SuppressLint("DefaultLocale")
    public static String calculateBandwidth(long currentRxBytes, long previousRxBytes, long timeInterval) {
        // 检查时间间隔是否有效
        if (timeInterval <= 0 || timeInterval > 5000) { // 超过5秒的间隔认为无效
            return "N/A";
        }
        
        // 检查字节数是否有效（防止TrafficStats返回异常值）
        if (currentRxBytes < 0 || previousRxBytes < 0) {
            return "N/A";
        }
        
        // 防止字节数回绕（32位系统可能发生）
        long rxBytesDifference = currentRxBytes - previousRxBytes;
        if (rxBytesDifference < 0) {
            // 如果出现负数，可能是计数器重置，返回0
            return "N/A";
        }
        
        // 转换为KB
        long rxBytesPerDifference = rxBytesDifference / 1024;
        double speedKBps = rxBytesPerDifference / ((double) timeInterval / 1000);
        
        if (speedKBps < 1024) {
            return String.format("%.0f K/s", speedKBps);
        }
        return String.format("%.2f M/s", speedKBps / 1024);
    }
}
