package com.easytier.jni;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class EasyTierManager {
    private static final String TAG = "EasyTierManager";
    private static final long MONITOR_INTERVAL = 3000L;

    private final Activity activity;
    private final String instanceName;
    private final String networkConfig;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean isRunning = false;
    private volatile String currentIpv4 = null;
    private volatile List<String> currentProxyCidrs = new ArrayList<>();
    private volatile Intent vpnServiceIntent = null;
    private volatile String lastNetworkInfoJson = null;

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                monitorNetworkStatus();
                handler.postDelayed(this, MONITOR_INTERVAL);
            }
        }
    };

    public EasyTierManager(Activity activity, String instanceName, String networkConfig) {
        this.activity = activity;
        this.instanceName = instanceName;
        this.networkConfig = networkConfig;
    }

    public void start() {
        if (isRunning) return;
        try {
            if (EasyTierJNI.runNetworkInstance(networkConfig) == 0) {
                isRunning = true;
                Log.i(TAG, "EasyTier 实例启动成功: " + instanceName);
                handler.post(monitorRunnable);
            } else {
                Log.e(TAG, "EasyTier 实例启动失败: " + EasyTierJNI.getLastError());
            }
        } catch (Exception e) {
            Log.e(TAG, "启动 EasyTier 实例时发生异常", e);
        }
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;
        handler.removeCallbacks(monitorRunnable);
        try {
            stopVpnService();
            EasyTierJNI.stopAllInstances();
            lastNetworkInfoJson = null;
            currentIpv4 = null;
            currentProxyCidrs.clear();
        } catch (Exception e) {
            Log.e(TAG, "停止 EasyTier 实例时发生异常", e);
        }
    }

    public String getLatestNetworkInfoJson() {
        return lastNetworkInfoJson;
    }

    private void monitorNetworkStatus() {
        try {
            String infosJson = EasyTierJNI.collectNetworkInfos(10);
            this.lastNetworkInfoJson = infosJson;

            if (infosJson == null || infosJson.isEmpty()) {
                if (currentIpv4 != null) {
                    Log.w(TAG, "网络信息为空，停止VPN服务。");
                    stopVpnService();
                    currentIpv4 = null;
                    currentProxyCidrs.clear();
                }
                return;
            }

            String newIpv4 = null;
            List<String> newProxyCidrs = new ArrayList<>();

            try {
                JSONObject root = new JSONObject(infosJson);
                JSONObject instance = root.getJSONObject("map").getJSONObject(instanceName);

                // --- 1. 解析本机 IP 和前缀 ---
                String myIp = null;
                int myPrefix = 0;

                JSONObject myNodeInfo = instance.optJSONObject("my_node_info");
                if (myNodeInfo != null) {
                    JSONObject virtualIpv4 = myNodeInfo.optJSONObject("virtual_ipv4");
                    if (virtualIpv4 != null) {
                        int myAddrInt = virtualIpv4.getJSONObject("address").getInt("addr");
                        myPrefix = virtualIpv4.getInt("network_length");
                        myIp = ipFromInt(myAddrInt);
                        newIpv4 = myIp + "/" + myPrefix;
                    }
                }

                // --- 2. 检查对等节点网段 & 解析 proxyCidrs ---
                JSONArray routes = instance.optJSONArray("routes");
                if (routes != null) {
                    for (int i = 0; i < routes.length(); i++) {
                        JSONObject route = routes.getJSONObject(i);

                        // A. 检查对等节点网段是否一致
                        if (myIp != null && myPrefix > 0) {
                            JSONObject ipv4AddrJson = route.optJSONObject("ipv4_addr");
                            if (ipv4AddrJson != null) {
                                int peerAddrInt = ipv4AddrJson.getJSONObject("address").getInt("addr");
                                String peerIp = ipFromInt(peerAddrInt);
                            }
                        }

                        // B. 解析 proxyCidrs
                        JSONArray proxyCidrsArray = route.optJSONArray("proxy_cidrs");
                        if (proxyCidrsArray != null) {
                            for (int j = 0; j < proxyCidrsArray.length(); j++) {
                                newProxyCidrs.add(proxyCidrsArray.getString(j));
                            }
                        }
                    }
                }

            } catch (JSONException e) {
                Log.e(TAG, "解析网络信息失败", e);
                if (currentIpv4 != null) {
                    stopVpnService();
                    currentIpv4 = null;
                    currentProxyCidrs.clear();
                }
                return;
            }

            // --- 3. 比较状态变化，并决定是否重启 VPN ---
            boolean ipv4Changed = !Objects.equals(newIpv4, currentIpv4);
            boolean proxyCidrsChanged = !newProxyCidrs.equals(currentProxyCidrs);

            if (ipv4Changed || proxyCidrsChanged) {
                Log.i(TAG, "网络拓扑变化，需要重启 VpnService。");

                this.currentIpv4 = newIpv4;
                this.currentProxyCidrs = new ArrayList<>(newProxyCidrs);

                if (newIpv4 != null) {
                    restartVpnService(newIpv4, newProxyCidrs);
                } else {
                    stopVpnService();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "监控网络状态时发生严重异常", e);
            this.lastNetworkInfoJson = null;
            if (currentIpv4 != null) {
                stopVpnService();
                currentIpv4 = null;
                currentProxyCidrs.clear();
            }
        }
    }

    private void restartVpnService(String ipv4, List<String> proxyCidrs) {
        stopVpnService();
        startVpnService(ipv4, proxyCidrs);
    }

    private void startVpnService(String ipv4, List<String> proxyCidrs) {
        Intent intent = new Intent(activity, EasyTierVpnService.class);
        intent.putExtra("ipv4_address", ipv4);
        intent.putStringArrayListExtra("proxy_cidrs", new ArrayList<>(proxyCidrs));
        intent.putExtra("instance_name", this.instanceName);
        activity.startService(intent);
        vpnServiceIntent = intent;
    }

    private void stopVpnService() {
        Intent stopIntent = new Intent(EasyTierVpnService.ACTION_STOP_VPN);
        activity.sendBroadcast(stopIntent);
        Log.i(TAG, "停止发送VPN广播。");
        vpnServiceIntent = null;
    }

    private String ipFromInt(int addr) {
        return ((addr >>> 24) & 0xFF) + "." + ((addr >>> 16) & 0xFF) + "." + ((addr >>> 8) & 0xFF) + "." + (addr & 0xFF);
    }

}