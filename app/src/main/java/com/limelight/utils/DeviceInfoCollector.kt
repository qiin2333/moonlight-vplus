package com.limelight.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * 设备信息收集器
 * 负责收集设备的SOC、硬件等关键信息用于统计分析
 */
object DeviceInfoCollector {
    private const val TAG = "DeviceInfoCollector"

    fun collectBasicDeviceInfo(): Map<String, String> {
        val deviceInfo = HashMap<String, String>()

        try {
            deviceInfo["manufacturer"] = Build.MANUFACTURER
            deviceInfo["brand"] = Build.BRAND
            deviceInfo["model"] = Build.MODEL
            deviceInfo["product"] = Build.PRODUCT
            deviceInfo["device"] = Build.DEVICE
            deviceInfo["board"] = Build.BOARD
            deviceInfo["hardware"] = Build.HARDWARE

            deviceInfo["android_version"] = Build.VERSION.RELEASE
            deviceInfo["api_level"] = Build.VERSION.SDK_INT.toString()

            @Suppress("DEPRECATION")
            deviceInfo["cpu_abi"] = Build.CPU_ABI
            @Suppress("DEPRECATION")
            if (Build.CPU_ABI2 != null) {
                @Suppress("DEPRECATION")
                deviceInfo["cpu_abi2"] = Build.CPU_ABI2
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                deviceInfo["soc_manufacturer"] = Build.SOC_MANUFACTURER
                deviceInfo["soc_model"] = Build.SOC_MODEL
                deviceInfo["media_performance_class"] = Build.VERSION.MEDIA_PERFORMANCE_CLASS.toString()
            }

            deviceInfo["screen_density"] = android.content.res.Resources.getSystem().displayMetrics.densityDpi.toString()

        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect basic device info: ${e.message}")
        }

        return deviceInfo
    }

    fun collectCpuInfo(): Map<String, String> {
        val cpuInfo = HashMap<String, String>()

        try {
            val reader = BufferedReader(FileReader("/proc/cpuinfo"))
            var line: String?
            var processorCount = 0
            var processorModel: String? = null
            var processorVendor: String? = null

            while (reader.readLine().also { line = it } != null) {
                when {
                    line?.startsWith("processor") == true -> processorCount++
                    line?.startsWith("model name") == true&& processorModel == null ->
                        processorModel = line?.split(":")[1]?.trim()
                    line?.startsWith("Hardware") == true&& processorVendor == null ->
                        processorVendor = line?.split(":")[1]?.trim()
                    line?.startsWith("CPU architecture") == true&& processorVendor == null ->
                        processorVendor = line?.split(":")[1]?.trim()
                }
            }
            reader.close()

            cpuInfo["processor_count"] = processorCount.toString()
            if (processorModel != null) {
                cpuInfo["processor_model"] = processorModel!!
            }
            if (processorVendor != null) {
                cpuInfo["processor_vendor"] = processorVendor!!
            }

        } catch (e: IOException) {
            Log.w(TAG, "Failed to read CPU info: ${e.message}")
        }

        return cpuInfo
    }

    fun collectGpuInfo(): Map<String, String> {
        val gpuInfo = HashMap<String, String>()

        try {
            val gpuRenderer = getSystemProperty("ro.hardware.gpu")
            if (gpuRenderer != null) {
                gpuInfo["gpu_renderer"] = gpuRenderer
            }

            val hardware = Build.HARDWARE.lowercase()
            gpuInfo["gpu_type"] = when {
                hardware.contains("adreno") -> "adreno"
                hardware.contains("mali") -> "mali"
                hardware.contains("powervr") || hardware.contains("sgx") -> "powervr"
                hardware.contains("tegra") -> "tegra"
                else -> "unknown"
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect GPU info: ${e.message}")
        }

        return gpuInfo
    }

    fun collectNetworkInfo(context: Context): Map<String, String> {
        val networkInfo = HashMap<String, String>()

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager

            if (connectivityManager != null) {
                @Suppress("DEPRECATION")
                val activeNetwork = connectivityManager.activeNetworkInfo
                if (activeNetwork != null) {
                    @Suppress("DEPRECATION")
                    networkInfo["network_type"] = activeNetwork.typeName
                    @Suppress("DEPRECATION")
                    networkInfo["network_subtype"] = activeNetwork.subtypeName
                    @Suppress("DEPRECATION")
                    networkInfo["is_connected"] = activeNetwork.isConnected.toString()
                }
            }

            @Suppress("DEPRECATION")
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiManager != null) {
                networkInfo["wifi_enabled"] = wifiManager.isWifiEnabled.toString()
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect network info: ${e.message}")
        }

        return networkInfo
    }

    fun collectAllDeviceInfo(context: Context): Map<String, String> {
        val allInfo = HashMap<String, String>()
        allInfo.putAll(collectBasicDeviceInfo())
        allInfo.putAll(collectCpuInfo())
        allInfo.putAll(collectGpuInfo())
        allInfo.putAll(collectNetworkInfo(context))
        allInfo["collection_timestamp"] = System.currentTimeMillis().toString()
        return allInfo
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()
            reader.close()
            value
        } catch (e: Exception) {
            null
        }
    }

    fun generateDeviceFingerprint(): String {
        return try {
            val fingerprint = StringBuilder()
            fingerprint.append(Build.MANUFACTURER).append("_")
            fingerprint.append(Build.MODEL).append("_")
            fingerprint.append(Build.HARDWARE).append("_")
            fingerprint.append(Build.VERSION.SDK_INT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                fingerprint.append("_").append(Build.SOC_MANUFACTURER)
                fingerprint.append("_").append(Build.SOC_MODEL)
            }

            fingerprint.toString().replace(Regex("[^a-zA-Z0-9_]"), "_")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate device fingerprint: ${e.message}")
            "unknown_device"
        }
    }
}
