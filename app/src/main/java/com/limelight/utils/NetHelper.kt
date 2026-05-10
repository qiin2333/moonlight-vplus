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

    fun isLanAddress(addressStr: String?): Boolean {
        if (addressStr.isNullOrEmpty()) return false
        return try {
            val addr = InetAddress.getByName(addressStr)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || isPrivateAddress(addr)
        } catch (_: Exception) {
            false
        }
    }

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
    fun calculateBandwidth(currentRxBytes: Long, previousRxBytes: Long, timeInterval: Long): String {
        if (timeInterval !in 1..5000) return "N/A"
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

    /**
     * Returns true if [host] is an IP literal in a private range:
     *   IPv4: RFC1918 (10/8, 172.16/12, 192.168/16), loopback (127/8) or link-local (169.254/16).
     *   IPv6: loopback (::1), link-local (fe80::/10) or unique-local (fc00::/7).
     * Domain names and invalid input return false. Brackets around IPv6 are accepted.
     */
    fun isPrivateAddress(host: String?): Boolean {
        val h = host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return false
        if (!isIpLiteral(h)) return false  // skip DNS for hostnames
        val addr = try { InetAddress.getByName(h) } catch (_: Exception) { return false }
        return addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                (addr is java.net.Inet6Address && isIpv6UniqueLocal(addr))
    }

    /** Convenience: parse [url] and apply [isPrivateAddress] to its host. */
    fun isPrivateNetworkUrl(url: String?): Boolean {
        val host = try { android.net.Uri.parse(url ?: return false).host } catch (_: Exception) { null }
        return isPrivateAddress(host)
    }

    private fun isIpLiteral(h: String): Boolean {
        if (h.contains(':')) return true  // IPv6 always uses ':'
        val parts = h.split('.')
        return parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }

    /** IPv6 unique-local fc00::/7 → top byte is 0xFC or 0xFD. */
    private fun isIpv6UniqueLocal(addr: java.net.Inet6Address): Boolean {
        val b = addr.address[0].toInt() and 0xFF
        return b == 0xFC || b == 0xFD
    }
}
