package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import com.easytier.jni.EasyTierManager
import com.limelight.LimeLog
import com.limelight.R

import org.json.JSONArray
import org.json.JSONObject

/**
 * EasyTier功能控制器
 * 集中管理EasyTier的所有功能：配置、状态、UI对话框、服务控制
 */
class EasyTierController(
        private val activity: Activity,
        private val vpnCallback: VpnPermissionCallback
) {
    private var easyTierManager: EasyTierManager? = null
    private var currentDialog: AlertDialog? = null

    interface VpnPermissionCallback {
        fun requestVpnPermission()
    }

    init {
        initEasyTierManager()
    }

    // ==================== 初始化和生命周期 ====================

    private fun initEasyTierManager() {
        val config = getEasyTierConfig()
        val instanceName = "Default"

        if (easyTierManager != null && easyTierManager?.latestNetworkInfoJson != null) {
            easyTierManager?.stop()
        }
        LimeLog.info("使用的easytier配置为：\n$config")
        easyTierManager = EasyTierManager(activity, instanceName, config)
        LimeLog.info("$TAG: EasyTierManager initialized with instance: $instanceName")
    }

    fun onDestroy() {
        easyTierManager?.stop()
        if (currentDialog != null && currentDialog?.isShowing == true) {
            currentDialog?.dismiss()
        }
    }

    // ==================== 主要公共方法 ====================

    fun showControlDialog() {
        if (easyTierManager == null) {
            Toast.makeText(activity, "EasyTier Manager尚未初始化", Toast.LENGTH_SHORT).show()
            return
        }

        createAndShowDialog()
    }

    fun handleVpnPermissionResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            LimeLog.info("$TAG: VPN权限已获取，启动EasyTier Manager。")
            easyTierManager?.start()
            Toast.makeText(activity, "EasyTier服务正在启动...", Toast.LENGTH_SHORT).show()
        } else {
            LimeLog.warning("$TAG: VPN权限被拒绝。")
            Toast.makeText(activity, "需要VPN权限才能启动服务。", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 对话框管理 ====================

    private fun createAndShowDialog() {
        val builder = AlertDialog.Builder(activity)
        val inflater = LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_easytier_panel, null)
        builder.setView(dialogView)
        builder.setTitle("EasyTier 控制面板")

        builder.setPositiveButton("启动/停止", null)
        builder.setNeutralButton("保存配置", null)
        builder.setNegativeButton("关闭", null)

        currentDialog = builder.create()
        currentDialog?.setOnShowListener {
            setupDialogButtons(dialogView)
            initializeTabs(dialogView)
            loadConfigurationToUi(dialogView)
            setupAdvancedFlags(dialogView)
            refreshStatus(dialogView)
        }

        currentDialog?.show()
    }

    private fun setupDialogButtons(dialogView: View) {
        val positiveButton = currentDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
        val neutralButton = currentDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)

        // 刷新按钮
        dialogView.findViewById<View>(R.id.button_refresh_status).setOnClickListener {
            refreshStatus(dialogView)
            Toast.makeText(activity, "状态已刷新", Toast.LENGTH_SHORT).show()
        }

        // 启动/停止按钮
        positiveButton?.setOnClickListener {
            if (easyTierManager?.latestNetworkInfoJson != null) {
                Toast.makeText(activity, "Easytier服务已停止", Toast.LENGTH_SHORT).show()
                easyTierManager?.stop()
                currentDialog?.dismiss()
            } else {
                saveConfigurationFromUi(dialogView, false)
                vpnCallback.requestVpnPermission()
                currentDialog?.dismiss()
            }
        }

        // 保存配置按钮
        neutralButton?.setOnClickListener {
            saveConfigurationFromUi(dialogView, true)
        }
    }

    private fun initializeTabs(dialogView: View) {
        val tabStatusButton = dialogView.findViewById<Button>(R.id.tab_button_status)
        val tabConfigButton = dialogView.findViewById<Button>(R.id.tab_button_config)
        val statusContent = dialogView.findViewById<ScrollView>(R.id.tab_content_status)
        val configContent = dialogView.findViewById<ScrollView>(R.id.tab_content_config)

        tabStatusButton.setOnClickListener {
            statusContent.visibility = View.VISIBLE
            configContent.visibility = View.GONE
            tabStatusButton.isEnabled = false
            tabConfigButton.isEnabled = true
        }

        tabConfigButton.setOnClickListener {
            statusContent.visibility = View.GONE
            configContent.visibility = View.VISIBLE
            tabStatusButton.isEnabled = true
            tabConfigButton.isEnabled = false
        }

        // 默认选中状态页
        tabStatusButton.performClick()
    }

    private fun setupAdvancedFlags(dialogView: View) {
        val flagsContainer = dialogView.findViewById<LinearLayout>(R.id.advanced_flags_container)
        val flagsArrow = dialogView.findViewById<ImageView>(R.id.advanced_flags_arrow)
        dialogView.findViewById<View>(R.id.advanced_flags_header).setOnClickListener {
            val isVisible = flagsContainer.visibility == View.VISIBLE
            flagsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            flagsArrow.rotation = if (isVisible) 0f else 180f
        }
    }

    // ==================== 配置管理 ====================

    private fun getEasyTierConfig(): String {
        val prefs = activity.getSharedPreferences(EASYTIER_PREFS, Context.MODE_PRIVATE)
        val defaultConfig = "instance_name = \"Default\"\n" +
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
                "[flags]\n"
        return prefs.getString(KEY_TOML_CONFIG, defaultConfig)!!
    }

    private fun loadConfigurationToUi(dialogView: View) {
        val currentTomlConfig = getEasyTierConfig()

        val editNetworkName = dialogView.findViewById<EditText>(R.id.edit_network_name)
        val editNetworkSecret = dialogView.findViewById<EditText>(R.id.edit_network_secret)
        val editIpv4 = dialogView.findViewById<EditText>(R.id.edit_ipv4)
        val editListeners = dialogView.findViewById<EditText>(R.id.edit_listeners)
        val editPeers = dialogView.findViewById<EditText>(R.id.edit_peers)

        val flagUseSmoltcp = dialogView.findViewById<Switch>(R.id.flag_use_smoltcp)
        val flagLatencyFirst = dialogView.findViewById<Switch>(R.id.flag_latency_first)
        val flagDisableP2p = dialogView.findViewById<Switch>(R.id.flag_disable_p2p)
        val flagPrivateMode = dialogView.findViewById<Switch>(R.id.flag_private_mode)
        val flagEnableIpv6 = dialogView.findViewById<Switch>(R.id.flag_enable_ipv6)
        val flagEnableKcpProxy = dialogView.findViewById<Switch>(R.id.flag_enable_kcp_proxy)
        val flagDisableKcpInput = dialogView.findViewById<Switch>(R.id.flag_disable_kcp_input)
        val flagEnableQuicProxy = dialogView.findViewById<Switch>(R.id.flag_enable_quic_proxy)
        val flagDisableQuicInput = dialogView.findViewById<Switch>(R.id.flag_disable_quic_input)
        val flagProxyForwardBySystem = dialogView.findViewById<Switch>(R.id.flag_proxy_forward_by_system)
        val flagEnableEncryption = dialogView.findViewById<Switch>(R.id.flag_enable_encryption)
        val flagDisableUdpHolePunching = dialogView.findViewById<Switch>(R.id.flag_disable_udp_hole_punching)
        val flagDisableSymHolePunching = dialogView.findViewById<Switch>(R.id.flag_disable_sym_hole_punching)

        // 加载基本配置
        editNetworkName.setText(extractValue(currentTomlConfig, "network_name", ""))
        editNetworkSecret.setText(extractValue(currentTomlConfig, "network_secret", ""))

        val ipv4Full = extractValue(currentTomlConfig, "ipv4", "")
        if (ipv4Full.contains("/")) {
            editIpv4.setText(ipv4Full.split("/")[0])
        } else {
            editIpv4.setText(ipv4Full)
        }

        editListeners.setText(extractListAsString(currentTomlConfig, "listeners"))
        editPeers.setText(extractListAsString(currentTomlConfig, "uri"))

        // 加载Flags
        flagUseSmoltcp.isChecked = extractValue(currentTomlConfig, "use_smoltcp", "false").toBoolean()
        flagLatencyFirst.isChecked = extractValue(currentTomlConfig, "latency_first", "false").toBoolean()
        flagDisableP2p.isChecked = extractValue(currentTomlConfig, "disable_p2p", "false").toBoolean()
        flagPrivateMode.isChecked = extractValue(currentTomlConfig, "private_mode", "false").toBoolean()

        val isIpv6Enabled = extractValue(currentTomlConfig, "enable_ipv6", "true").toBoolean()
        flagEnableIpv6.isChecked = !isIpv6Enabled

        flagEnableKcpProxy.isChecked = extractValue(currentTomlConfig, "enable_kcp_proxy", "false").toBoolean()
        flagDisableKcpInput.isChecked = extractValue(currentTomlConfig, "disable_kcp_input", "false").toBoolean()
        flagEnableQuicProxy.isChecked = extractValue(currentTomlConfig, "enable_quic_proxy", "false").toBoolean()
        flagDisableQuicInput.isChecked = extractValue(currentTomlConfig, "disable_quic_input", "false").toBoolean()
        flagProxyForwardBySystem.isChecked = extractValue(currentTomlConfig, "proxy_forward_by_system", "false").toBoolean()

        val isEncryptionEnabled = extractValue(currentTomlConfig, "enable_encryption", "true").toBoolean()
        flagEnableEncryption.isChecked = !isEncryptionEnabled

        flagDisableUdpHolePunching.isChecked = extractValue(currentTomlConfig, "disable_udp_hole_punching", "false").toBoolean()
        flagDisableSymHolePunching.isChecked = extractValue(currentTomlConfig, "disable_sym_hole_punching", "false").toBoolean()
    }

    private fun saveConfigurationFromUi(dialogView: View, showToast: Boolean) {
        // 获取UI控件
        val editNetworkName = dialogView.findViewById<EditText>(R.id.edit_network_name)
        val editNetworkSecret = dialogView.findViewById<EditText>(R.id.edit_network_secret)
        val editIpv4 = dialogView.findViewById<EditText>(R.id.edit_ipv4)
        val editListeners = dialogView.findViewById<EditText>(R.id.edit_listeners)
        val editPeers = dialogView.findViewById<EditText>(R.id.edit_peers)

        val flagUseSmoltcp = dialogView.findViewById<Switch>(R.id.flag_use_smoltcp)
        val flagLatencyFirst = dialogView.findViewById<Switch>(R.id.flag_latency_first)
        val flagDisableP2p = dialogView.findViewById<Switch>(R.id.flag_disable_p2p)
        val flagPrivateMode = dialogView.findViewById<Switch>(R.id.flag_private_mode)
        val flagEnableIpv6 = dialogView.findViewById<Switch>(R.id.flag_enable_ipv6)
        val flagEnableKcpProxy = dialogView.findViewById<Switch>(R.id.flag_enable_kcp_proxy)
        val flagDisableKcpInput = dialogView.findViewById<Switch>(R.id.flag_disable_kcp_input)
        val flagEnableQuicProxy = dialogView.findViewById<Switch>(R.id.flag_enable_quic_proxy)
        val flagDisableQuicInput = dialogView.findViewById<Switch>(R.id.flag_disable_quic_input)
        val flagProxyForwardBySystem = dialogView.findViewById<Switch>(R.id.flag_proxy_forward_by_system)
        val flagEnableEncryption = dialogView.findViewById<Switch>(R.id.flag_enable_encryption)
        val flagDisableUdpHolePunching = dialogView.findViewById<Switch>(R.id.flag_disable_udp_hole_punching)
        val flagDisableSymHolePunching = dialogView.findViewById<Switch>(R.id.flag_disable_sym_hole_punching)

        // 构建新的TOML配置
        val newToml = buildTomlFromUi(
                editNetworkName.text.toString(),
                editNetworkSecret.text.toString(),
                editIpv4.text.toString(),
                editListeners.text.toString(),
                editPeers.text.toString(),
                flagUseSmoltcp.isChecked,
                flagLatencyFirst.isChecked,
                flagDisableP2p.isChecked,
                flagPrivateMode.isChecked,
                flagEnableIpv6.isChecked,
                flagEnableKcpProxy.isChecked,
                flagDisableKcpInput.isChecked,
                flagEnableQuicProxy.isChecked,
                flagDisableQuicInput.isChecked,
                flagProxyForwardBySystem.isChecked,
                flagEnableEncryption.isChecked,
                flagDisableUdpHolePunching.isChecked,
                flagDisableSymHolePunching.isChecked
        )

        // 保存配置
        activity.getSharedPreferences(EASYTIER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOML_CONFIG, newToml)
                .apply()

        // 重新初始化
        initEasyTierManager()

        // 刷新状态
        refreshStatus(dialogView)

        if (showToast) {
            Toast.makeText(activity, "配置已保存，服务已根据新配置重新初始化。", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildTomlFromUi(
            networkName: String, networkSecret: String, ipv4: String, listeners: String, peers: String,
            useSmoltcp: Boolean, latencyFirst: Boolean, disableP2p: Boolean, privateMode: Boolean, enableIpv6: Boolean,
            enableKcpProxy: Boolean, disableKcpInput: Boolean, enableQuicProxy: Boolean, disableQuicInput: Boolean,
            proxyForwardBySystem: Boolean, enableEncryption: Boolean, disableUdpHolePunching: Boolean, disableSymHolePunching: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("hostname = \"moonlight-V+\"\n")
        sb.append("instance_name = \"Default\"\n")
        sb.append("dhcp = false\n")
        sb.append("ipv4 = \"").append(ipv4).append("/24\"\n")

        // 构建listeners
        if (!TextUtils.isEmpty(listeners)) {
            val items = listeners.split("\n")
            val quotedItems = ArrayList<String>()
            for (item in items) {
                if (item.trim().isNotEmpty()) quotedItems.add("\"" + item.trim() + "\"")
            }
            if (quotedItems.isNotEmpty()) {
                sb.append("listeners = [").append(TextUtils.join(", ", quotedItems)).append("]\n")
            }
        }

        sb.append("rpc_portal = \"0.0.0.0:0\"\n")
        sb.append("\n[network_identity]\n")

        if (!TextUtils.isEmpty(networkName)) {
            sb.append("network_name = \"").append(networkName).append("\"\n")
        }
        if (!TextUtils.isEmpty(networkSecret)) {
            sb.append("network_secret = \"").append(networkSecret).append("\"\n")
        }

        // 构建peers
        val peerItems = peers.split("\n")
        for (peer in peerItems) {
            if (peer.trim().isNotEmpty()) {
                sb.append("\n[[peer]]\n")
                sb.append("uri = \"").append(peer.trim()).append("\"\n")
            }
        }

        // 构建[flags]部分
        sb.append("\n[flags]\n")
        appendFlagIfNotDefault(sb, "use_smoltcp", useSmoltcp, false)
        appendFlagIfNotDefault(sb, "latency_first", latencyFirst, false)
        appendFlagIfNotDefault(sb, "disable_p2p", disableP2p, false)
        appendFlagIfNotDefault(sb, "private_mode", privateMode, false)
        appendFlagIfNotDefault(sb, "enable_ipv6", !enableIpv6, true)
        appendFlagIfNotDefault(sb, "enable_kcp_proxy", enableKcpProxy, false)
        appendFlagIfNotDefault(sb, "disable_kcp_input", disableKcpInput, false)
        appendFlagIfNotDefault(sb, "enable_quic_proxy", enableQuicProxy, false)
        appendFlagIfNotDefault(sb, "disable_quic_input", disableQuicInput, false)
        appendFlagIfNotDefault(sb, "proxy_forward_by_system", proxyForwardBySystem, false)
        appendFlagIfNotDefault(sb, "enable_encryption", !enableEncryption, true)
        appendFlagIfNotDefault(sb, "disable_udp_hole_punching", disableUdpHolePunching, false)
        appendFlagIfNotDefault(sb, "disable_sym_hole_punching", disableSymHolePunching, false)

        return sb.toString()
    }

    // ==================== 状态管理 ====================

    private fun refreshStatus(dialogView: View) {
        val json = easyTierManager?.latestNetworkInfoJson
        val statusContainer = dialogView.findViewById<LinearLayout>(R.id.panel_status_container)
        updateStatusUi(statusContainer, json)

        val positiveButton = currentDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
        val isRunningNow = json != null && json.isNotEmpty()
        positiveButton?.text = if (isRunningNow) "停止服务" else "启动服务"
    }

    private fun updateStatusUi(container: LinearLayout, json: String?) {
        container.removeAllViews()

        if (json == null || json.isEmpty()) {
            val placeholder = TextView(activity)
            placeholder.text = "服务未运行或正在连接...\n请点击刷新按钮获取最新状态。"
            placeholder.gravity = Gravity.CENTER
            val padding = (40 * activity.resources.displayMetrics.density).toInt()
            placeholder.setPadding(0, padding, 0, padding)
            container.addView(placeholder)
            return
        }

        val displayInfo = parseNetworkInfoForDialog(json, "Default")

        // 添加本机信息
        addSectionTitle(container, "本机信息")
        addStatusRow(container, "主机名:", displayInfo.hostname)
        addStatusRow(container, "虚拟 IP:", displayInfo.virtualIp)
        addStatusRow(container, "公网 IP:", displayInfo.publicIp)
        addStatusRow(container, "NAT 类型:", displayInfo.natType)

        // 添加对等节点信息
        addSectionTitle(container, "对等节点 (${displayInfo.finalPeerList.size})")

        if (displayInfo.finalPeerList.isEmpty()) {
            val noPeersText = TextView(activity)
            noPeersText.text = "暂无其他节点"
            val padding = (20 * activity.resources.displayMetrics.density).toInt()
            noPeersText.setPadding(padding, padding / 2, 0, padding / 2)
            container.addView(noPeersText)
        } else {
            val inflater = LayoutInflater.from(activity)
            for (peer in displayInfo.finalPeerList) {
                val peerView = inflater.inflate(R.layout.dialog_peer_info_item, container, false)

                val hostname = peerView.findViewById<TextView>(R.id.peer_hostname)
                val virtualIp = peerView.findViewById<TextView>(R.id.peer_value_virtual_ip)
                val natType = peerView.findViewById<TextView>(R.id.peer_value_nat_type)
                val connectionLabel = peerView.findViewById<TextView>(R.id.peer_label_connection)
                val connectionValue = peerView.findViewById<TextView>(R.id.peer_value_connection)
                val latency = peerView.findViewById<TextView>(R.id.peer_value_latency)
                val traffic = peerView.findViewById<TextView>(R.id.peer_value_traffic)

                // 填充主机名和警告
                var title = peer.hostname
                if (!peer.isInSameSubnet) {
                    title += " (网段不匹配!)"
                    hostname.setTextColor(Color.RED)
                } else if (!peer.isDirectConnection) {
                    title += " (中转)"
                }
                hostname.text = title

                // 填充详细信息
                virtualIp.text = peer.virtualIp ?: "N/A"
                natType.text = peer.natType ?: "N/A"
                latency.text = peer.latency ?: "N/A"
                traffic.text = peer.traffic ?: "N/A"

                val connLabelText = if (peer.isDirectConnection) "物理地址:" else "下一跳节点:"
                connectionLabel.text = connLabelText
                connectionValue.text = peer.connectionDetails ?: "N/A"

                container.addView(peerView)
            }
        }
    }

    private fun parseNetworkInfoForDialog(jsonString: String, instanceName: String): EasyTierDisplayInfo {
        val displayInfo = EasyTierDisplayInfo()
        try {
            val root = JSONObject(jsonString)
            val instance = root.getJSONObject("map").getJSONObject(instanceName)

            // 解析本机信息
            val myNode = instance.getJSONObject("my_node_info")
            var myIp: String? = null
            var myPrefix = 0
            displayInfo.hostname = myNode.getString("hostname")
            displayInfo.version = myNode.getString("version")

            val virtualIpv4 = myNode.optJSONObject("virtual_ipv4")
            if (virtualIpv4 != null) {
                myPrefix = virtualIpv4.getInt("network_length")
                myIp = ipFromInt(virtualIpv4.getJSONObject("address").getInt("addr"))
                displayInfo.virtualIp = "$myIp/$myPrefix"
            } else {
                displayInfo.virtualIp = "获取中..."
            }

            val stunInfo = myNode.getJSONObject("stun_info")
            val publicIps = stunInfo.optJSONArray("public_ip")
            if (publicIps != null && publicIps.length() > 0) {
                val ipBuilder = StringBuilder()
                for (i in 0 until publicIps.length()) {
                    if (i > 0) ipBuilder.append("\n")
                    ipBuilder.append(publicIps.getString(i))
                }
                displayInfo.publicIp = ipBuilder.toString()
            } else {
                displayInfo.publicIp = "N/A"
            }

            displayInfo.natType = parseNatType(stunInfo.getInt("udp_nat_type"))

            // 解析路由和对等连接
            val routesMap = parseRoutesToMap(instance.getJSONArray("routes"))
            val peersMap = parsePeersToMap(instance.getJSONArray("peers"))

            val finalPeerList = ArrayList<FinalPeerInfo>()
            for (route in routesMap.values) {
                var inSameSubnet = true
                if (myIp != null && myPrefix > 0 && route.virtualIp != "无") {
                    inSameSubnet = isInSameSubnet(myIp, route.virtualIp, myPrefix)
                }

                val peerConn = peersMap[route.peerId]

                if (peerConn != null) {
                    // 直接连接
                    finalPeerList.add(FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            true,
                            inSameSubnet,
                            peerConn.physicalAddr,
                            "${peerConn.latencyUs / 1000} ms",
                            "${formatBytes(peerConn.rxBytes)} / ${formatBytes(peerConn.txBytes)}",
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ))
                } else {
                    // 中继路由
                    val nextHop = routesMap[route.nextHopPeerId]
                    val nextHopHostname = nextHop?.hostname ?: "未知"
                    finalPeerList.add(FinalPeerInfo(
                            route.hostname,
                            route.virtualIp,
                            false,
                            inSameSubnet,
                            "通过 $nextHopHostname",
                            "${route.pathLatency} ms (路径)",
                            "N/A",
                            route.version,
                            route.natType,
                            route.cost,
                            route.nextHopPeerId,
                            route.peerId,
                            route.instId
                    ))
                }
            }

            finalPeerList.sortBy { it.hostname }
            displayInfo.finalPeerList = finalPeerList

        } catch (e: Exception) {
            LimeLog.warning("解析JSON失败:$e")
            displayInfo.hostname = "解析错误"
            displayInfo.version = e.message
        }
        return displayInfo
    }

    // ==================== UI辅助方法 ====================

    private fun addStatusRow(parent: LinearLayout, label: String, value: String?) {
        val rowLayout = LinearLayout(activity)
        rowLayout.orientation = LinearLayout.HORIZONTAL

        val padding = (8 * activity.resources.displayMetrics.density).toInt()
        rowLayout.setPadding(0, padding, 0, padding)

        val labelView = TextView(activity)
        labelView.text = label
        labelView.setTypeface(null, Typeface.BOLD)

        val labelParams = LinearLayout.LayoutParams(
                (120 * activity.resources.displayMetrics.density).toInt(), // 120dp
                LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelView.layoutParams = labelParams

        val valueView = TextView(activity)
        valueView.text = value ?: "N/A"
        valueView.setTextIsSelectable(true)

        rowLayout.addView(labelView)
        rowLayout.addView(valueView)
        parent.addView(rowLayout)
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        val titleView = TextView(activity)
        titleView.text = title
        titleView.textSize = 16f
        titleView.setTypeface(null, Typeface.BOLD)

        val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val density = activity.resources.displayMetrics.density
        params.setMargins(0, (16 * density).toInt(), 0, (8 * density).toInt())
        titleView.layoutParams = params

        parent.addView(titleView)
    }

    // ==================== 工具方法 ====================

    private fun extractValue(toml: String, key: String, defaultValue: String): String {
        for (rawLine in toml.split("\n")) {
            val line = rawLine.trim()
            if (line.startsWith("$key =")) {
                try {
                    return line.split("=", limit = 2)[1].trim().replace("\"", "")
                } catch (e: Exception) { /* ignore */ }
            }
        }
        return defaultValue
    }

    private fun extractListAsString(toml: String, key: String): String {
        if ("uri" == key) {
            val peers = StringBuilder()
            for (rawLine in toml.split("\n")) {
                val line = rawLine.trim()
                if (line.startsWith("uri =")) {
                    if (peers.isNotEmpty()) peers.append("\n")
                    peers.append(line.split("=", limit = 2)[1].trim().replace("\"", ""))
                }
            }
            return peers.toString()
        }
        for (rawLine in toml.split("\n")) {
            val line = rawLine.trim()
            if (line.startsWith("$key =")) {
                try {
                    val list = line.substring(line.indexOf('[') + 1, line.lastIndexOf(']'))
                    return list.replace("\"", "").replace(", ", "\n")
                } catch (e: Exception) { /* ignore */ }
            }
        }
        return ""
    }

    private fun appendFlagIfNotDefault(sb: StringBuilder, key: String, value: Boolean, defaultValue: Boolean) {
        if (value != defaultValue) {
            sb.append(key).append(" = ").append(value).append("\n")
        }
    }

    private fun ipFromInt(addr: Int): String {
        return "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun parseNatType(typeCode: Int): String {
        return when (typeCode) {
            0 -> "Unknown (未知类型)"
            1 -> "Open Internet (开放互联网)"
            2 -> "No PAT (无端口转换)"
            3 -> "Full Cone (完全锥形)"
            4 -> "Restricted Cone (限制锥形)"
            5 -> "Port Restricted (端口限制锥形)"
            6 -> "Symmetric (对称型)"
            7 -> "Symmetric UDP Firewall (对称UDP防火墙)"
            8 -> "Symmetric Easy Inc (对称型-端口递增)"
            9 -> "Symmetric Easy Dec (对称型-端口递减)"
            else -> "Other Type ($typeCode)"
        }
    }

    private fun isInSameSubnet(ip1: String, ip2: String, prefix: Int): Boolean {
        try {
            val ip1Int = ipToInt(ip1)
            val ip2Int = ipToInt(ip2)
            val mask = -1 shl (32 - prefix)
            val network1 = ip1Int and mask
            val network2 = ip2Int and mask
            return network1 == network2
        } catch (e: Exception) {
            LimeLog.warning("未能检查子网的IP：$ip1, $ip2$e")
            return false
        }
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    private fun parseRoutesToMap(routesJson: JSONArray): Map<Long, RouteData> {
        val map = HashMap<Long, RouteData>()
        for (i in 0 until routesJson.length()) {
            val route = routesJson.getJSONObject(i)
            val peerId = route.getLong("peer_id")
            val ipv4AddrJson = route.optJSONObject("ipv4_addr")
            val virtualIp = if (ipv4AddrJson != null) ipFromInt(ipv4AddrJson.getJSONObject("address").getInt("addr")) else "无"

            map[peerId] = RouteData(
                    peerId,
                    route.getString("hostname"),
                    virtualIp,
                    route.getLong("next_hop_peer_id"),
                    route.getInt("path_latency"),
                    route.getInt("cost"),
                    route.getString("version"),
                    parseNatType(route.getJSONObject("stun_info").getInt("udp_nat_type")),
                    route.getString("inst_id")
            )
        }
        return map
    }

    private fun parsePeersToMap(peersJson: JSONArray): Map<Long, PeerConnectionData> {
        val map = HashMap<Long, PeerConnectionData>()
        for (i in 0 until peersJson.length()) {
            val peer = peersJson.getJSONObject(i)
            val conns = peer.getJSONArray("conns")
            if (conns.length() > 0) {
                val conn = conns.getJSONObject(0)
                val peerId = conn.getLong("peer_id")
                map[peerId] = PeerConnectionData(
                        peerId,
                        conn.getJSONObject("tunnel").getJSONObject("remote_addr").getString("url"),
                        conn.getJSONObject("stats").getLong("latency_us"),
                        conn.getJSONObject("stats").getLong("rx_bytes"),
                        conn.getJSONObject("stats").getLong("tx_bytes")
                )
            }
        }
        return map
    }

    // ==================== 内部数据类 ====================

    private class EasyTierDisplayInfo {
        var hostname: String? = null
        var version: String? = null
        var virtualIp: String? = null
        var publicIp: String? = null
        var natType: String? = null
        var finalPeerList: List<FinalPeerInfo> = ArrayList()
    }

    private class FinalPeerInfo(
            val hostname: String,
            val virtualIp: String?,
            val isDirectConnection: Boolean,
            val isInSameSubnet: Boolean,
            val connectionDetails: String?,
            val latency: String?,
            val traffic: String?,
            val version: String?,
            val natType: String?,
            val routeCost: Int,
            val nextHopPeerId: Long,
            val peerId: Long,
            val instId: String?
    )

    private class RouteData(
            val peerId: Long,
            val hostname: String,
            val virtualIp: String,
            val nextHopPeerId: Long,
            val pathLatency: Int,
            val cost: Int,
            val version: String,
            val natType: String,
            val instId: String
    )

    private class PeerConnectionData(
            val peerId: Long,
            val physicalAddr: String,
            val latencyUs: Long,
            val rxBytes: Long,
            val txBytes: Long
    )

    companion object {
        private const val TAG = "EasyTierController"
        private const val EASYTIER_PREFS = "easytier_preferences"
        private const val KEY_TOML_CONFIG = "toml_config_string"
    }
}
