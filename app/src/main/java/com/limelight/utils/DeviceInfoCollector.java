package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备信息收集器
 * 负责收集设备的SOC、硬件等关键信息用于统计分析
 */
public class DeviceInfoCollector {
    private static final String TAG = "DeviceInfoCollector";
    
    /**
     * 收集设备基本信息
     */
    public static Map<String, String> collectBasicDeviceInfo() {
        Map<String, String> deviceInfo = new HashMap<>();
        
        try {
            // 基本设备信息
            deviceInfo.put("manufacturer", Build.MANUFACTURER);
            deviceInfo.put("brand", Build.BRAND);
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("product", Build.PRODUCT);
            deviceInfo.put("device", Build.DEVICE);
            deviceInfo.put("board", Build.BOARD);
            deviceInfo.put("hardware", Build.HARDWARE);
            
            // Android版本信息
            deviceInfo.put("android_version", Build.VERSION.RELEASE);
            deviceInfo.put("api_level", String.valueOf(Build.VERSION.SDK_INT));
            
            // CPU架构信息
            deviceInfo.put("cpu_abi", Build.CPU_ABI);
            if (Build.CPU_ABI2 != null) {
                deviceInfo.put("cpu_abi2", Build.CPU_ABI2);
            }
            
            // SOC信息（Android S及以上）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (Build.SOC_MANUFACTURER != null) {
                    deviceInfo.put("soc_manufacturer", Build.SOC_MANUFACTURER);
                }
                if (Build.SOC_MODEL != null) {
                    deviceInfo.put("soc_model", Build.SOC_MODEL);
                }
                deviceInfo.put("media_performance_class", String.valueOf(Build.VERSION.MEDIA_PERFORMANCE_CLASS));
            }
            
            // 屏幕信息
            deviceInfo.put("screen_density", String.valueOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.DONUT ? 
                android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi : "unknown"));
            
            // 内存信息
            android.app.ActivityManager activityManager = (android.app.ActivityManager) 
                android.app.ActivityManager.class.cast(null);
            if (activityManager != null) {
                android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                deviceInfo.put("total_memory_mb", String.valueOf(memoryInfo.totalMem / (1024 * 1024)));
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to collect basic device info: " + e.getMessage());
        }
        
        return deviceInfo;
    }
    
    /**
     * 收集详细的CPU信息
     */
    public static Map<String, String> collectCpuInfo() {
        Map<String, String> cpuInfo = new HashMap<>();
        
        try {
            // 从/proc/cpuinfo读取CPU详细信息
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            int processorCount = 0;
            String processorModel = null;
            String processorVendor = null;
            
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("processor")) {
                    processorCount++;
                } else if (line.startsWith("model name") && processorModel == null) {
                    processorModel = line.split(":")[1].trim();
                } else if (line.startsWith("Hardware") && processorVendor == null) {
                    processorVendor = line.split(":")[1].trim();
                } else if (line.startsWith("CPU architecture") && processorVendor == null) {
                    processorVendor = line.split(":")[1].trim();
                }
            }
            reader.close();
            
            cpuInfo.put("processor_count", String.valueOf(processorCount));
            if (processorModel != null) {
                cpuInfo.put("processor_model", processorModel);
            }
            if (processorVendor != null) {
                cpuInfo.put("processor_vendor", processorVendor);
            }
            
        } catch (IOException e) {
            Log.w(TAG, "Failed to read CPU info: " + e.getMessage());
        }
        
        return cpuInfo;
    }
    
    /**
     * 收集GPU信息
     */
    public static Map<String, String> collectGpuInfo() {
        Map<String, String> gpuInfo = new HashMap<>();
        
        try {
            // 尝试从系统属性获取GPU信息
            String gpuRenderer = getSystemProperty("ro.hardware.gpu");
            if (gpuRenderer != null) {
                gpuInfo.put("gpu_renderer", gpuRenderer);
            }
            
            // 检查是否为特定GPU厂商
            String hardware = Build.HARDWARE.toLowerCase();
            if (hardware.contains("adreno")) {
                gpuInfo.put("gpu_type", "adreno");
            } else if (hardware.contains("mali")) {
                gpuInfo.put("gpu_type", "mali");
            } else if (hardware.contains("powervr") || hardware.contains("sgx")) {
                gpuInfo.put("gpu_type", "powervr");
            } else if (hardware.contains("tegra")) {
                gpuInfo.put("gpu_type", "tegra");
            } else {
                gpuInfo.put("gpu_type", "unknown");
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to collect GPU info: " + e.getMessage());
        }
        
        return gpuInfo;
    }
    
    /**
     * 收集网络和连接信息
     */
    public static Map<String, String> collectNetworkInfo(Context context) {
        Map<String, String> networkInfo = new HashMap<>();
        
        try {
            android.net.ConnectivityManager connectivityManager = 
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            
            if (connectivityManager != null) {
                android.net.NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    networkInfo.put("network_type", activeNetwork.getTypeName());
                    networkInfo.put("network_subtype", activeNetwork.getSubtypeName());
                    networkInfo.put("is_connected", String.valueOf(activeNetwork.isConnected()));
                }
            }
            
            // WiFi信息
            android.net.wifi.WifiManager wifiManager = 
                (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                networkInfo.put("wifi_enabled", String.valueOf(wifiManager.isWifiEnabled()));
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to collect network info: " + e.getMessage());
        }
        
        return networkInfo;
    }
    
    /**
     * 收集完整的设备信息
     */
    public static Map<String, String> collectAllDeviceInfo(Context context) {
        Map<String, String> allInfo = new HashMap<>();
        
        // 收集各类信息
        allInfo.putAll(collectBasicDeviceInfo());
        allInfo.putAll(collectCpuInfo());
        allInfo.putAll(collectGpuInfo());
        allInfo.putAll(collectNetworkInfo(context));
        
        // 添加收集时间戳
        allInfo.put("collection_timestamp", String.valueOf(System.currentTimeMillis()));
        
        return allInfo;
    }
    
    /**
     * 获取系统属性
     */
    private static String getSystemProperty(String key) {
        try {
            Process process = Runtime.getRuntime().exec("getprop " + key);
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            reader.close();
            return value;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 生成设备指纹（用于匿名标识）
     */
    public static String generateDeviceFingerprint() {
        try {
            StringBuilder fingerprint = new StringBuilder();
            fingerprint.append(Build.MANUFACTURER).append("_");
            fingerprint.append(Build.MODEL).append("_");
            fingerprint.append(Build.HARDWARE).append("_");
            fingerprint.append(Build.VERSION.SDK_INT);
            
            // 添加SOC信息（如果可用）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (Build.SOC_MANUFACTURER != null) {
                    fingerprint.append("_").append(Build.SOC_MANUFACTURER);
                }
                if (Build.SOC_MODEL != null) {
                    fingerprint.append("_").append(Build.SOC_MODEL);
                }
            }
            
            return fingerprint.toString().replaceAll("[^a-zA-Z0-9_]", "_");
        } catch (Exception e) {
            Log.w(TAG, "Failed to generate device fingerprint: " + e.getMessage());
            return "unknown_device";
        }
    }
}
