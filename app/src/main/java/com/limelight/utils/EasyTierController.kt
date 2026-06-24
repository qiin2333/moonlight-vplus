package com.limelight.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var currentDialog: Dialog? = null
    private val instanceName = "Default"

    private enum class EasyTierTab {
        STATUS,
        CONFIG
    }

    private data class EasyTierConfigUiState(
            val networkName: String = "",
            val networkSecret: String = "",
            val ipv4: String = "",
            val listeners: String = "",
            val peers: String = "",
            val useSmoltcp: Boolean = false,
            val latencyFirst: Boolean = false,
            val disableP2p: Boolean = false,
            val privateMode: Boolean = false,
            val disableIpv6: Boolean = false,
            val enableKcpProxy: Boolean = false,
            val disableKcpInput: Boolean = false,
            val enableQuicProxy: Boolean = false,
            val disableQuicInput: Boolean = false,
            val proxyForwardBySystem: Boolean = false,
            val disableEncryption: Boolean = false,
            val disableUdpHolePunching: Boolean = false,
            val disableSymHolePunching: Boolean = false
    )

    private data class EasyTierDialogUiState(
            val selectedTab: EasyTierTab = EasyTierTab.STATUS,
            val config: EasyTierConfigUiState = EasyTierConfigUiState(),
            val statusJson: String? = null,
            val advancedExpanded: Boolean = false
    ) {
        val isRunning: Boolean
            get() = !statusJson.isNullOrEmpty()
    }

    interface VpnPermissionCallback {
        fun requestVpnPermission()
    }

    init {
        initEasyTierManager()
    }

    // ==================== 初始化和生命周期 ====================

    private fun initEasyTierManager() {
        val config = getEasyTierConfig()

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
        val uiState = mutableStateOf(
                EasyTierDialogUiState(
                        config = loadConfigurationState(),
                        statusJson = easyTierManager?.latestNetworkInfoJson
                )
        )

        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                EasyTierPanel(
                        state = uiState.value,
                        onTabSelected = { tab ->
                            uiState.value = uiState.value.copy(selectedTab = tab)
                        },
                        onConfigChange = { config ->
                            uiState.value = uiState.value.copy(config = config)
                        },
                        onAdvancedExpandedChange = { expanded ->
                            uiState.value = uiState.value.copy(advancedExpanded = expanded)
                        },
                        onRefresh = {
                            uiState.value = uiState.value.copy(statusJson = easyTierManager?.latestNetworkInfoJson)
                            Toast.makeText(activity, "状态已刷新", Toast.LENGTH_SHORT).show()
                        },
                        onToggleService = {
                            if (uiState.value.isRunning) {
                                Toast.makeText(activity, "Easytier服务已停止", Toast.LENGTH_SHORT).show()
                                easyTierManager?.stop()
                                uiState.value = uiState.value.copy(statusJson = null)
                                currentDialog?.dismiss()
                            } else {
                                saveConfiguration(uiState.value.config, showToast = false)
                                vpnCallback.requestVpnPermission()
                                currentDialog?.dismiss()
                            }
                        },
                        onSaveConfig = {
                            saveConfiguration(uiState.value.config, showToast = true)
                            uiState.value = uiState.value.copy(statusJson = easyTierManager?.latestNetworkInfoJson)
                        },
                        onClose = {
                            currentDialog?.dismiss()
                        }
                )
            }
        }

        currentDialog = ComponentDialog(activity, R.style.AppDialogStyle).apply {
            setContentView(composeView)
        }
        currentDialog?.show()
        currentDialog?.let { AppDialogStyler.applyCustomContent(it, activity) }
    }

    @Composable
    private fun EasyTierPanel(
            state: EasyTierDialogUiState,
            onTabSelected: (EasyTierTab) -> Unit,
            onConfigChange: (EasyTierConfigUiState) -> Unit,
            onAdvancedExpandedChange: (Boolean) -> Unit,
            onRefresh: () -> Unit,
            onToggleService: () -> Unit,
            onSaveConfig: () -> Unit,
            onClose: () -> Unit
    ) {
        val accent = colorResource(R.color.crown_accent)
        val panel = colorResource(R.color.crown_panel_background)
        val card = colorResource(R.color.crown_section_background)
        val input = colorResource(R.color.crown_input_background)
        val textPrimary = colorResource(R.color.crown_text_primary)
        val textSecondary = colorResource(R.color.crown_text_secondary)
        MaterialTheme(
                colorScheme = darkColorScheme(
                        primary = accent,
                        surface = panel,
                        onSurface = textPrimary,
                        surfaceVariant = input,
                        onSurfaceVariant = textSecondary
                )
        ) {
            Box(modifier = Modifier.padding(10.dp)) {
                Surface(
                        modifier = Modifier
                                .widthIn(max = 560.dp)
                                .heightIn(max = 560.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = panel,
                        tonalElevation = 0.dp,
                        shadowElevation = 12.dp
                ) {
                    Column(
                            modifier = Modifier.padding(18.dp)
                    ) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = "EasyTier 控制面板",
                                        color = textPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                )
                                Text(
                                        text = if (state.isRunning) "服务正在运行" else "服务未运行或正在连接",
                                        color = textSecondary,
                                        fontSize = 12.5.sp
                                )
                            }
                            EasyTierStatusPill(state.isRunning)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        TabRow(
                                selectedTabIndex = if (state.selectedTab == EasyTierTab.STATUS) 0 else 1,
                                containerColor = Color.Transparent,
                                contentColor = accent
                        ) {
                            Tab(
                                    selected = state.selectedTab == EasyTierTab.STATUS,
                                    onClick = { onTabSelected(EasyTierTab.STATUS) },
                                    text = { Text("状态") }
                            )
                            Tab(
                                    selected = state.selectedTab == EasyTierTab.CONFIG,
                                    onClick = { onTabSelected(EasyTierTab.CONFIG) },
                                    text = { Text("配置") }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                                modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .heightIn(max = 360.dp)
                        ) {
                            when (state.selectedTab) {
                                EasyTierTab.STATUS -> EasyTierStatusTab(state.statusJson, onRefresh)
                                EasyTierTab.CONFIG -> EasyTierConfigTab(
                                        config = state.config,
                                        advancedExpanded = state.advancedExpanded,
                                        onConfigChange = onConfigChange,
                                        onAdvancedExpandedChange = onAdvancedExpandedChange
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                    onClick = onClose,
                                    modifier = Modifier.weight(1f)
                            ) {
                                Text("关闭")
                            }
                            ComposeButton(
                                    onClick = onSaveConfig,
                                    modifier = Modifier.weight(1.25f),
                                    colors = ButtonDefaults.buttonColors(
                                            containerColor = input,
                                            contentColor = textPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text("保存", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ComposeButton(
                                    onClick = onToggleService,
                                    modifier = Modifier.weight(1.25f),
                                    colors = ButtonDefaults.buttonColors(
                                            containerColor = accent,
                                            contentColor = colorResource(R.color.app_dialog_text_primary)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(if (state.isRunning) "停止" else "启动", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EasyTierStatusPill(isRunning: Boolean) {
        val accent = colorResource(if (isRunning) R.color.crown_accent else R.color.crown_text_secondary)
        val textColor = colorResource(R.color.crown_text_primary)
        Box(
                modifier = Modifier
                        .background(accent.copy(alpha = if (isRunning) 0.28f else 0.16f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                    text = if (isRunning) "Running" else "Idle",
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun EasyTierStatusTab(statusJson: String?, onRefresh: () -> Unit) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ComposeButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                            containerColor = colorResource(R.color.crown_input_background),
                            contentColor = colorResource(R.color.crown_text_primary)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text("刷新状态")
            }

            if (statusJson.isNullOrEmpty()) {
                EasyTierInfoCard {
                    Text(
                            text = "服务未运行或正在连接...",
                            color = colorResource(R.color.crown_text_primary),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                            text = "请点击刷新按钮获取最新状态。",
                            color = colorResource(R.color.crown_text_secondary),
                            fontSize = 13.sp
                    )
                }
                return@Column
            }

            val displayInfo = remember(statusJson) {
                parseNetworkInfoForDialog(statusJson, instanceName)
            }

            EasyTierSectionTitle("本机信息")
            EasyTierInfoCard {
                EasyTierInfoRow("主机名:", displayInfo.hostname)
                EasyTierInfoRow("虚拟 IP:", displayInfo.virtualIp)
                EasyTierInfoRow("公网 IP:", displayInfo.publicIp)
                EasyTierInfoRow("NAT 类型:", displayInfo.natType)
            }

            EasyTierSectionTitle("对等节点 (${displayInfo.finalPeerList.size})")
            if (displayInfo.finalPeerList.isEmpty()) {
                EasyTierInfoCard {
                    Text(
                            text = "暂无其他节点",
                            color = colorResource(R.color.crown_text_secondary),
                            fontSize = 13.sp
                    )
                }
            } else {
                displayInfo.finalPeerList.forEach { peer ->
                    EasyTierPeerCard(peer)
                }
            }
        }
    }

    @Composable
    private fun EasyTierConfigTab(
            config: EasyTierConfigUiState,
            advancedExpanded: Boolean,
            onConfigChange: (EasyTierConfigUiState) -> Unit,
            onAdvancedExpandedChange: (Boolean) -> Unit
    ) {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EasyTierTextField(
                    label = "Network Name",
                    value = config.networkName,
                    onValueChange = { onConfigChange(config.copy(networkName = it)) },
                    placeholder = "e.g. easytier"
            )
            EasyTierTextField(
                    label = "Network Secret",
                    value = config.networkSecret,
                    onValueChange = { onConfigChange(config.copy(networkSecret = it)) }
            )
            EasyTierTextField(
                    label = "Virtual IPv4",
                    value = config.ipv4,
                    onValueChange = { onConfigChange(config.copy(ipv4 = it)) },
                    placeholder = "e.g. 10.0.0.6, default mask /24"
            )
            EasyTierTextField(
                    label = "Listeners (one per line)",
                    value = config.listeners,
                    onValueChange = { onConfigChange(config.copy(listeners = it)) },
                    placeholder = "e.g. udp://0.0.0.0:11010",
                    minLines = 2
            )
            EasyTierTextField(
                    label = "Peers (one per line)",
                    value = config.peers,
                    onValueChange = { onConfigChange(config.copy(peers = it)) },
                    placeholder = "e.g. tcp://1.2.3.4:11010",
                    minLines = 2
            )

            TextButton(
                    onClick = { onAdvancedExpandedChange(!advancedExpanded) },
                    modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (advancedExpanded) "Hide Advanced Feature Flags" else "Show Advanced Feature Flags")
            }

            if (advancedExpanded) {
                EasyTierSwitchSection("Core Network Behavior")
                EasyTierSwitchRow("Use Smoltcp", config.useSmoltcp) { onConfigChange(config.copy(useSmoltcp = it)) }
                EasyTierSwitchRow("Latency Priority", config.latencyFirst) { onConfigChange(config.copy(latencyFirst = it)) }
                EasyTierSwitchRow("Disable P2P (Force Relay)", config.disableP2p) { onConfigChange(config.copy(disableP2p = it)) }
                EasyTierSwitchRow("Private Mode", config.privateMode) { onConfigChange(config.copy(privateMode = it)) }
                EasyTierSwitchRow("Disable IPv6", config.disableIpv6) { onConfigChange(config.copy(disableIpv6 = it)) }

                EasyTierSwitchSection("Proxy & Protocol")
                EasyTierSwitchRow("Enable KCP Proxy", config.enableKcpProxy) { onConfigChange(config.copy(enableKcpProxy = it)) }
                EasyTierSwitchRow("Disable KCP Input", config.disableKcpInput) { onConfigChange(config.copy(disableKcpInput = it)) }
                EasyTierSwitchRow("Enable QUIC Proxy", config.enableQuicProxy) { onConfigChange(config.copy(enableQuicProxy = it)) }
                EasyTierSwitchRow("Disable QUIC Input", config.disableQuicInput) { onConfigChange(config.copy(disableQuicInput = it)) }
                EasyTierSwitchRow("Use System Proxy Forwarding", config.proxyForwardBySystem) { onConfigChange(config.copy(proxyForwardBySystem = it)) }

                EasyTierSwitchSection("Security & Connection")
                EasyTierSwitchRow("Disable Encryption", config.disableEncryption) { onConfigChange(config.copy(disableEncryption = it)) }
                EasyTierSwitchRow("Disable UDP Hole Punching", config.disableUdpHolePunching) { onConfigChange(config.copy(disableUdpHolePunching = it)) }
                EasyTierSwitchRow("Disable Symmetric NAT Hole Punching", config.disableSymHolePunching) { onConfigChange(config.copy(disableSymHolePunching = it)) }
            }
        }
    }

    @Composable
    private fun EasyTierInfoCard(content: @Composable () -> Unit) {
        Card(
                colors = CardDefaults.cardColors(containerColor = colorResource(R.color.crown_section_background)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                    modifier = Modifier.padding(12.dp),
                    content = { content() }
            )
        }
    }

    @Composable
    private fun EasyTierPeerCard(peer: FinalPeerInfo) {
        val titleColor = if (!peer.isInSameSubnet) {
            Color(0xFFFF7777)
        } else {
            colorResource(R.color.crown_text_primary)
        }
        EasyTierInfoCard {
            Text(
                    text = peerTitle(peer),
                    color = titleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            EasyTierInfoRow("虚拟 IP:", peer.virtualIp)
            EasyTierInfoRow("NAT 类型:", peer.natType)
            EasyTierInfoRow(if (peer.isDirectConnection) "物理地址:" else "下一跳节点:", peer.connectionDetails)
            EasyTierInfoRow("延迟:", peer.latency)
            EasyTierInfoRow("流量:", peer.traffic)
        }
    }

    private fun peerTitle(peer: FinalPeerInfo): String {
        return when {
            !peer.isInSameSubnet -> "${peer.hostname} (网段不匹配!)"
            !peer.isDirectConnection -> "${peer.hostname} (中转)"
            else -> peer.hostname
        }
    }

    @Composable
    private fun EasyTierInfoRow(label: String, value: String?) {
        Row(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
        ) {
            Text(
                    text = label,
                    color = colorResource(R.color.crown_text_primary),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(104.dp)
            )
            Text(
                    text = value ?: "N/A",
                    color = colorResource(R.color.crown_text_secondary),
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun EasyTierSectionTitle(title: String) {
        Text(
                text = title,
                color = colorResource(R.color.crown_text_primary),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
        )
    }

    @Composable
    private fun EasyTierTextField(
            label: String,
            value: String,
            onValueChange: (String) -> Unit,
            placeholder: String = "",
            minLines: Int = 1
    ) {
        OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = {
                    if (placeholder.isNotBlank()) {
                        Text(placeholder)
                    }
                },
                minLines = minLines,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
        )
    }

    @Composable
    private fun EasyTierSwitchSection(title: String) {
        Text(
                text = title,
                color = colorResource(R.color.crown_text_secondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
    }

    @Composable
    private fun EasyTierSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
                modifier = Modifier
                        .fillMaxWidth()
                        .background(colorResource(R.color.crown_section_background), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = label,
                    color = colorResource(R.color.crown_text_primary),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.size(width = 48.dp, height = 32.dp)
            )
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

    private fun loadConfigurationState(): EasyTierConfigUiState {
        val currentTomlConfig = getEasyTierConfig()

        val ipv4Full = extractValue(currentTomlConfig, "ipv4", "")
        val ipv4 = if (ipv4Full.contains("/")) {
            ipv4Full.split("/")[0]
        } else {
            ipv4Full
        }
        val isIpv6Enabled = extractValue(currentTomlConfig, "enable_ipv6", "true").toBoolean()
        val isEncryptionEnabled = extractValue(currentTomlConfig, "enable_encryption", "true").toBoolean()

        return EasyTierConfigUiState(
                networkName = extractValue(currentTomlConfig, "network_name", ""),
                networkSecret = extractValue(currentTomlConfig, "network_secret", ""),
                ipv4 = ipv4,
                listeners = extractListAsString(currentTomlConfig, "listeners"),
                peers = extractListAsString(currentTomlConfig, "uri"),
                useSmoltcp = extractValue(currentTomlConfig, "use_smoltcp", "false").toBoolean(),
                latencyFirst = extractValue(currentTomlConfig, "latency_first", "false").toBoolean(),
                disableP2p = extractValue(currentTomlConfig, "disable_p2p", "false").toBoolean(),
                privateMode = extractValue(currentTomlConfig, "private_mode", "false").toBoolean(),
                disableIpv6 = !isIpv6Enabled,
                enableKcpProxy = extractValue(currentTomlConfig, "enable_kcp_proxy", "false").toBoolean(),
                disableKcpInput = extractValue(currentTomlConfig, "disable_kcp_input", "false").toBoolean(),
                enableQuicProxy = extractValue(currentTomlConfig, "enable_quic_proxy", "false").toBoolean(),
                disableQuicInput = extractValue(currentTomlConfig, "disable_quic_input", "false").toBoolean(),
                proxyForwardBySystem = extractValue(currentTomlConfig, "proxy_forward_by_system", "false").toBoolean(),
                disableEncryption = !isEncryptionEnabled,
                disableUdpHolePunching = extractValue(currentTomlConfig, "disable_udp_hole_punching", "false").toBoolean(),
                disableSymHolePunching = extractValue(currentTomlConfig, "disable_sym_hole_punching", "false").toBoolean()
        )
    }

    private fun saveConfiguration(config: EasyTierConfigUiState, showToast: Boolean) {
        // 保存配置
        activity.getSharedPreferences(EASYTIER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOML_CONFIG, buildTomlFromConfig(config))
                .apply()

        // 重新初始化
        initEasyTierManager()

        if (showToast) {
            Toast.makeText(activity, "配置已保存，服务已根据新配置重新初始化。", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildTomlFromConfig(config: EasyTierConfigUiState): String {
        val sb = StringBuilder()
        sb.append("hostname = \"moonlight-V+\"\n")
        sb.append("instance_name = \"Default\"\n")
        sb.append("dhcp = false\n")
        sb.append("ipv4 = \"").append(config.ipv4).append("/24\"\n")

        // 构建listeners
        if (!TextUtils.isEmpty(config.listeners)) {
            val items = config.listeners.split("\n")
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

        if (!TextUtils.isEmpty(config.networkName)) {
            sb.append("network_name = \"").append(config.networkName).append("\"\n")
        }
        if (!TextUtils.isEmpty(config.networkSecret)) {
            sb.append("network_secret = \"").append(config.networkSecret).append("\"\n")
        }

        // 构建peers
        val peerItems = config.peers.split("\n")
        for (peer in peerItems) {
            if (peer.trim().isNotEmpty()) {
                sb.append("\n[[peer]]\n")
                sb.append("uri = \"").append(peer.trim()).append("\"\n")
            }
        }

        // 构建[flags]部分
        sb.append("\n[flags]\n")
        appendFlagIfNotDefault(sb, "use_smoltcp", config.useSmoltcp, false)
        appendFlagIfNotDefault(sb, "latency_first", config.latencyFirst, false)
        appendFlagIfNotDefault(sb, "disable_p2p", config.disableP2p, false)
        appendFlagIfNotDefault(sb, "private_mode", config.privateMode, false)
        appendFlagIfNotDefault(sb, "enable_ipv6", !config.disableIpv6, true)
        appendFlagIfNotDefault(sb, "enable_kcp_proxy", config.enableKcpProxy, false)
        appendFlagIfNotDefault(sb, "disable_kcp_input", config.disableKcpInput, false)
        appendFlagIfNotDefault(sb, "enable_quic_proxy", config.enableQuicProxy, false)
        appendFlagIfNotDefault(sb, "disable_quic_input", config.disableQuicInput, false)
        appendFlagIfNotDefault(sb, "proxy_forward_by_system", config.proxyForwardBySystem, false)
        appendFlagIfNotDefault(sb, "enable_encryption", !config.disableEncryption, true)
        appendFlagIfNotDefault(sb, "disable_udp_hole_punching", config.disableUdpHolePunching, false)
        appendFlagIfNotDefault(sb, "disable_sym_hole_punching", config.disableSymHolePunching, false)

        return sb.toString()
    }

    // ==================== 状态管理 ====================

    private fun parseNetworkInfoForDialog(jsonString: String, instanceName: String): EasyTierDisplayInfo {
        val displayInfo = EasyTierDisplayInfo()
        try {
            val root = JSONObject(jsonString)
            val instance = resolveInstanceInfo(root, instanceName)
                    ?: throw IllegalStateException("No EasyTier network info instance found")

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

    private fun resolveInstanceInfo(root: JSONObject, preferredName: String): JSONObject? {
        val instances = root.optJSONObject("map") ?: return null
        instances.optJSONObject(preferredName)?.let { return it }

        val keys = instances.keys()
        while (keys.hasNext()) {
            val fallbackName = keys.next()
            val fallback = instances.optJSONObject(fallbackName)
            if (fallback != null) {
                LimeLog.warning("EasyTier instance '$preferredName' not found; using '$fallbackName'")
                return fallback
            }
        }

        return null
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
