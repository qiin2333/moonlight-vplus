package com.limelight.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.limelight.LimeLog
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

object NetHelper {

    @JvmStatic
    fun isActiveNetworkVpn(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork ?: return false
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } else {
            @Suppress("DEPRECATION")
            connMgr.activeNetworkInfo?.type == ConnectivityManager.TYPE_VPN
        }
    }

    @JvmStatic
    fun isActiveNetworkMobile(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork ?: return false
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            @Suppress("DEPRECATION")
            val info = connMgr.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.type in intArrayOf(
                ConnectivityManager.TYPE_MOBILE,
                ConnectivityManager.TYPE_MOBILE_DUN,
                ConnectivityManager.TYPE_MOBILE_HIPRI,
                ConnectivityManager.TYPE_MOBILE_MMS,
                ConnectivityManager.TYPE_MOBILE_SUPL,
                ConnectivityManager.TYPE_WIMAX
            )
        }
    }

    @JvmStatic
    fun isActiveNetworkWifi(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork ?: return false
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            connMgr.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    @JvmStatic
    fun isActiveNetworkEthernet(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork ?: return false
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            connMgr.activeNetworkInfo?.type == ConnectivityManager.TYPE_ETHERNET
        }
    }

    @JvmStatic
    fun getDownstreamBandwidthKbps(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connMgr?.activeNetwork
            val netCaps = activeNetwork?.let { connMgr.getNetworkCapabilities(it) }
            if (netCaps != null) {
                return netCaps.linkDownstreamBandwidthKbps
            }
        }
        return -1
    }

    @JvmStatic
    fun isLanAddress(addressStr: String?): Boolean {
        if (addressStr.isNullOrEmpty()) return false
        return try {
            val addr = InetAddress.getByName(addressStr)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || isPrivateAddress(addr)
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun isLocalNetworkInterfaceAvailable(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false

            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue

                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("pdp") ||
                    name.startsWith("wwan") || name.startsWith("tun") ||
                    name.startsWith("ppp")
                ) continue

                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr.isLoopbackAddress) continue
                    if (addr.address.size == 4 && isPrivateAddress(addr)) {
                        return true
                    }
                }
            }
        } catch (e: SocketException) {
            LimeLog.warning("Error checking local network interfaces: ${e.message}")
        }
        return false
    }

    @JvmStatic
    fun isPrivateAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            if (bytes[0] == 10.toByte()) return true
            if (bytes[0] == 172.toByte() && bytes[1] >= 16 && bytes[1] <= 31) return true
            if (bytes[0] == 192.toByte() && bytes[1] == 168.toByte()) return true
        }
        return false
    }

    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun calculateBandwidth(currentRxBytes: Long, previousRxBytes: Long, timeInterval: Long): String {
        if (timeInterval <= 0 || timeInterval > 5000) return "N/A"
        if (currentRxBytes < 0 || previousRxBytes < 0) return "N/A"

        val rxBytesDifference = currentRxBytes - previousRxBytes
        if (rxBytesDifference < 0) return "N/A"

        val rxBytesPerDifference = rxBytesDifference / 1024
        val speedKBps = rxBytesPerDifference / (timeInterval / 1000.0)

        return if (speedKBps < 1024) {
            String.format("%.0f K/s", speedKBps)
        } else {
            String.format("%.2f M/s", speedKBps / 1024)
        }
    }
}
