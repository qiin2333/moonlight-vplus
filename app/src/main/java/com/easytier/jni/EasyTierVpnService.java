package com.easytier.jni;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EasyTierVpnService extends VpnService {

    private static final String TAG = "EasyTierVpnService";
    public static final String ACTION_STOP_VPN = "com.easytier.jni.ACTION_STOP_VPN";

    private ParcelFileDescriptor vpnInterface = null;
    private volatile boolean isRunning = false;
    private String instanceName = null;
    private Thread vpnThread = null;

    private final BroadcastReceiver stopVpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), ACTION_STOP_VPN)) {
                Log.i(TAG, "收到停止广播。正在清理并停止自身。");
                cleanupAndStop();
            }
        }
    };

    /**
     * 使用 @SuppressLint 注解来抑制 "UnspecifiedRegisterReceiverFlag" 警告。
     * 这告诉 Lint 工具，我们已经知晓并手动处理了这个问题。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "已创建VPN服务。");

        IntentFilter filter = new IntentFilter(ACTION_STOP_VPN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopVpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopVpnReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Objects.equals(intent.getAction(), ACTION_STOP_VPN)) {
            cleanupAndStop();
            return START_NOT_STICKY;
        }

        vpnThread = new Thread(() -> {
            try {
                String ipv4Address = intent.getStringExtra("ipv4_address");
                ArrayList<String> proxyCidrs = intent.getStringArrayListExtra("proxy_cidrs");
                instanceName = intent.getStringExtra("instance_name");

                if (ipv4Address == null || instanceName == null) {
                    cleanupAndStop();
                    return;
                }
                if (proxyCidrs == null) proxyCidrs = new ArrayList<>();

                setupVpnInterface(ipv4Address, proxyCidrs);
            } catch (Throwable t) {
                Log.e(TAG, "VPN设置线程失败", t);
                cleanupAndStop();
            }
        }, "VpnSetupThread");

        vpnThread.start();
        return START_NOT_STICKY;
    }

    private void setupVpnInterface(String ipv4Address, List<String> proxyCidrs) {
        try {
            IpAddressInfo addressInfo = parseIpv4Address(ipv4Address);

            Builder builder = new Builder();
            builder.setSession("EasyTier VPN")
                    .addAddress(addressInfo.ip, addressInfo.networkLength)
                    .addDnsServer("223.5.5.5");

            try {
                builder.addAddress("fd00::1", 128);
                Log.i(TAG, "已激活 VPN 接口 IPv6 协议栈 (fd00::1/128) 以支持双栈通信");
            } catch (Exception e) {
                Log.w(TAG, "添加 IPv6 地址失败", e);
            }

            Log.i(TAG, "为虚拟网络添加了VPN路由：" + addressInfo.ip + "/" + addressInfo.networkLength);

            for (String cidr : proxyCidrs) {
                Log.i(TAG, "为虚拟网络添加代理CIDR：" + cidr);
                try {
                    IpAddressInfo routeInfo = parseCidr(cidr);
                    builder.addRoute(routeInfo.ip, routeInfo.networkLength);
                    Log.i(TAG, "为虚拟网络添加了VPN路由：" + routeInfo.ip + "/" + routeInfo.networkLength);
                } catch (Exception e) {
                    Log.w(TAG, "解析代理CIDR失败：" + cidr, e);
                }
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) return;
            Log.i(TAG, "已建立VPN接口。");
            isRunning = true;

            EasyTierJNI.setTunFd(instanceName, vpnInterface.getFd());

            while (isRunning) {
                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "VPN接口设置过程中出错", t);
        } finally {
            cleanup();
        }
    }

    private void cleanupAndStop() {
        cleanup();
        stopSelf();
    }

    private void cleanup() {
        if (!isRunning) return;
        isRunning = false;

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        try {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭VPN接口时出错", e);
        }
        vpnInterface = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stopVpnReceiver);
        } catch (IllegalArgumentException e) {
            // Ignore
        }
        cleanup();
        Log.d(TAG, "VPN服务已损坏。");
    }

    private static class IpAddressInfo {
        final String ip; final int networkLength;
        IpAddressInfo(String ip, int len) { this.ip = ip; this.networkLength = len; }
    }

    private IpAddressInfo parseIpv4Address(String addr) {
        String[] parts = addr.split("/");
        return new IpAddressInfo(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 24);
    }

    private IpAddressInfo parseCidr(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        return new IpAddressInfo(parts[0], Integer.parseInt(parts[1]));
    }
}