package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;

import com.easytier.jni.EasyTierManager;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EasyTier功能控制器
 * 集中管理EasyTier的所有功能：配置、状态、UI对话框、服务控制
 */
public class EasyTierController {
    private static final String TAG = "EasyTierController";
    private static final String EASYTIER_PREFS = "easytier_preferences";
    private static final String KEY_TOML_CONFIG = "toml_config_string";

    private final Activity activity;
    private final VpnPermissionCallback vpnCallback;
    private EasyTierManager easyTierManager;
    private AlertDialog currentDialog;

    public interface VpnPermissionCallback {
        void requestVpnPermission();
    }

    public EasyTierController(Activity activity, VpnPermissionCallback callback) {
        this.activity = activity;
        this.vpnCallback = callback;
        initEasyTierManager();
    }

    // ==================== 初始化和生命周期 ====================

    private void initEasyTierManager() {
        String config = getEasyTierConfig();
        String instanceName = "Default";

        if (easyTierManager != null && easyTierManager.getLatestNetworkInfoJson() != null) {
            easyTierManager.stop();
        }
        LimeLog.info("使用的easytier配置为：\n" + config);
        easyTierManager = new EasyTierManager(activity, instanceName, config);
        LimeLog.info(TAG + ": EasyTierManager initialized with instance: " + instanceName);
    }

    public void onDestroy() {
        if (easyTierManager != null) {
            easyTierManager.stop();
        }
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
    }

    // ==================== 主要公共方法 ====================

    public void showControlDialog() {
        if (easyTierManager == null) {
            Toast.makeText(activity, "EasyTier Manager尚未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        createAndShowDialog();
    }

    public void handleVpnPermissionResult(int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            LimeLog.info(TAG + ": VPN权限已获取，启动EasyTier Manager。");
            easyTierManager.start();
            Toast.makeText(activity, "EasyTier服务正在启动...", Toast.LENGTH_SHORT).show();
        } else {
            LimeLog.warning(TAG + ": VPN权限被拒绝。");
            Toast.makeText(activity, "需要VPN权限才能启动服务。", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 对话框管理 ====================

    private void createAndShowDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LayoutInflater inflater = LayoutInflater.from(activity);
        View dialogView = inflater.inflate(R.layout.dialog_easytier_panel, null);
        builder.setView(dialogView);
        builder.setTitle("EasyTier 控制面板");

        builder.setPositiveButton("启动/停止", null);
        builder.setNeutralButton("保存配置", null);
        builder.setNegativeButton("关闭", null);

        currentDialog = builder.create();
        currentDialog.setOnShowListener(dialogInterface -> {
            setupDialogButtons(dialogView);
            initializeTabs(dialogView);
            loadConfigurationToUi(dialogView);
            setupAdvancedFlags(dialogView);
            refreshStatus(dialogView);
        });

        currentDialog.show();
    }

    private void setupDialogButtons(View dialogView) {
        final Button positiveButton = currentDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        final Button neutralButton = currentDialog.getButton(AlertDialog.BUTTON_NEUTRAL);

        // 刷新按钮
        dialogView.findViewById(R.id.button_refresh_status).setOnClickListener(v -> {
            refreshStatus(dialogView);
            Toast.makeText(activity, "状态已刷新", Toast.LENGTH_SHORT).show();
        });

        // 启动/停止按钮
        positiveButton.setOnClickListener(v -> {
            if (easyTierManager.getLatestNetworkInfoJson() != null) {
                Toast.makeText(activity, "Easytier服务已停止", Toast.LENGTH_SHORT).show();
                easyTierManager.stop();
                currentDialog.dismiss();
            } else {
                saveConfigurationFromUi(dialogView,  false);
                vpnCallback.requestVpnPermission();
                currentDialog.dismiss();
            }
        });

        // 保存配置按钮
        neutralButton.setOnClickListener(v -> {
            saveConfigurationFromUi(dialogView, true);
        });
    }

    private void initializeTabs(View dialogView) {
        Button tabStatusButton = dialogView.findViewById(R.id.tab_button_status);
        Button tabConfigButton = dialogView.findViewById(R.id.tab_button_config);
        ScrollView statusContent = dialogView.findViewById(R.id.tab_content_status);
        ScrollView configContent = dialogView.findViewById(R.id.tab_content_config);

        tabStatusButton.setOnClickListener(v -> {
            statusContent.setVisibility(View.VISIBLE);
            configContent.setVisibility(View.GONE);
            tabStatusButton.setEnabled(false);
            tabConfigButton.setEnabled(true);
        });

        tabConfigButton.setOnClickListener(v -> {
            statusContent.setVisibility(View.GONE);
            configContent.setVisibility(View.VISIBLE);
            tabStatusButton.setEnabled(true);
            tabConfigButton.setEnabled(false);
        });

        // 默认选中状态页
        tabStatusButton.performClick();
    }

    private void setupAdvancedFlags(View dialogView) {
        LinearLayout flagsContainer = dialogView.findViewById(R.id.advanced_flags_container);
        ImageView flagsArrow = dialogView.findViewById(R.id.advanced_flags_arrow);
        dialogView.findViewById(R.id.advanced_flags_header).setOnClickListener(v -> {
            boolean isVisible = flagsContainer.getVisibility() == View.VISIBLE;
            flagsContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            flagsArrow.setRotation(isVisible ? 0 : 180);
        });
    }

    // ==================== 配置管理 ====================

    private String getEasyTierConfig() {
        SharedPreferences prefs = activity.getSharedPreferences(EASYTIER_PREFS, Context.MODE_PRIVATE);
        String defaultConfig = "instance_name = \"Default\"\n" +
                "hostname = \"moonlight-V+\"\n" +
                "ipv4 = \"10.0.0.1/24\"\n" +
                "dhcp = false\n" +
                "listeners = [\"tcp://0.0.0.0:11010\", \"udp://0.0.0.0:11010\", \"wg://0.0.0.0:11011\"]\n" +
                "rpc_portal = \"0.0.0.0:0\"\n" +
                "\n" +
                "[network_identity]\n" +
                "network_name = \"easytier\"\n" +
                "network_secret = \"\"\n" +
                "\n" +
                "[[peer]]\n" +
                "uri = \"tcp://public.easytier.top:11010\"\n" +
                "\n" +
                "[flags]\n";
        return prefs.getString(KEY_TOML_CONFIG, defaultConfig);
    }

    private void loadConfigurationToUi(View dialogView) {
        String currentTomlConfig = getEasyTierConfig();

        EditText editNetworkName = dialogView.findViewById(R.id.edit_network_name);
        EditText editNetworkSecret = dialogView.findViewById(R.id.edit_network_secret);
        EditText editIpv4 = dialogView.findViewById(R.id.edit_ipv4);
        EditText editListeners = dialogView.findViewById(R.id.edit_listeners);
        EditText editPeers = dialogView.findViewById(R.id.edit_peers);

        Switch flagUseSmoltcp = dialogView.findViewById(R.id.flag_use_smoltcp);
        Switch flagLatencyFirst = dialogView.findViewById(R.id.flag_latency_first);
        Switch flagDisableP2p = dialogView.findViewById(R.id.flag_disable_p2p);
        Switch flagPrivateMode = dialogView.findViewById(R.id.flag_private_mode);
        Switch flagEnableIpv6 = dialogView.findViewById(R.id.flag_enable_ipv6);
        Switch flagEnableKcpProxy = dialogView.findViewById(R.id.flag_enable_kcp_proxy);
        Switch flagDisableKcpInput = dialogView.findViewById(R.id.flag_disable_kcp_input);
        Switch flagEnableQuicProxy = dialogView.findViewById(R.id.flag_enable_quic_proxy);
        Switch flagDisableQuicInput = dialogView.findViewById(R.id.flag_disable_quic_input);
        Switch flagProxyForwardBySystem = dialogView.findViewById(R.id.flag_proxy_forward_by_system);
        Switch flagEnableEncryption = dialogView.findViewById(R.id.flag_enable_encryption);
        Switch flagDisableUdpHolePunching = dialogView.findViewById(R.id.flag_disable_udp_hole_punching);
        Switch flagDisableSymHolePunching = dialogView.findViewById(R.id.flag_disable_sym_hole_punching);

        // 加载基本配置
        editNetworkName.setText(extractValue(currentTomlConfig, "network_name", ""));
        editNetworkSecret.setText(extractValue(currentTomlConfig, "network_secret", ""));

        String ipv4Full = extractValue(currentTomlConfig, "ipv4", "");
        if (ipv4Full.contains("/")) {
            editIpv4.setText(ipv4Full.split("/")[0]);
        } else {
            editIpv4.setText(ipv4Full);
        }

        editListeners.setText(extractListAsString(currentTomlConfig, "listeners"));
        editPeers.setText(extractListAsString(currentTomlConfig, "uri"));

        // 加载Flags
        flagUseSmoltcp.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "use_smoltcp", "false")));
        flagLatencyFirst.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "latency_first", "false")));
        flagDisableP2p.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "disable_p2p", "false")));
        flagPrivateMode.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "private_mode", "false")));

        boolean isIpv6Enabled = Boolean.parseBoolean(extractValue(currentTomlConfig, "enable_ipv6", "true"));
        flagEnableIpv6.setChecked(!isIpv6Enabled);

        flagEnableKcpProxy.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "enable_kcp_proxy", "false")));
        flagDisableKcpInput.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "disable_kcp_input", "false")));
        flagEnableQuicProxy.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "enable_quic_proxy", "false")));
        flagDisableQuicInput.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "disable_quic_input", "false")));
        flagProxyForwardBySystem.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "proxy_forward_by_system", "false")));

        boolean isEncryptionEnabled = Boolean.parseBoolean(extractValue(currentTomlConfig, "enable_encryption", "true"));
        flagEnableEncryption.setChecked(!isEncryptionEnabled);

        flagDisableUdpHolePunching.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "disable_udp_hole_punching", "false")));
        flagDisableSymHolePunching.setChecked(Boolean.parseBoolean(extractValue(currentTomlConfig, "disable_sym_hole_punching", "false")));
    }

    private void saveConfigurationFromUi(View dialogView, boolean showToast) {
        // 获取UI控件
        EditText editNetworkName = dialogView.findViewById(R.id.edit_network_name);
        EditText editNetworkSecret = dialogView.findViewById(R.id.edit_network_secret);
        EditText editIpv4 = dialogView.findViewById(R.id.edit_ipv4);
        EditText editListeners = dialogView.findViewById(R.id.edit_listeners);
        EditText editPeers = dialogView.findViewById(R.id.edit_peers);

        Switch flagUseSmoltcp = dialogView.findViewById(R.id.flag_use_smoltcp);
        Switch flagLatencyFirst = dialogView.findViewById(R.id.flag_latency_first);
        Switch flagDisableP2p = dialogView.findViewById(R.id.flag_disable_p2p);
        Switch flagPrivateMode = dialogView.findViewById(R.id.flag_private_mode);
        Switch flagEnableIpv6 = dialogView.findViewById(R.id.flag_enable_ipv6);
        Switch flagEnableKcpProxy = dialogView.findViewById(R.id.flag_enable_kcp_proxy);
        Switch flagDisableKcpInput = dialogView.findViewById(R.id.flag_disable_kcp_input);
        Switch flagEnableQuicProxy = dialogView.findViewById(R.id.flag_enable_quic_proxy);
        Switch flagDisableQuicInput = dialogView.findViewById(R.id.flag_disable_quic_input);
        Switch flagProxyForwardBySystem = dialogView.findViewById(R.id.flag_proxy_forward_by_system);
        Switch flagEnableEncryption = dialogView.findViewById(R.id.flag_enable_encryption);
        Switch flagDisableUdpHolePunching = dialogView.findViewById(R.id.flag_disable_udp_hole_punching);
        Switch flagDisableSymHolePunching = dialogView.findViewById(R.id.flag_disable_sym_hole_punching);

        // 构建新的TOML配置
        String newToml = buildTomlFromUi(
                editNetworkName.getText().toString(),
                editNetworkSecret.getText().toString(),
                editIpv4.getText().toString(),
                editListeners.getText().toString(),
                editPeers.getText().toString(),
                flagUseSmoltcp.isChecked(),
                flagLatencyFirst.isChecked(),
                flagDisableP2p.isChecked(),
                flagPrivateMode.isChecked(),
                flagEnableIpv6.isChecked(),
                flagEnableKcpProxy.isChecked(),
                flagDisableKcpInput.isChecked(),
                flagEnableQuicProxy.isChecked(),
                flagDisableQuicInput.isChecked(),
                flagProxyForwardBySystem.isChecked(),
                flagEnableEncryption.isChecked(),
                flagDisableUdpHolePunching.isChecked(),
                flagDisableSymHolePunching.isChecked()
        );

        // 保存配置
        activity.getSharedPreferences(EASYTIER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOML_CONFIG, newToml)
                .apply();

        // 重新初始化
        initEasyTierManager();

        // 刷新状态
        refreshStatus(dialogView);

        if (showToast) {
            Toast.makeText(activity, "配置已保存，服务已根据新配置重新初始化。", Toast.LENGTH_LONG).show();
        }
    }

    private String buildTomlFromUi(
            String networkName, String networkSecret, String ipv4, String listeners, String peers,
            boolean useSmoltcp, boolean latencyFirst, boolean disableP2p, boolean privateMode, boolean enableIpv6,
            boolean enableKcpProxy, boolean disableKcpInput, boolean enableQuicProxy, boolean disableQuicInput,
            boolean proxyForwardBySystem, boolean enableEncryption, boolean disableUdpHolePunching, boolean disableSymHolePunching) {

        StringBuilder sb = new StringBuilder();
        sb.append("hostname = \"moonlight-V+\"\n");
        sb.append("instance_name = \"Default\"\n");
        sb.append("dhcp = false\n");
        sb.append("ipv4 = \"").append(ipv4).append("/24\"\n");

        // 构建listeners
        if (!TextUtils.isEmpty(listeners)) {
            String[] items = listeners.split("\n");
            List<String> quotedItems = new ArrayList<>();
            for (String item : items) {
                if (!item.trim().isEmpty()) quotedItems.add("\"" + item.trim() + "\"");
            }
            if (!quotedItems.isEmpty()) {
                sb.append("listeners = [").append(TextUtils.join(", ", quotedItems)).append("]\n");
            }
        }

        sb.append("rpc_portal = \"0.0.0.0:0\"\n");
        sb.append("\n[network_identity]\n");

        if (!TextUtils.isEmpty(networkName)) {
            sb.append("network_name = \"").append(networkName).append("\"\n");
        }
        if (!TextUtils.isEmpty(networkSecret)) {
            sb.append("network_secret = \"").append(networkSecret).append("\"\n");
        }

        // 构建peers
        String[] peerItems = peers.split("\n");
        for (String peer : peerItems) {
            if (!peer.trim().isEmpty()) {
                sb.append("\n[[peer]]\n");
                sb.append("uri = \"").append(peer.trim()).append("\"\n");
            }
        }

        // 构建[flags]部分
        sb.append("\n[flags]\n");
        appendFlagIfNotDefault(sb, "use_smoltcp", useSmoltcp, false);
        appendFlagIfNotDefault(sb, "latency_first", latencyFirst, false);
        appendFlagIfNotDefault(sb, "disable_p2p", disableP2p, false);
        appendFlagIfNotDefault(sb, "private_mode", privateMode, false);
        appendFlagIfNotDefault(sb, "enable_ipv6", !enableIpv6, true);
        appendFlagIfNotDefault(sb, "enable_kcp_proxy", enableKcpProxy, false);
        appendFlagIfNotDefault(sb, "disable_kcp_input", disableKcpInput, false);
        appendFlagIfNotDefault(sb, "enable_quic_proxy", enableQuicProxy, false);
        appendFlagIfNotDefault(sb, "disable_quic_input", disableQuicInput, false);
        appendFlagIfNotDefault(sb, "proxy_forward_by_system", proxyForwardBySystem, false);
        appendFlagIfNotDefault(sb, "enable_encryption", !enableEncryption, true);
        appendFlagIfNotDefault(sb, "disable_udp_hole_punching", disableUdpHolePunching, false);
        appendFlagIfNotDefault(sb, "disable_sym_hole_punching", disableSymHolePunching, false);

        return sb.toString();
    }

    // ==================== 状态管理 ====================

    private void refreshStatus(View dialogView) {
        String json = (easyTierManager != null) ? easyTierManager.getLatestNetworkInfoJson() : null;
        LinearLayout statusContainer = dialogView.findViewById(R.id.panel_status_container);
        updateStatusUi(statusContainer, json);

        Button positiveButton = currentDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        boolean isRunningNow = (json != null && !json.isEmpty());
        positiveButton.setText(isRunningNow ? "停止服务" : "启动服务");
    }

    private void updateStatusUi(LinearLayout container, String json) {
        container.removeAllViews();

        if (json == null || json.isEmpty()) {
            TextView placeholder = new TextView(activity);
            placeholder.setText("服务未运行或正在连接...\n请点击刷新按钮获取最新状态。");
            placeholder.setGravity(Gravity.CENTER);
            int padding = (int) (40 * activity.getResources().getDisplayMetrics().density);
            placeholder.setPadding(0, padding, 0, padding);
            container.addView(placeholder);
            return;
        }

        EasyTierDisplayInfo displayInfo = parseNetworkInfoForDialog(json, "Default");

        // 添加本机信息
        addSectionTitle(container, "本机信息");
        addStatusRow(container, "主机名:", displayInfo.hostname);
        addStatusRow(container, "虚拟 IP:", displayInfo.virtualIp);
        addStatusRow(container, "公网 IP:", displayInfo.publicIp);
        addStatusRow(container, "NAT 类型:", displayInfo.natType);

        // 添加对等节点信息
        addSectionTitle(container, "对等节点 (" + displayInfo.finalPeerList.size() + ")");

        if (displayInfo.finalPeerList.isEmpty()) {
            TextView noPeersText = new TextView(activity);
            noPeersText.setText("暂无其他节点");
            int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
            noPeersText.setPadding(padding, padding / 2, 0, padding / 2);
            container.addView(noPeersText);
        } else {
            LayoutInflater inflater = LayoutInflater.from(activity);
            for (FinalPeerInfo peer : displayInfo.finalPeerList) {
                View peerView = inflater.inflate(R.layout.dialog_peer_info_item, container, false);

                TextView hostname = peerView.findViewById(R.id.peer_hostname);
                TextView virtualIp = peerView.findViewById(R.id.peer_value_virtual_ip);
                TextView natType = peerView.findViewById(R.id.peer_value_nat_type);
                TextView connectionLabel = peerView.findViewById(R.id.peer_label_connection);
                TextView connectionValue = peerView.findViewById(R.id.peer_value_connection);
                TextView latency = peerView.findViewById(R.id.peer_value_latency);
                TextView traffic = peerView.findViewById(R.id.peer_value_traffic);

                // 填充主机名和警告
                String title = peer.hostname;
                if (!peer.isInSameSubnet) {
                    title += " (网段不匹配!)";
                    hostname.setTextColor(Color.RED);
                } else if (!peer.isDirectConnection) {
                    title += " (中转)";
                }
                hostname.setText(title);

                // 填充详细信息
                virtualIp.setText(peer.virtualIp != null ? peer.virtualIp : "N/A");
                natType.setText(peer.natType != null ? peer.natType : "N/A");
                latency.setText(peer.latency != null ? peer.latency : "N/A");
                traffic.setText(peer.traffic != null ? peer.traffic : "N/A");

                String connLabelText = peer.isDirectConnection ? "物理地址:" : "下一跳节点:";
                connectionLabel.setText(connLabelText);
                connectionValue.setText(peer.connectionDetails != null ? peer.connectionDetails : "N/A");

                container.addView(peerView);
            }
        }
    }

    private EasyTierDisplayInfo parseNetworkInfoForDialog(String jsonString, String instanceName) {
        EasyTierDisplayInfo displayInfo = new EasyTierDisplayInfo();
        try {
            JSONObject root = new JSONObject(jsonString);
            JSONObject instance = root.getJSONObject("map").getJSONObject(instanceName);

            // 解析本机信息
            JSONObject myNode = instance.getJSONObject("my_node_info");
            String myIp = null;
            int myPrefix = 0;
            displayInfo.hostname = myNode.getString("hostname");
            displayInfo.version = myNode.getString("version");

            JSONObject virtualIpv4 = myNode.optJSONObject("virtual_ipv4");
            if (virtualIpv4 != null) {
                myPrefix = virtualIpv4.getInt("network_length");
                myIp = ipFromInt(virtualIpv4.getJSONObject("address").getInt("addr"));
                displayInfo.virtualIp = myIp + "/" + myPrefix;
            } else {
                displayInfo.virtualIp = "获取中...";
            }

            JSONObject stunInfo = myNode.getJSONObject("stun_info");
            JSONArray publicIps = stunInfo.optJSONArray("public_ip");
            if (publicIps != null && publicIps.length() > 0) {
                StringBuilder ipBuilder = new StringBuilder();
                for (int i = 0; i < publicIps.length(); i++) {
                    if (i > 0) ipBuilder.append("\n");
                    ipBuilder.append(publicIps.getString(i));
                }
                displayInfo.publicIp = ipBuilder.toString();
            } else {
                displayInfo.publicIp = "N/A";
            }

            displayInfo.natType = parseNatType(stunInfo.getInt("udp_nat_type"));

            // 解析路由和对等连接
            Map<Long, RouteData> routesMap = parseRoutesToJavaMap(instance.getJSONArray("routes"));
            Map<Long, PeerConnectionData> peersMap = parsePeersToJavaMap(instance.getJSONArray("peers"));

            List<FinalPeerInfo> finalPeerList = new ArrayList<>();
            for (RouteData route : routesMap.values()) {
                boolean inSameSubnet = true;
                if (myIp != null && myPrefix > 0 && !route.virtualIp.equals("无")) {
                    inSameSubnet = isInSameSubnet(myIp, route.virtualIp, myPrefix);
                }

                PeerConnectionData peerConn = peersMap.get(route.peerId);

                if (peerConn != null) {
                    // 直接连接
                    finalPeerList.add(new FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            true,
                            inSameSubnet,
                            peerConn.physicalAddr,
                            (peerConn.latencyUs / 1000) + " ms",
                            formatBytes(peerConn.rxBytes) + " / " + formatBytes(peerConn.txBytes),
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ));
                } else {
                    // 中继路由
                    RouteData nextHop = routesMap.get(route.nextHopPeerId);
                    String nextHopHostname = (nextHop != null) ? nextHop.hostname : "未知";
                    finalPeerList.add(new FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            false,
                            inSameSubnet,
                            "通过 " + nextHopHostname,
                            route.pathLatency + " ms (路径)",
                            "N/A",
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ));
                }
            }

            finalPeerList.sort(Comparator.comparing(p -> p.hostname));
            displayInfo.finalPeerList = finalPeerList;

        } catch (Exception e) {
            LimeLog.warning("解析JSON失败:" + e);
            displayInfo.hostname = "解析错误";
            displayInfo.version = e.getMessage();
        }
        return displayInfo;
    }

    // ==================== UI辅助方法 ====================

    private void addStatusRow(LinearLayout parent, String label, String value) {
        LinearLayout rowLayout = new LinearLayout(activity);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);

        int padding = (int) (8 * activity.getResources().getDisplayMetrics().density);
        rowLayout.setPadding(0, padding, 0, padding);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                (int) (120 * activity.getResources().getDisplayMetrics().density), // 120dp
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelView.setLayoutParams(labelParams);

        TextView valueView = new TextView(activity);
        valueView.setText(value != null ? value : "N/A");
        valueView.setTextIsSelectable(true);

        rowLayout.addView(labelView);
        rowLayout.addView(valueView);
        parent.addView(rowLayout);
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(16f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );

        float density = activity.getResources().getDisplayMetrics().density;
        params.setMargins(0, (int) (16 * density), 0, (int) (8 * density));
        titleView.setLayoutParams(params);

        parent.addView(titleView);
    }

    // ==================== 工具方法 ====================

    private String extractValue(String toml, String key, String defaultValue) {
        for (String line : toml.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    return line.split("=", 2)[1].trim().replace("\"", "");
                } catch (Exception e) { /* ignore */ }
            }
        }
        return defaultValue;
    }

    private String extractListAsString(String toml, String key) {
        if ("uri".equals(key)) {
            StringBuilder peers = new StringBuilder();
            for (String line : toml.split("\n")) {
                line = line.trim();
                if (line.startsWith("uri =")) {
                    if (peers.length() > 0) peers.append("\n");
                    peers.append(line.split("=", 2)[1].trim().replace("\"", ""));
                }
            }
            return peers.toString();
        }
        for (String line : toml.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    String list = line.substring(line.indexOf('[') + 1, line.lastIndexOf(']'));
                    return list.replace("\"", "").replace(", ", "\n");
                } catch (Exception e) { /* ignore */ }
            }
        }
        return "";
    }

    private void appendFlagIfNotDefault(StringBuilder sb, String key, boolean value, boolean defaultValue) {
        if (value != defaultValue) {
            sb.append(key).append(" = ").append(value).append("\n");
        }
    }

    private String ipFromInt(int addr) {
        return ((addr >>> 24) & 0xFF) + "." + ((addr >>> 16) & 0xFF) + "." + ((addr >>> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String parseNatType(int typeCode) {
        switch (typeCode) {
            case 0: return "Unknown (未知类型)";
            case 1: return "Open Internet (开放互联网)";
            case 2: return "No PAT (无端口转换)";
            case 3: return "Full Cone (完全锥形)";
            case 4: return "Restricted Cone (限制锥形)";
            case 5: return "Port Restricted (端口限制锥形)";
            case 6: return "Symmetric (对称型)";
            case 7: return "Symmetric UDP Firewall (对称UDP防火墙)";
            case 8: return "Symmetric Easy Inc (对称型-端口递增)";
            case 9: return "Symmetric Easy Dec (对称型-端口递减)";
            default: return "Other Type (" + typeCode + ")";
        }
    }

    private boolean isInSameSubnet(String ip1, String ip2, int prefix) {
        try {
            int ip1Int = ipToInt(ip1);
            int ip2Int = ipToInt(ip2);
            int mask = -1 << (32 - prefix);
            int network1 = ip1Int & mask;
            int network2 = ip2Int & mask;
            return network1 == network2;
        } catch (Exception e) {
            LimeLog.warning("未能检查子网的IP：" + ip1 + ", " + ip2 + e);
            return false;
        }
    }

    private int ipToInt(String ip) {
        String[] parts = ip.split("\\.");
        return (Integer.parseInt(parts[0]) << 24) |
                (Integer.parseInt(parts[1]) << 16) |
                (Integer.parseInt(parts[2]) << 8) |
                (Integer.parseInt(parts[3]));
    }

    private Map<Long, RouteData> parseRoutesToJavaMap(JSONArray routesJson) throws Exception {
        Map<Long, RouteData> map = new HashMap<>();
        for (int i = 0; i < routesJson.length(); i++) {
            JSONObject route = routesJson.getJSONObject(i);
            long peerId = route.getLong("peer_id");
            JSONObject ipv4AddrJson = route.optJSONObject("ipv4_addr");
            String virtualIp = (ipv4AddrJson != null) ? ipFromInt(ipv4AddrJson.getJSONObject("address").getInt("addr")) : "无";

            map.put(peerId, new RouteData(
                    peerId,
                    route.getString("hostname"),
                    virtualIp,
                    route.getLong("next_hop_peer_id"),
                    route.getInt("path_latency"),
                    route.getInt("cost"),
                    route.getString("version"),
                    parseNatType(route.getJSONObject("stun_info").getInt("udp_nat_type")),
                    route.getString("inst_id")
            ));
        }
        return map;
    }

    private Map<Long, PeerConnectionData> parsePeersToJavaMap(JSONArray peersJson) throws Exception {
        Map<Long, PeerConnectionData> map = new HashMap<>();
        for (int i = 0; i < peersJson.length(); i++) {
            JSONObject peer = peersJson.getJSONObject(i);
            JSONArray conns = peer.getJSONArray("conns");
            if (conns.length() > 0) {
                JSONObject conn = conns.getJSONObject(0);
                long peerId = conn.getLong("peer_id");
                map.put(peerId, new PeerConnectionData(
                        peerId,
                        conn.getJSONObject("tunnel").getJSONObject("remote_addr").getString("url"),
                        conn.getJSONObject("stats").getLong("latency_us"),
                        conn.getJSONObject("stats").getLong("rx_bytes"),
                        conn.getJSONObject("stats").getLong("tx_bytes")
                ));
            }
        }
        return map;
    }

    // ==================== 内部数据类 ====================

    private static class EasyTierDisplayInfo {
        String hostname;
        String version;
        String virtualIp;
        String publicIp;
        String natType;
        List<FinalPeerInfo> finalPeerList = new ArrayList<>();
    }

    private static class FinalPeerInfo {
        final String hostname, virtualIp, connectionDetails, latency, traffic, version, natType, instId;
        final boolean isDirectConnection;
        final boolean isInSameSubnet;
        final int routeCost;
        final long nextHopPeerId, peerId;

        FinalPeerInfo(String hostname, String virtualIp, boolean isDirectConnection, boolean isInSameSubnet,
                      String connectionDetails, String latency, String traffic, String version, String natType,
                      int routeCost, long nextHopPeerId, long peerId, String instId) {
            this.hostname = hostname;
            this.virtualIp = virtualIp;
            this.isDirectConnection = isDirectConnection;
            this.isInSameSubnet = isInSameSubnet;
            this.connectionDetails = connectionDetails;
            this.latency = latency;
            this.traffic = traffic;
            this.version = version;
            this.natType = natType;
            this.routeCost = routeCost;
            this.nextHopPeerId = nextHopPeerId;
            this.peerId = peerId;
            this.instId = instId;
        }
    }

    private static class RouteData {
        final long peerId, nextHopPeerId;
        final String hostname, virtualIp, version, natType, instId;
        final int pathLatency, cost;

        RouteData(long peerId, String hostname, String virtualIp, long nextHopPeerId, int pathLatency, int cost, String version, String natType, String instId) {
            this.peerId = peerId;
            this.hostname = hostname;
            this.virtualIp = virtualIp;
            this.nextHopPeerId = nextHopPeerId;
            this.pathLatency = pathLatency;
            this.cost = cost;
            this.version = version;
            this.natType = natType;
            this.instId = instId;
        }
    }

    private static class PeerConnectionData {
        final long peerId, latencyUs, rxBytes, txBytes;
        final String physicalAddr;

        PeerConnectionData(long peerId, String physicalAddr, long latencyUs, long rxBytes, long txBytes) {
            this.peerId = peerId;
            this.physicalAddr = physicalAddr;
            this.latencyUs = latencyUs;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }
}