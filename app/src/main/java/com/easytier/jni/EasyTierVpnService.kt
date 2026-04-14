package com.easytier.jni

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

import java.io.IOException

class EasyTierVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    private var instanceName: String? = null
    private var vpnThread: Thread? = null

    private val stopVpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP_VPN) {
                Log.i(TAG, "收到停止广播。正在清理并停止自身。")
                cleanupAndStop()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "已创建VPN服务。")

        val filter = IntentFilter(ACTION_STOP_VPN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopVpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopVpnReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP_VPN) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        vpnThread = Thread({
            try {
                val ipv4Address = intent.getStringExtra("ipv4_address")
                val proxyCidrs = intent.getStringArrayListExtra("proxy_cidrs")
                instanceName = intent.getStringExtra("instance_name")

                if (ipv4Address == null || instanceName == null) {
                    cleanupAndStop()
                    return@Thread
                }

                setupVpnInterface(ipv4Address, proxyCidrs ?: ArrayList())
            } catch (t: Throwable) {
                Log.e(TAG, "VPN设置线程失败", t)
                cleanupAndStop()
            }
        }, "VpnSetupThread")

        vpnThread!!.start()
        return START_NOT_STICKY
    }

    private fun setupVpnInterface(ipv4Address: String, proxyCidrs: List<String>) {
        try {
            val addressInfo = parseIpv4Address(ipv4Address)

            val builder = Builder()
            builder.setSession("EasyTier VPN")
                .addAddress(addressInfo.ip, addressInfo.networkLength)
                .addDnsServer("223.5.5.5")

            try {
                builder.addAddress("fd00::1", 128)
                Log.i(TAG, "已激活 VPN 接口 IPv6 协议栈 (fd00::1/128) 以支持双栈通信")
            } catch (e: Exception) {
                Log.w(TAG, "添加 IPv6 地址失败", e)
            }

            Log.i(TAG, "为虚拟网络添加了VPN路由：${addressInfo.ip}/${addressInfo.networkLength}")

            for (cidr in proxyCidrs) {
                Log.i(TAG, "为虚拟网络添加代理CIDR：$cidr")
                try {
                    val routeInfo = parseCidr(cidr)
                    builder.addRoute(routeInfo.ip, routeInfo.networkLength)
                    Log.i(TAG, "为虚拟网络添加了VPN路由：${routeInfo.ip}/${routeInfo.networkLength}")
                } catch (e: Exception) {
                    Log.w(TAG, "解析代理CIDR失败：$cidr", e)
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) return
            Log.i(TAG, "已建立VPN接口。")
            isRunning = true

            EasyTierJNI.setTunFd(instanceName!!, vpnInterface!!.fd)

            while (isRunning) {
                Thread.sleep(Long.MAX_VALUE)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.e(TAG, "VPN接口设置过程中出错", t)
        } finally {
            cleanup()
        }
    }

    private fun cleanupAndStop() {
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        if (!isRunning) return
        isRunning = false

        vpnThread?.interrupt()
        vpnThread = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭VPN接口时出错", e)
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stopVpnReceiver)
        } catch (_: IllegalArgumentException) {
        }
        cleanup()
        Log.d(TAG, "VPN服务已损坏。")
    }

    private data class IpAddressInfo(val ip: String, val networkLength: Int)

    private fun parseIpv4Address(addr: String): IpAddressInfo {
        val parts = addr.split("/")
        return IpAddressInfo(parts[0], if (parts.size > 1) parts[1].toInt() else 24)
    }

    private fun parseCidr(cidr: String): IpAddressInfo {
        val parts = cidr.split("/")
        require(parts.size == 2) { "Invalid CIDR: $cidr" }
        return IpAddressInfo(parts[0], parts[1].toInt())
    }

    companion object {
        private const val TAG = "EasyTierVpnService"
        const val ACTION_STOP_VPN = "com.easytier.jni.ACTION_STOP_VPN"
    }
}
