package com.easytier.jni

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Objects

class EasyTierManager(
    private val activity: Activity,
    private val instanceName: String,
    private val networkConfig: String
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false
    @Volatile
    private var currentIpv4: String? = null
    @Volatile
    private var currentProxyCidrs: MutableList<String> = ArrayList()
    @Volatile
    private var vpnServiceIntent: Intent? = null
    @Volatile
    var latestNetworkInfoJson: String? = null
        private set

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                monitorNetworkStatus()
                handler.postDelayed(this, MONITOR_INTERVAL)
            }
        }
    }

    fun start() {
        if (isRunning) return
        try {
            if (EasyTierJNI.runNetworkInstance(networkConfig) == 0) {
                isRunning = true
                Log.i(TAG, "EasyTier 实例启动成功: $instanceName")
                handler.post(monitorRunnable)
            } else {
                Log.e(TAG, "EasyTier 实例启动失败: ${EasyTierJNI.getLastError()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动 EasyTier 实例时发生异常", e)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(monitorRunnable)
        try {
            stopVpnService()
            EasyTierJNI.stopAllInstances()
            latestNetworkInfoJson = null
            currentIpv4 = null
            currentProxyCidrs.clear()
        } catch (e: Exception) {
            Log.e(TAG, "停止 EasyTier 实例时发生异常", e)
        }
    }

    private fun monitorNetworkStatus() {
        try {
            val infosJson = EasyTierJNI.collectNetworkInfos(10)
            latestNetworkInfoJson = infosJson

            if (infosJson.isNullOrEmpty()) {
                if (currentIpv4 != null) {
                    Log.w(TAG, "网络信息为空，停止VPN服务。")
                    stopVpnService()
                    currentIpv4 = null
                    currentProxyCidrs.clear()
                }
                return
            }

            var newIpv4: String? = null
            val newProxyCidrs = ArrayList<String>()

            try {
                val root = JSONObject(infosJson)
                val instance = root.getJSONObject("map").getJSONObject(instanceName)

                var myIp: String? = null
                var myPrefix = 0

                val myNodeInfo = instance.optJSONObject("my_node_info")
                if (myNodeInfo != null) {
                    val virtualIpv4 = myNodeInfo.optJSONObject("virtual_ipv4")
                    if (virtualIpv4 != null) {
                        val myAddrInt = virtualIpv4.getJSONObject("address").getInt("addr")
                        myPrefix = virtualIpv4.getInt("network_length")
                        myIp = ipFromInt(myAddrInt)
                        newIpv4 = "$myIp/$myPrefix"
                    }
                }

                val routes = instance.optJSONArray("routes")
                if (routes != null) {
                    for (i in 0 until routes.length()) {
                        val route = routes.getJSONObject(i)

                        if (myIp != null && myPrefix > 0) {
                            val ipv4AddrJson = route.optJSONObject("ipv4_addr")
                            if (ipv4AddrJson != null) {
                                val peerAddrInt = ipv4AddrJson.getJSONObject("address").getInt("addr")
                                val peerIp = ipFromInt(peerAddrInt)
                            }
                        }

                        val proxyCidrsArray = route.optJSONArray("proxy_cidrs")
                        if (proxyCidrsArray != null) {
                            for (j in 0 until proxyCidrsArray.length()) {
                                newProxyCidrs.add(proxyCidrsArray.getString(j))
                            }
                        }
                    }
                }

            } catch (e: JSONException) {
                Log.e(TAG, "解析网络信息失败", e)
                if (currentIpv4 != null) {
                    stopVpnService()
                    currentIpv4 = null
                    currentProxyCidrs.clear()
                }
                return
            }

            val ipv4Changed = currentIpv4 != newIpv4
            val proxyCidrsChanged = newProxyCidrs != currentProxyCidrs

            if (ipv4Changed || proxyCidrsChanged) {
                Log.i(TAG, "网络拓扑变化，需要重启 VpnService。")

                currentIpv4 = newIpv4
                currentProxyCidrs = ArrayList(newProxyCidrs)

                if (newIpv4 != null) {
                    restartVpnService(newIpv4, newProxyCidrs)
                } else {
                    stopVpnService()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "监控网络状态时发生严重异常", e)
            latestNetworkInfoJson = null
            if (currentIpv4 != null) {
                stopVpnService()
                currentIpv4 = null
                currentProxyCidrs.clear()
            }
        }
    }

    private fun restartVpnService(ipv4: String, proxyCidrs: List<String>) {
        stopVpnService()
        startVpnService(ipv4, proxyCidrs)
    }

    private fun startVpnService(ipv4: String, proxyCidrs: List<String>) {
        val intent = Intent(activity, EasyTierVpnService::class.java)
        intent.putExtra("ipv4_address", ipv4)
        intent.putStringArrayListExtra("proxy_cidrs", ArrayList(proxyCidrs))
        intent.putExtra("instance_name", instanceName)
        activity.startService(intent)
        vpnServiceIntent = intent
    }

    private fun stopVpnService() {
        val stopIntent = Intent(EasyTierVpnService.ACTION_STOP_VPN)
        activity.sendBroadcast(stopIntent)
        Log.i(TAG, "停止发送VPN广播。")
        vpnServiceIntent = null
    }

    private fun ipFromInt(addr: Int): String {
        return "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
    }

    companion object {
        private const val TAG = "EasyTierManager"
        private const val MONITOR_INTERVAL = 3000L
    }
}
