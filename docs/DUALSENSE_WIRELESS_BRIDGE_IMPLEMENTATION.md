# DualSense 无线桥接实施方案

状态：Implementation in progress（控制 MVP 已闭环，待外置适配器实机验收）

适用项目：Moonlight V+ Android

目标平台：Android API 22+，USB Host 设备
功能名称：DualSense 无线桥接（DualSense Wireless Bridge）

## 1. 结论

本功能不是“使用 Android 内置蓝牙完整控制 DualSense”，而是：

> 由 Moonlight V+ 通过 Android USB Host API 直接控制一个外置 USB 蓝牙适配器，在应用内实现最小化的 Bluetooth HCI、ACL、L2CAP 与 HIDP 主机链路，使实体 DualSense 可以绕过 Android 系统蓝牙栈无线接入。

产品上保留两条互不替代的路径：

1. **Android 系统蓝牙**：零配置，保留系统能够提供的按键、摇杆、基础振动和可用传感器。
2. **DualSense 无线桥接**：需要外置 USB 蓝牙适配器，提供原始 DS5 输入、触摸板、IMU、自适应扳机、灯光和原生 HD Haptics。

首个正式里程碑只实现“一只 DualSense + 一个已验证 USB 蓝牙适配器”的完整控制和触觉闭环。扬声器、耳机孔和麦克风不进入首版。

### 1.1 当前实现快照（2026-08-21）

已落地并通过自动化验证：

- USB HCI descriptor probe、Generic/CSR profile、command/event/ACL transport 与 credit；CSR 使用端点包长读取并在首次 Reset 超时后仅重试一次。若 CSR 拒绝或不完成 Page Scan 配置，会记录该能力缺失并继续主动 Inquiry/Create Connection，只禁用该适配器会话的手柄主动回连。
- Inquiry、名称解析、单 DualSense 选择、ACL 连接、SSP、加密 Link Key；API 23+ 使用 Android Keystore，API 22 仅进程内保存。SSP 仍是主路径，但认证中的匹配手柄若请求 Legacy PIN，会按已验证兼容路径回复 `0000`。初始化会开启 Page Scan，并且只接受已有 Link Key 地址的 controller-initiated ACL 回连；回连请求会优先取消正在执行的 Inquiry。
- L2CAP Basic Mode 重组、HID Control/Interrupt、HIDP Report Protocol；协议握手后主动读取
  Feature Report `0x05`，确保手柄从精简 `0x01` 切换为包含触摸、IMU 和电池的完整 `0x31` 输入。
- 蓝牙 `0x31` 输入长度、CRC、sequence/gap/重复/乱序校验，以及共享的按键、触摸、IMU、电池解析；未充电状态保留真实容量。
- 传统振动、灯条和自适应扳机的 `0x31` 输出，包含 sequence、`0xA2` CRC、单 writer 合并和退出中性包；首个有效输入后会先发送独立的一次性灯条 startup-release 报告，再允许常规 RGB 更新。
- 首个有效 HID 输入后才执行非致命链路调优：禁用 Hold/Sniff/Park、把监督超时设为 12.8 秒；若适配器随后报告进入低功耗模式，再限频请求 Exit Sniff。调优不会参与配对、加密或 L2CAP 成功判定。
- USB/HCI 读线程只负责有序完成 ACL 重组、L2CAP/HIDP 校验与状态解析；完整 DS5 快照交给独立高优先级输入线程。消费端短暂停顿时仅保留最新快照，避免网络发送阻塞 USB 接收，也避免过期按键状态排队后造成粘键或菜单迟滞滚动。
- 默认日志每 5 秒聚合一次实体链路证据：输入 rate、missing/resync、队列覆盖、最大输入 gap、最大分发耗时、各类拒绝、非法 HIDP，以及 output submitted/sent/coalesced/failed；适配器就绪时记录 ACL MTU/slots、Page Scan 能力与 HCI 版本。禁止逐包日志干扰回报率。
- `UsbDriverService` 对适配器权限、拔出、串流结束的生命周期托管；功能默认关闭，只在用户启用后 claim HCI 适配器，并且每次串流最多自动扫描和连接一次；串流开始前已插入的 HCI 适配器也会被枚举。
- Classic HCI 使用 Android 默认有效事件掩码，ACL Create Connection 使用经 Artemis 实测的 `0xCC18` BR/EDR 包型集合，不再限制为 DM1/DH1。
- 简体中文、繁体中文和英文实验性设置入口；Root/NonRoot 全量单测与 Debug APK 构建通过，非 Root Debug 包已覆盖真机并验证入口与开关。

尚未完成，不能作为当前能力宣传：

- 外置 HCI 适配器 + 实体 DualSense 的端到端配对、输入频率和输出实测矩阵。
- 蓝牙原生 HD Haptics PCM carrier；当前无线闭环仅包含传统振动。
- 完整的首次配置页、设备选择、稳定错误码、可复制诊断和手柄功能测试。
- 已配对手柄 controller-initiated reconnect 的实体适配器复测，以及有限自动恢复。

## 2. 背景与现有基础

Moonlight V+ 当前已经具备：

- USB DualSense HID 输入解析，包括按键、摇杆、模拟扳机、触摸板、陀螺仪、加速度计和电池。
- USB DualSense 输出，包括传统振动、自适应扳机和灯光。
- Sunshine 到客户端的原生 DS5 双声道 48 kHz S16LE 触觉 PCM 协议。
- 将 DS5 触觉 PCM 写入实体 USB DualSense 四声道 UAC 端点的 `DualSenseUsbHapticsSink`。
- 控制器编号、到达/离开、触觉路由、输出合并和串流生命周期管理。

关键现有文件：

- `app/src/main/java/com/limelight/binding/input/driver/AbstractPlayStationUsbController.kt`
- `app/src/main/java/com/limelight/binding/input/driver/DualSenseUsbController.kt`
- `app/src/main/java/com/limelight/binding/input/driver/DualSenseUsbOutputReport.kt`
- `app/src/main/java/com/limelight/binding/input/driver/UsbDriverService.kt`
- `app/src/main/java/com/limelight/binding/input/ControllerHandler.kt`
- `app/src/main/java/com/limelight/binding/input/haptics/ControllerHapticsCoordinator.kt`
- `app/src/main/java/com/limelight/binding/input/haptics/DualSenseUsbHapticsSink.kt`
- `app/src/main/jni/moonlight-core/moonlight-common-c/src/Ds5HapticsStream.c`

本方案应复用这些已经验证的 DS5 语义和 Sunshine 协议，只新增蓝牙传输层。不能复制一套平行的控制器路由、编号或触觉协议。

## 3. Android 能力边界

Android 公共 `BluetoothHidDevice` API 用于让 Android 应用将本机注册成 HID 外设，并不是让普通应用以 HID Host 身份接管已经由系统配对的 DualSense。Android 系统连接的游戏手柄主要以 `InputDevice` 暴露；API 31 起可以查询与输入设备关联的传感器，但具体能力仍取决于系统和设备厂商。

因此：

- Android 系统蓝牙路径可以继续作为基础手柄路径。
- 不使用 root、隐藏 API、反射或修改系统蓝牙服务。
- 完整 DS5 原始输入和输出必须使用应用独占的外置 USB 蓝牙控制器。
- 外置适配器由 Android 识别成普通 USB HCI 设备，但不能同时再交给 Android 系统蓝牙栈使用。

参考：

- [Android BluetoothHidDevice](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice.html)
- [Android InputDevice](https://developer.android.com/reference/android/view/InputDevice.html)
- [Android USB Host](https://developer.android.com/develop/connectivity/usb/host)

## 4. 目标

### 4.1 功能目标

首版必须支持：

- USB 蓝牙适配器发现、权限申请、打开和释放。
- DualSense 扫描、配对、Link Key 保存和自动重连。
- 加密 ACL 链路。
- HID Control PSM `0x0011` 与 HID Interrupt PSM `0x0013`。
- 完整蓝牙输入报告：按键、摇杆、模拟扳机、两个触点、IMU、电池和耳机插入状态。
- 传统振动、自适应扳机、灯条、玩家灯和麦克风灯状态。
- Sunshine 原生 DS5 PCM 到实体蓝牙 DualSense HD Haptics 的转换。
- 串流开始、结束、网络中断、USB 拔出和 Activity 重建时的正确生命周期。
- 可操作的连接状态、错误提示、功能测试和诊断信息。

### 4.2 工程目标

- USB 与蓝牙共用同一套 DS5 输入模型。
- 所有 DS5 输出通过一个原子状态快照合并，避免不同输出报告相互覆盖。
- 所有队列有界，实时数据积压时丢弃旧状态，不允许无限增长。
- 输入优先级高于触觉；触觉优先级高于普通灯光更新和诊断日志。
- HCI 适配器差异封装为 profile，不把芯片判断散落在协议代码里。
- 首版不修改 Sunshine/common-c 的 DS5 PCM wire format。
- 功能可以由 feature flag 完整关闭并安全回退到现有路径。

## 5. 非目标

首版不实现：

- 使用手机或电视的内置蓝牙实现完整 DS5 输出。
- 多个 USB 蓝牙适配器。
- 同一适配器连接多个 DualSense。
- DualSense Edge 的背键与 Fn 键专用能力。
- DS5 内置扬声器、耳机孔音频或麦克风转发。
- 蓝牙 LE、BLE HID 或蓝牙音频通用栈。
- 自动下载 Realtek 等芯片的厂商固件。
- 后台常驻扫描。
- 将蓝牙传输转换逻辑放入 `moonlight-audio-haptics` 分析 SDK。

## 6. 支持矩阵

| 能力 | Android 系统蓝牙 | USB 直连 | 无线桥接首版 |
| --- | --- | --- | --- |
| 按键/摇杆/扳机 | 是 | 是 | 是 |
| 触摸板 | 依系统 | 是 | 是 |
| 陀螺仪/加速度计 | API/ROM 相关 | 是 | 是 |
| 电池 | 依系统 | 是 | 是 |
| 传统振动 | 依系统 | 是 | 是 |
| 自适应扳机 | 否 | 是 | 是 |
| 灯条/玩家灯 | 依系统 | 是 | 是 |
| 原生 HD Haptics | 否 | 是 | 是 |
| 扬声器/耳机 | 否 | 暂未使用 | 后续 |
| 麦克风 | 否 | 暂未使用 | 后续 |

## 7. 总体架构

```text
                              ControllerHandler
                                      ^
                                      | ControllerDriverListener
                                      |
                              DualSenseProtocolCore
                              /                    \
                  USB HID report 0x01       BT HID report 0x31
                           ^                        ^
                           |                        |
                   UsbDualSenseLink        HciDualSenseLink
                           |                        |
                    UsbDeviceConnection      Acl/L2cap/Hidp
                           |                        |
                           +------ 实体 DualSense --+


 Sunshine DS5 PCM
        |
 ControllerHapticsCoordinator
        |
        +-- UsbDs5HapticsSink --> UAC 48 kHz 4ch --> USB DualSense
        |
        +-- BtDs5HapticsSink  --> FIR 48k->3k --> BT haptics report
```

### 7.1 模块目录

建议新增：

```text
app/src/main/java/com/limelight/binding/input/dualsense/
  DualSenseInputState.kt
  DualSenseInputParser.kt
  DualSenseOutputState.kt
  DualSenseOutputEncoder.kt
  DualSenseNativeHapticsSink.kt

app/src/main/java/com/limelight/binding/input/dualsense/bt/
  BluetoothHciAdapter.kt
  HciUsbAdapterFactory.kt
  GenericHciUsbAdapter.kt
  CsrHciUsbAdapter.kt
  HciCommandDispatcher.kt
  AclTransport.kt
  L2capChannelManager.kt
  HidpSession.kt
  DualSenseWirelessLink.kt
  DualSenseBluetoothInputCodec.kt
  DualSenseBluetoothOutputCodec.kt
  BtDs5HapticsSink.kt
  BluetoothHapticsResampler.kt
  DualSenseLinkKeyStore.kt
  WirelessBridgeDiagnostics.kt
  WirelessBridgeState.kt
```

UI 建议新增：

```text
app/src/main/java/com/limelight/preferences/dualsense/
  DualSenseWirelessBridgeFragment.kt
  DualSenseWirelessBridgeViewModel.kt
  DualSenseWirelessBridgeUiState.kt
```

不要建立独立于 `ControllerHandler` 的输入业务层。无线连接成功后，应创建一个现有 `AbstractController` 兼容对象并走标准控制器生命周期。

## 8. 核心重构

### 8.1 通用控制器监听接口

当前 `UsbDriverListener` 同时包含通用控制器事件和 USB UAC 特有回调。建议拆分：

```kotlin
interface ControllerDriverListener {
    fun reportControllerState(...)
    fun reportControllerMotion(...)
    fun reportControllerBattery(...)
    fun reportControllerTouch(...)
    fun deviceAdded(controller: AbstractController)
    fun deviceRemoved(controller: AbstractController)
    fun isControllerReady(controllerId: Int): Boolean
}

interface UsbDriverListener : ControllerDriverListener {
    fun onDs5AudioInterfaceAvailable(...)
    fun onDs5AudioInterfaceGone(controllerId: Int)
}
```

`ControllerHandler` 继续实现 `UsbDriverListener`。无线桥接只依赖 `ControllerDriverListener`，避免蓝牙类出现 USB 音频参数。

### 8.2 通用 DS5 输入模型

从 `DualSenseController.handleRead()` 抽取：

```kotlin
data class DualSenseInputState(
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val buttons: Int,
    val dpad: Int,
    val gyro: Vector3,
    val acceleration: Vector3,
    val touches: List<DualSenseTouch>,
    val battery: DualSenseBattery,
    val headphonesConnected: Boolean,
    val microphoneConnected: Boolean,
    val sequence: Int?
)
```

解析分两层：

1. 传输层校验报告 ID、长度、CRC 和外层前缀。
2. 通用解析器按公共 DS5 payload 偏移读取字段。

USB 和蓝牙 golden test 必须对同一组逻辑状态输出一致结果。

### 8.3 原子输出状态

蓝牙 DS5 的灯光、扳机、振动和音频控制位共享输出报告。不能继续让每项功能独立生成整包，否则后写入者会覆盖前一项。

建议：

```kotlin
data class DualSenseOutputState(
    val lowFrequencyRumble: Int = 0,
    val highFrequencyRumble: Int = 0,
    val leftTriggerEffect: ByteArray = ByteArray(11),
    val rightTriggerEffect: ByteArray = ByteArray(11),
    val lightbar: Rgb = Rgb.DEFAULT,
    val playerLeds: Int = 0,
    val microphoneLed: Boolean = false,
    val audioFlags: Int = 0,
    val generation: Long = 0
)
```

规则：

- 所有调用只更新快照字段。
- 单一 output writer 读取快照并编码。
- 输出 signal 使用容量 1 的合并队列。
- writer 发送时增加蓝牙 sequence nibble 并计算 CRC。
- 扳机释放、LED release 等一次性 valid bit 发送后必须消费掉，不能被后续报告重复携带。
- 断开前发送 neutral snapshot；失败时不能阻塞 teardown。

USB 首次重构保持输出字节不变，待蓝牙链路稳定后再评估是否让 USB 也完全共用快照编码器。

## 9. USB HCI 适配器层

### 9.1 设备识别

优先根据 HCI USB 接口布局识别，而不是只维护 VID/PID 白名单：

- Wireless Controller class `0xE0`。
- subclass `0x01`。
- protocol `0x01`。
- Interrupt IN event endpoint。
- Bulk IN ACL endpoint。
- Bulk OUT ACL endpoint。

VID/PID 用于选择已知 profile 和呈现兼容性信息，不能作为唯一识别条件。

### 9.2 所有权

扩展现有 `UsbDriverService`，由它继续统一处理：

- USB attach/detach 广播。
- USB permission。
- `UsbDeviceConnection` 打开与关闭。
- 普通有线控制器和 HCI 适配器的排他分类。

不能新建第二个同时监听全部 USB 设备的 Service，否则可能出现重复权限弹窗和同一设备被两个组件同时 claim。

桥接只有在以下条件之一成立时才 claim HCI 适配器：

- 用户正在无线桥接设置页主动配置。
- 用户已启用“串流时自动连接”，且串流正在启动。
- 已建立的桥接连接正在恢复。

应用普通启动时不得主动弹出 USB 权限。

### 9.3 Adapter profile

```kotlin
interface HciTransport : Closeable {
    val state: HciTransportState
    val profile: HciUsbAdapterProfile
    fun setListener(listener: HciPacketListener?)
    fun open(): Boolean
    fun configureAclOutput(maxPayloadLength: Int, packetCredits: Int): Boolean
    fun sendCommand(packet: HciCommandPacket): Boolean
    fun sendAcl(packet: HciAclPacket): Boolean
}
```

`sendCommand()` 只表示 USB control transfer 是否成功提交。HCI Command Complete/Command Status
仍通过 Event endpoint 异步返回，由协议状态机按 opcode 关联，transport 不伪装成同步 RPC。

首版 profile：

1. `GenericHciUsbAdapter`：完全依据 HCI 返回的 ACL MTU、buffer count 和 endpoint 能力工作。
2. `CsrHciUsbAdapter`：只封装已验证 CSR 适配器的 fragment、初始化或流控差异。

Realtek/Actions profile 必须在有实机、固件和抓包验证后添加，不做推测式兼容。

### 9.4 I/O 与初始化

- Event Interrupt IN 维护一个 `UsbRequest`，ACL Bulk IN 维护四个在途 `UsbRequest`；全部由同一个
  completion loop 串行回收和立即补位，兼顾 detach/close 确定性与持续 HID/触觉流量。
- 单功能适配器的 HCI command 使用 device recipient；USB Composite 适配器优先使用 interface
  recipient 和实际 HCI interface index，并仅在传输失败时回退到规范允许的 device recipient。
- Command control transfer 与 ACL Bulk OUT 共用串行输出锁；close 后不得再提交输出。
- ACL decoder 支持 USB transfer 的拆包与粘包。`Read Buffer Size` 只约束 host-to-controller
  输出，不能用于限制反方向输入；controller-to-host 输入在显式启用 Host Buffer Size 后再按该值收紧。
- 初始化严格按 `Reset -> Read Local Version -> Read Buffer Size -> Read BD_ADDR` 单命令推进；
  bootstrap、扫描与配对共用同一个 session command executor，每个命令具有独立超时和稳定失败码。
- `Read Local Version` 只用于诊断与 profile 选择：控制器明确返回不支持或返回字段不完整时继续初始化，
  不把可选诊断能力误当作建链硬门槛。
- `UsbRequest`、interface 和 `UsbDeviceConnection` 由 transport 一次性持有并按此顺序关闭，
  Service 不与 transport 并发操作同一 connection。

## 10. 蓝牙协议状态机

### 10.1 UI 状态

```text
DISABLED
DETACHED
PERMISSION_REQUIRED
OPENING_ADAPTER
ADAPTER_READY
SCANNING
PAIRING
ENCRYPTING
OPENING_HID_CONTROL
OPENING_HID_INTERRUPT
CONNECTED
RECOVERING_HID
RESETTING_ADAPTER
FAILED
```

状态对象必须包含：

- 用户可见标题和说明。
- 是否允许扫描、连接、断开或重试。
- 适配器标识与 profile。
- DS5 地址、名称、电池和最后输入时间。
- 稳定错误码，不仅是异常字符串。

### 10.2 建链流程

```text
USB permission
  -> HCI Reset / Read Buffer Size / Read BD_ADDR
  -> 配置 controller-to-host ACL flow control
  -> Inquiry / Remote Name
  -> Create Connection
  -> Link Key 或配对事件
  -> Enable Encryption
  -> L2CAP HID Control PSM 0x0011
  -> L2CAP HID Interrupt PSM 0x0013
  -> HID Report mode
  -> 等待有效 0x31 输入报告
  -> 发布 Controller 到 ControllerHandler
```

必须收到至少一个通过长度和 CRC 校验的完整 DS5 输入报告后，才进入 `CONNECTED` 并向 Sunshine 发布控制器到达。ACL 已连接但 HID 未工作时不能伪装成成功。

### 10.2.1 发现阶段实现约束

- 使用 GIAC `0x9E8B33` 发起有限时长 Inquiry，并同时维护 controller command timeout 与
  Inquiry Complete 操作级 timeout。
- 默认 Reset 后按 Standard Inquiry Result 格式解析；严格按规范的字段数组布局读取多个响应，
  不能把多个字段数组误当作简单 C struct 连续强转。
- 以 BD_ADDR 去重，最多保留 64 个候选；只有 Major Device Class 为 Peripheral 的前 16 个候选
  进入 Remote Name Request，避免附近手机和音箱拉长配置 UX。
- Remote Name 使用严格 UTF-8 解码，名称失败只跳过该候选，不让一次离线设备导致整轮扫描失败。
- 当前明确识别 `DualSense Wireless Controller` 与 `DualSense Edge Wireless Controller`；
  UI 仍展示未命名/未知控制器供诊断，但不会自动把它们连接成 DS5。
- 扫描期间由 discovery session 独占 HCI command executor；取消时分别使用 Inquiry Cancel 或
  Remote Name Request Cancel，完成或失败后必须释放会话，避免后续连接命令永久显示 busy。

### 10.2.2 ACL 建链阶段实现约束

- 首次连接的 `Create Connection` 复用 Inquiry 得到的 Page Scan Repetition Mode 和 Clock Offset，
  并使用已验证路径的 `0xCC18` BR/EDR DM/DH 1、3、5-slot 包型集合，避免 78 字节输入和输出被
  无谓限制在单时隙吞吐。
- Reset 后启用 Page Scan（不启用 Inquiry Scan）。Generic profile 将配置失败视为初始化失败；CSR profile
  可在控制器拒绝或超时时降级为仅主动发现/连接，并通过 `pageScanEnabled=false` 记录能力，不能继续宣传
  controller-initiated reconnect。收到 `Connection Request` 时只处理 ACL link；
  只有应用私有 Link Key store 中存在该地址的有效密钥才发送 `Accept Connection Request`，并以
  `role=0x01` 保持 DualSense 为 BR/EDR master。未知地址保持未接受，不能借自动回连绕过首次
  发现时的 DualSense 名称筛选。
- 若已配对手柄在 Inquiry 或 Remote Name 阶段主动回连，先完成对应 Cancel，再接受缓存的最新请求；
  不能让两个流程并发占用单 outstanding HCI command gate。
- 主动回连完成加密后立即创建被动 L2CAP HID session，优先接受手柄发起的 Control/Interrupt PSM；
  750 ms 内没有收到对应请求才回退为 host 主动建链。不能沿用首次连接的整段 HID 延迟，否则手柄
  先到的 signaling ACL 会在 session 创建前丢失，也不能与手柄并发创建第二条 Interrupt channel。
- 只接受目标 BD_ADDR 的 `Connection Complete`，并严格校验 11 字节事件、12 位有效 handle、
  ACL link type 和 encryption flag；其他地址的并发事件交还上层，不误绑定到 DS5。
- 建链命令、Page 过程、Cancel 命令和 Cancel 最终事件分别设置超时。Page 超时后先发送
  `Create Connection Cancel` 做控制器侧清理，再向 UI 报告超时，不能直接遗留后台 Page。
- `Create Connection Cancel` 的 Command Complete 成功不代表流程结束；必须继续等待规范要求的
  同地址 `Connection Complete (Status=0x02)`，之后才能释放连接会话。
- ACL 建立后由连接会话持续追踪 handle 和 `Disconnection Complete`。主动断开使用 reason `0x13`，
  只有收到最终断开事件后才清理 link，防止后续 L2CAP 数据误投递给已释放的控制器实例。
- 本阶段的 `CONNECTED` 只表示 HCI ACL 已建立，不等同于产品 UI 的控制器已连接；仍需完成配对、
  加密、L2CAP HID 和至少一个有效 DS5 输入报告后才能向 `ControllerHandler` 发布。

### 10.2.3 SSP、Link Key 与加密阶段实现约束

- Reset 后显式写入 Event Mask 并启用 Simple Pairing Mode；不能依赖适配器固件默认值，否则可能
  收不到 IO Capability、Link Key 和 Simple Pairing Complete 等关键事件。
- ACL 建立后主动发起 Authentication Requested。收到 Link Key Request 时优先从
  `HciLinkKeyStore` 返回已保存密钥；没有密钥时发送 Negative Reply，明确启动 SSP。
- 本产品没有可用于蓝牙数字比较的独立可信输入/显示，因此声明 `NoInputNoOutput`、无 OOB、
  General Bonding 且不要求 MITM，按 Just Works 路径处理 User Confirmation Request。
- Link Key Notification 中的 16 字节密钥按 BD_ADDR 保存。状态机只依赖密钥存储接口；API 23+
  使用 `AndroidKeystoreHciLinkKeyStore` 以 Android Keystore AES-GCM 加密，SharedPreferences 仅
  保存绑定 BD_ADDR 作为 AAD 的密文。API 22 禁止降级为明文持久化，且任何路径都不得写入日志。
- 已保存密钥认证失败时删除该密钥并仅自动重试一次 SSP，防止坏密钥造成无限认证循环。
- Authentication Complete 成功后显式启用连接加密，并以匹配 handle 的 Encryption Change
  `enabled=1` 作为安全阶段唯一成功条件；仅收到 Link Key 或 Pairing Complete 不能提前进入 L2CAP。
- PIN、Passkey 和 Remote OOB 等与当前 NoInputNoOutput 策略不匹配的请求发送 Negative Reply；
  事件、命令和整体认证均有稳定超时，ACL 中途断开立即终止安全会话。

### 10.2.4 Basic L2CAP 与 HID 通道实现约束

- 仅在 ACL 加密确认后启动 L2CAP。按 HCI Packet Boundary Flag 重组 Basic L2CAP PDU，首片必须
  包含完整 4 字节 Basic Header，续片必须属于同一 handle；越界长度、新首片覆盖未完成 PDU、
  broadcast 数据或孤立续片均视为协议错误并清空重组状态。
- Signaling 固定使用 CID `0x0001`，支持同一 C-frame 内多个命令；Identifier 范围为 1–255，
  每个本地请求只有一个有界 pending 项，Connection/Configuration/Disconnection 均独立超时。
- 先建立 HID Control PSM `0x0011`（本地 CID `0x0040`），双向配置完成后再建立 HID Interrupt
  PSM `0x0013`（本地 CID `0x0041`）。只有两个通道都收到本地主动配置成功响应，同时成功响应
  对端配置请求后，才进入 `OPEN`。
- 当前使用 Basic Mode 和本地 MTU 672，解析对端 MTU、Flush Timeout 与 hint option；未知 mandatory
  option 返回 `Unknown options`，低于规范最小值 48 的 MTU 返回建议值，禁止默默接受无效参数。
- Channel payload 只按匹配的本地 CID 投递；输出使用对端动态 CID 并受协商 MTU 限制。主动关闭先
  Interrupt 后 Control，均收到 Disconnection Response 后才允许继续断开 ACL。
- 本阶段仅交付可靠 HIDP 字节通道，`OPEN` 仍不是产品级控制器到达；下一阶段必须完成 HIDP
  handshake/report protocol 和有效 DualSense `0x31` 输入报告校验。

### 10.2.5 HIDP 与 DualSense 蓝牙输入实现约束

- HID Control/Interrupt 双通道打开后，主机先在 Control 通道发送 HIDP
  `SET_PROTOCOL(Report)`（`0x71`），且只把单字节 `HANDSHAKE(success)`（`0x00`）视为成功。
  拒绝、畸形响应和 3 秒超时均终止 HIDP 会话，不能靠收到任意 Interrupt 数据跳过握手。
- Report Protocol 成功后，在 Control 通道发送 `GET_REPORT(FEATURE, 0x05)`（`43 05`）。
  DualSense 默认可能只发送缺少触摸、IMU 和电池的精简 `0x01`；读取该校准报告会切换为完整
  `0x31` 输入。首版不把精简报告当作产品级连接成功，也不虚假声明其缺失能力。
- Report Protocol 就绪后，Interrupt 通道仅接受 `DATA | INPUT`（`0xA1`）头；去掉 HIDP 头后，
  DualSense 完整报告必须精确为 78 字节、Report ID 为 `0x31`，公共输入载荷从字节 2 开始。
- 输入 CRC 使用 IEEE CRC32，计算内容为虚拟前缀 `0xA1` 加报告字节 `0..73`，并与字节
  `74..77` 的 little-endian 值比较。CRC 失败不得更新 sequence 或控制器状态。
- 使用公共载荷内的 8-bit sequence（完整报告字节 8）去重并拒绝新鲜窗口内的倒序包；
  支持 `255 -> 0` 回绕并统计 forward gap。连续 500 ms 没有有效输入后允许重新同步，避免暂停或
  恢复后因序号歧义冻结半圈。
- 只有第一份完整有效报告到达后，`DualSenseWirelessController` 才经
  `ControllerDriverListener.deviceAdded()` 发布，并复用 `DualSenseInputSession` 上报按键、模拟轴、
  IMU、电池和触摸。HIDP/L2CAP 提前失败时不得产生虚假的到达/离开事件。
- 触摸使用报告中的 7-bit tracking counter 作为 pointer ID；同一硬件 slot 的 tracking ID 改变时，
  必须先为旧触点发送 UP，再发送新触点 DOWN。该规则由 USB 与蓝牙共同复用。
- 当前无线控制器只声明已经闭环的输入与输出能力：传统振动、灯条和自适应扳机已经接入；
  player/mic LED 尚无上层回调，蓝牙原生 PCM 在专用 haptics sink 完成前不得提前声明。

### 10.3 恢复顺序

检测到输入超时后按以下顺序恢复：

1. 清空未完成 signaling request。
2. 在现有加密 ACL 上关闭并重建 HID Control/Interrupt channel。
3. 若仍无输入，再断开 ACL 并重连已保存设备。
4. 最后才关闭并重新打开 USB HCI 适配器。

恢复期间：

- 立即向 Sunshine 发送中性输入，避免卡键和菜单自动滚动。
- 停止触觉报告，清空旧 generation。
- 不删除 Link Key。
- 每次恢复有冷却时间，禁止无限快速重试。

默认阈值：

- 连续 `2500 ms` 无有效输入进入恢复。
- HID rebuild 最多 2 次。
- ACL reconnect 最多 2 次。
- 失败后等待用户重试或下一次串流，不后台死循环。

## 11. 并发与队列模型

建议线程：

| 执行单元 | 职责 |
| --- | --- |
| HCI event reader | 读取 Command Complete、连接、认证和 credit 事件 |
| ACL reader | 读取并重组 ACL/L2CAP packet |
| Input dispatcher | 只保留最新 DS5 输入状态并提交 ControllerHandler |
| Output writer | 合并后发送普通 HID output snapshot |
| Haptics writer | 按 93.75 Hz 节拍发送 native haptics |

队列规则：

- Input latest-state：容量 1，覆盖旧状态。
- Output signal：容量 1，合并唤醒。
- L2CAP signaling：固定上限，并以 request ID 超时回收。
- Haptics PCM ring：最多 100 ms。
- Haptics report queue：最多 4 个完整 generation。
- 日志和 telemetry 不得占用 HCI 输出锁。

发送优先级：

```text
输入读取与 credit 返回
    > HID/L2CAP 控制
    > native haptics deadline
    > LED/rumble 状态刷新
    > 诊断日志
```

HCI ACL credit 必须同时覆盖 host-to-controller 与 controller-to-host：

- 发送端根据适配器 `Read Buffer Size` 返回的 MTU 和 slot 数分片、等待 credit。
- 接收端解析完成后批量返回 `Host Number Of Completed Packets`。
- 不能假设所有廉价适配器都允许无限 ACL 包。

controller-to-host 流控不能作为全局默认：Generic profile 只有在实机确认支持后，才按
`Host Buffer Size -> Set Controller To Host Flow Control` 顺序启用，并在每批输入处理完成后回 credit；
CSR/Realtek 已知兼容性未确认时保持关闭。关闭状态下不得发送 `Host Number Of Completed Packets`，
输入 decoder 继续接受 HCI 16-bit 长度范围。该策略不影响已经启用的 host-to-controller credit。

## 12. DS5 蓝牙输入

### 12.1 输入校验

必须校验：

- ACL packet 与 continuation fragment 长度。
- L2CAP payload 长度与 CID。
- HIDP transaction type。
- DS5 report ID。
- 最小/精确报告长度。
- 蓝牙 CRC。
- sequence 的重复、倒退和合理跳变。

任何来自 USB/HCI 的长度和字段都视为不可信输入。无效报告只计数和限频记录，不允许越界读取或触发状态迁移。

### 12.2 输入语义

蓝牙完整报告解析后必须复用现有：

- `ControllerPacket` 按钮位。
- `ControllerHandler.reportControllerState()`。
- `reportControllerMotion()`。
- `reportControllerTouch()`。
- `reportControllerBattery()`。
- 控制器快捷键和本地 Game Menu 捕获逻辑。

触摸 slot ID 必须读取 DS5 tracking counter，不能简单使用固定 slot index 代替真实 tracking ID。断开、恢复和本地捕获开始时发送触点 cancel/up。

### 12.3 能力声明

无线桥接 DS5 发布时至少声明：

- `LI_CTYPE_PS`
- `LI_CCAP_PREFER_DS5`
- `LI_CCAP_ANALOG_TRIGGERS`
- `LI_CCAP_RUMBLE`
- `LI_CCAP_ACCEL`
- `LI_CCAP_GYRO`
- `LI_CCAP_TOUCHPAD`
- `LI_CCAP_RGB_LED`
- `LI_CCAP_BATTERY_STATE`

只有 `BtDs5HapticsSink` 已成功启动后才能声明原生 DS5 PCM sink 能力。Android 系统蓝牙模式不得声明该能力。

如果实体 DS5 在控制器到达之后才完成 haptics wake，应通过受控的离开/重新到达更新能力，不能在 host 端留下与实际 sink 不一致的元数据。

## 13. DS5 蓝牙输出

### 13.1 普通输出

输出编码必须以经过验证的 golden capture 为准，不把实验项目中的魔数无注释复制进生产代码。

需要覆盖：

- 传统振动兼容模式。
- 左右自适应扳机 effect block。
- lightbar RGB。
- player LED mask。
- microphone LED。
- valid flags 与 release flags。
- 蓝牙 sequence nibble。
- output CRC（前缀 `0xA2`）。

所有输出更新进入 `DualSenseOutputState`，由一个 writer 编码。自适应扳机不能直接从 common-c callback 写 HCI endpoint。

### 13.2 原生 HD Haptics

输入是现有 Sunshine 协议提供的：

- 48,000 Hz。
- 2 channel。
- signed 16-bit little-endian。
- 已创作的左右触觉 lane，不是普通游戏声音。

蓝牙转换流程：

```text
48 kHz stereo S16
  -> 带限低通 FIR
  -> 16:1 decimation
  -> 3 kHz stereo
  -> DS5 Bluetooth haptics quantization
  -> 64-byte haptics generation
  -> haptics-only Bluetooth carrier
```

初始 DSP 参考参数：

- 127-tap Blackman-windowed sinc。
- 截止频率约 1.4 kHz。
- 通带增益归一化为 1。
- 左右 lane 完全独立处理。
- 不做整流、包络提取、动态压缩或低频增强。

这属于原生 DS5 transport conversion，而不是 `moonlight-audio-haptics` SDK 的 PCM-to-rumble 分析。因此代码应留在客户端 `dualsense/bt` 模块。

### 13.3 节拍与断点

- 3 kHz stereo 的 64 字节 generation 对应 32 stereo frames。
- 发送节拍约为 93.75 Hz。
- haptics writer 使用独立单调时钟，不依赖网络包到达瞬间直接发送。
- `STREAM_START` 建立新 generation。
- sequence gap、`DISCONTINUITY`、重连和设备切换必须清空 FIR/ring 状态。
- underrun 发送 silence，不重放最后一个触觉块。
- overrun 丢最旧 generation，不能增加持续延迟。

首版只发送 haptics-only carrier。扬声器 Opus、耳机路由和麦克风 duplex 留待后续单独设计，避免它们改变已验证的 haptics 时序。

## 14. Link Key 与安全

- Link Key 以 DS5 Bluetooth address 为索引。
- API 23+ 使用 Android Keystore 生成的 AES-GCM key 加密持久化。
- API 22 不以明文保存 Link Key；退出后需要重新配对。
- “忘记手柄”同时删除 Link Key、自动连接设置和展示名称。
- 不记录完整 Link Key、PIN、原始密钥事件或可复用认证数据。
- USB、HCI、ACL、L2CAP 与 HID 报告长度都必须在解析前验证。
- 每类错误日志限频，避免异常设备造成日志或内存 DoS。
- 不扫描或连接非用户选择的普通蓝牙设备。

## 15. 生命周期

### 15.1 设置页

- 进入设置页时可以发现适配器，但只有用户点击后才请求 USB 权限。
- 配对流程由 process-level service 持有，不由 Fragment/Activity 持有。
- 屏幕旋转、语言切换和前后台切换不应断开正在配对的 DS5。

### 15.2 串流开始

若启用“串流时自动连接”：

1. 检查适配器是否存在和已有 USB permission。
2. 有已保存 DS5 时自动连接。
3. HID 有效输入到达后才发布 controller arrival。
4. haptics sink ready 后启用相应能力。
5. 如果失败，显示非阻塞通知并继续串流，允许 Android 系统手柄或触屏回退。

### 15.3 串流结束

- 停止接收 Sunshine DS5 PCM。
- 向实体手柄发送 neutral output。
- 清空触觉 ring 与 pending output。
- 若“保持无线桥接连接”关闭，则断开 ACL；否则保持连接但停止触觉和高频输出。
- 释放 ControllerHandler 编号与触点。

### 15.4 USB 拔出

- 立即停止所有 writer。
- 关闭 request/endpoint/connection。
- 发布中性控制器状态并移除控制器。
- UI 进入 `DETACHED`。
- 禁止任何已排队任务继续写入旧 connection。

## 16. UX 方案

### 16.1 命名

统一使用：

- 中文：`DualSense 无线桥接`
- 英文：`DualSense Wireless Bridge`
- 副标题：`使用外置 USB 蓝牙适配器无线连接 DualSense`

禁止使用：

- `完整蓝牙支持`
- `原生蓝牙 DS5`
- `无线模块支持`

这些名称会让用户误以为可以直接使用手机内置蓝牙，或无法理解功能用途。

### 16.2 设置入口

建议位于：

```text
设置 -> 控制器 -> DualSense 无线桥接
```

主页面保持朴素：

```text
DualSense 无线桥接                                  实验性
使用外置 USB 蓝牙适配器无线连接 DualSense。

适配器        未连接
手柄          未配对

[配置无线桥接]
```

不要在主设置页直接放大量芯片、HCI 或 ACL 参数。

### 16.3 首次配置

```text
1. 插入 USB 蓝牙适配器
2. 授予 USB 访问权限
3. 按住 DualSense 的 Create + PS，直到灯条快速闪烁
4. 选择发现的 Wireless Controller
5. 等待：正在配对 -> 正在建立安全连接 -> 正在连接控制器
6. 完成功能测试
```

用户看到的是产品阶段，不是 HCI opcode。诊断页才显示技术细节。

### 16.4 状态与错误文案

建议稳定错误码：

| 错误码 | 用户文案 | 操作 |
| --- | --- | --- |
| `DS5-WB-USB-001` | 未发现 USB 蓝牙适配器 | 检查 OTG/适配器 |
| `DS5-WB-USB-002` | 未授予 USB 访问权限 | 重新授权 |
| `DS5-WB-HCI-001` | 此适配器的 HCI 接口不受支持 | 查看兼容列表 |
| `DS5-WB-HCI-002` | 适配器的 ACL 数据能力不足 | 更换适配器 |
| `DS5-WB-PAIR-001` | 未发现处于配对模式的 DualSense | 重试扫描 |
| `DS5-WB-PAIR-002` | 配对或加密失败 | 忘记并重新配对 |
| `DS5-WB-HID-001` | 已连接蓝牙，但控制器通道未建立 | 重建连接 |
| `DS5-WB-HID-002` | 控制器长时间没有输入 | 自动恢复/重试 |
| `DS5-WB-HAP-001` | 无法启动原生触觉 | 使用传统振动 |

### 16.5 功能测试

测试页应提供：

- 按键、摇杆和扳机实时图。
- 两个触点和 touch ID。
- 陀螺仪/加速度计数值和 report rate。
- 左右自适应扳机短测试，离开页面时强制清除。
- 灯条和 player LED 测试。
- 左/右 HD Haptics 短测试，限制最大持续时间和强度。
- 一键复制脱敏诊断。

测试页面关闭、应用进入后台或连接丢失时必须清除扳机和触觉。

## 17. 诊断与可观测性

本地诊断至少记录：

### 17.1 Adapter

- VID/PID、USB path、profile。
- HCI version。
- ACL MTU 和 packet slots。
- USB bulk/interrupt 错误计数。

### 17.2 Link

- 当前状态和状态持续时间。
- DS5 地址脱敏形式。
- 加密状态。
- HID Control/Interrupt CID 状态。
- 最近输入 age、平均 rate、最大 gap。
- CRC、长度、重复 sequence 和乱序计数。

### 17.3 Output/Haptics

- 普通 output submitted/sent/coalesced/failed。
- haptics generation received/sent/dropped/underrun。
- ring depth 和 high-water mark。
- ACL credit wait 最大时长。
- recovery count 与最近原因。

默认日志不得打印每个输入或触觉包。Verbose 模式使用时间窗口聚合输出。

## 18. 测试方案

### 18.1 单元测试

必须新增：

- USB `0x01` 与蓝牙 `0x31` 输入 golden vectors。
- 按钮、d-pad、摇杆、模拟扳机偏移。
- 两个触点 down/move/up/cancel 与 tracking ID。
- IMU 轴、单位和符号方向。
- 电池状态和异常状态。
- 蓝牙输入/输出 CRC。
- sequence wrap、重复、倒退和 gap。
- L2CAP signaling request/response/config/disconnect。
- ACL fragment/reassembly 与非法长度。
- 输出快照字段合并和一次性 valid bit。
- neutral output。
- FIR impulse、DC gain、左右隔离和高频衰减。
- 48 kHz 到 3 kHz 的帧数和 64-byte generation 边界。
- haptics discontinuity、underrun 和 overrun。
- 有界队列 newest-wins。
- Link Key store API 22/23+ 行为。

测试向量来源必须记录：

- 实体 DS5 抓包。
- Linux `hid-playstation`/SDL 已公开字段定义。
- DS5Dongle 或 Artemis 已验证报告。

### 18.2 模拟集成测试

提供 `FakeHciUsbAdapter`：

- 重放连接、配对、加密和 HID 信令。
- 输入报告以正常、丢包、重复、乱序和超时方式注入。
- 限制 ACL credits，验证 input 不被 haptics 饿死。
- 在每个状态拔出 adapter，验证只发布一次 device removed。
- 模拟 output write 永久阻塞，验证 stop 有截止时间。

### 18.3 实机测试矩阵

首批矩阵：

| 维度 | 最低覆盖 |
| --- | --- |
| Android | API 22/23、31、35 |
| 设备形态 | 手机、平板/电视盒 |
| USB | 直连 OTG、带供电 Hub |
| 适配器 | 已验证 Generic HCI、已验证 CSR profile |
| DS5 | 至少两个不同固件版本 |
| Sunshine | 当前稳定版、Foundation Sunshine 开发版 |
| 网络 | LAN 正常、丢包/抖动模拟 |

开发时 USB 端口可能被适配器占用，应使用无线 ADB。Android 官方 USB Host 文档也提示 USB 外设调试时可能需要改用网络 ADB。

### 18.4 性能验收

- 正常状态输入观察值稳定在约 200–250 Hz；以报告 gap 为主要判定，不伪造高频重复包。
- 开启 HD Haptics 后，输入 P99 gap 不超过关闭触觉时基线 `+4 ms`。
- haptics 输出长期平均约 93.75 generation/s。
- 正常 LAN 下 10 分钟 haptics drop rate 小于 0.1%。
- 连续运行 1 小时队列和内存不增长。
- 无按键时不得出现自动滚动、菜单移动或非中性摇杆。
- USB 拔出或串流结束后 1 秒内清除振动、扳机和触点。

## 19. PR 拆分

### PR 1：DS5 transport-neutral core

范围：

- 新增 `ControllerDriverListener`。
- 抽取 DS5 输入模型和解析器。
- 新增 `DualSenseNativeHapticsSink`。
- 现有 USB DS5 和 `DualSenseUsbHapticsSink` 迁移到新接口。
- 明确实体 DS5 的 `LI_CCAP_PREFER_DS5`。

约束：

- USB 输出字节和功能不变。
- 不新增 UI。
- 现有 USB DS5 测试全部通过。

### PR 2：USB HCI + 配对 + 输入 MVP

范围：

- HCI adapter abstraction。
- Generic/CSR profile。
- HCI command、ACL、L2CAP、HIDP。
- Link Key store。
- 单 DS5 扫描、配对、自动重连。
- 蓝牙 `0x31` 输入进入 `ControllerHandler`。
- 最小配置和诊断页面。

验收：

- 按键、摇杆、扳机、触摸、IMU、电池连续稳定。
- 无输出和 haptics 时可独立验证。

### PR 3：原子反馈输出

范围：

- `DualSenseOutputState`。
- output writer 与 bounded signal。
- rumble、adaptive trigger、LED、player LED、mic LED。
- teardown neutral report。

验收：

- 灯光、扳机和振动并发更新不互相覆盖。
- 每个测试退出路径都能清除效果。

### PR 4：Bluetooth native HD Haptics

范围：

- `BtDs5HapticsSink`。
- FIR decimator。
- haptics report encoder。
- ACL credit-aware scheduler。
- generation barrier 与 telemetry。
- 原生 PCM 能力与 sink ready 绑定。

验收：

- 左右 lane 正确。
- 节拍和延迟稳定。
- haptics 负载下输入不饥饿。

### PR 5：UX、兼容矩阵与发布门禁

范围：

- 完整首次配置体验。
- 功能测试页。
- 稳定错误码和脱敏诊断。
- 兼容适配器列表。
- 实验性 feature flag、升级和回滚说明。

### 后续独立项目

- 控制器 speaker/headset 音频。
- 蓝牙麦克风上行。
- 多控制器。
- DualSense Edge 专用输入。
- 更多适配器 profile。

这些能力不能阻塞首版无线控制和 HD Haptics。

## 20. Feature flag 与回滚

建议配置：

```text
dualsense_wireless_bridge_enabled=false
dualsense_wireless_bridge_auto_connect=false
dualsense_wireless_bridge_keep_connected=false
dualsense_wireless_bridge_diagnostics=false
```

发布初期：

- 功能默认关闭。
- 标记“实验性”。
- 只有用户主动配置后才启用 USB HCI claim。
- HCI 初始化失败不影响串流启动。
- 关闭 feature flag 后不扫描、不打开、不恢复 HCI 适配器。
- 卸载或清除配置无需驱动卸载；只删除 app 私有 Link Key 和设置。

## 21. 风险与防线

| 风险 | 防线 |
| --- | --- |
| 适配器固件差异 | Generic + 明确 profile；实机验证后才加入列表 |
| HCI 输入被 haptics 堵塞 | ACL credit、输入优先、独立有界队列 |
| 输出字段互相覆盖 | 原子 `DualSenseOutputState` 和单一 writer |
| 卡键/自动滚动 | sequence/CRC 校验、超时中性包、触点 cancel |
| 重连后旧触觉重放 | generation barrier，断点清空 FIR/ring |
| USB 权限骚扰 | 仅用户配置或已启用自动连接时请求 |
| 后台死循环 | 有限重试、冷却、失败后等待用户 |
| Link Key 泄漏 | Keystore 加密；API 22 不持久化 |
| 代码侵入现有 USB DS5 | transport-neutral PR 先行且保持 USB golden |
| 第三方实现不稳定 | 引用协议经验，不把整个 fork 作为运行时依赖 |

## 22. 第三方引用与许可证

### 22.1 已验证实现的比对结论

对 Artemis 已验证分支、Bluetooth Core 与 Linux `hid-playstation` 的差异逐项处理：

- **已采纳**：`0xCC18` ACL packet type、HID 打开前等待、Page Scan、已配对手柄主动回连、
  controller-initiated Control/Interrupt L2CAP、匹配认证会话的 `0000` Legacy PIN fallback，以及
  Linux 驱动使用的独立一次性 `valid_flag2=0x02 / lightbar_setup=0x02` 灯条初始化；同时采纳 Artemis
  的 CSR 容错边界：Page Scan 不可用时保留主动发现/连接，而不是报废整个适配器会话；并按其已验证
  时序只在首个真实输入后禁用低功耗链路策略、设置 12.8 秒监督超时及按 Mode Change 恢复 Active。
- **补强参考路径**：Artemis 原始 HCI 路径能够解析精简 `0x01`，但这不足以兑现本功能声明的触摸、
  IMU 和电池能力；按标准 HIDP `GET_REPORT(FEATURE)` 请求校准报告 `0x05`，明确触发完整 `0x31`。
- **保持当前实现**：Configuration Response 的 Source CID 使用请求方 CID，符合 L2CAP 与 Linux 定义；
  玩家灯只发送低 5 bit，Linux 驱动没有 Artemis 普通输出长期附加 `0x20` 的行为；持久 Link Key 使用
  General Bonding，而不是为一次配对设计的 Dedicated Bonding。
- **等待适配器实测后再启用**：controller-to-host ACL flow control、Realtek 专属连接角色/超时与
  SDP 快速响应。Artemis 已按芯片 profile 区分这些行为，不能把 Generic 路径直接套到 Realtek/CSR。
- **不混入本桥接路径**：Artemis 新增的 Android 12+ 系统蓝牙直连通过 `HiddenApiBypass` 反射非公开
  `BluetoothHidHost.getReport/setReport/sendData`，并兼容不同 ROM 的 raw/hex 方法签名。它能复用系统
  配对和输入设备，但稳定性边界取决于系统实现；外置 HCI 桥接继续使用公开 USB Host API 和自有 HIDP，
  两条后端应共享 DS5 codec/控制器层，而不应让隐藏 API fallback 渗入 HCI 状态机。

这份比对的原则是复用已证明必要的协议顺序，而不是复制参考项目的所有常量或把其整个 fork 作为运行时依赖。

推荐参考但不直接依赖：

- [Bluetooth Core：USB Transport Layer](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core_v6.3/out/en/host-controller-interface/usb-transport-layer.html)
- [Bluetooth Core：HCI Functional Specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core_v6.3/out/en/host-controller-interface/host-controller-interface-functional-specification.html)
- [Linux hid-playstation](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-playstation.c)
- [Artemis Extended HCI bridge](https://github.com/Taveszfito/Artemis-Extended-Native-Wireless-DualSense-Features)
- [Artemis HciUsbController](https://github.com/Taveszfito/Artemis-Extended-Native-Wireless-DualSense-Features/blob/agent/controller-emulation-settings/app/src/main/java/com/example/usbbtonandroid/hci/HciUsbController.kt)
- [Artemis DualSenseBtAudio](https://github.com/Taveszfito/Artemis-Extended-Native-Wireless-DualSense-Features/blob/agent/controller-emulation-settings/app/src/main/java/com/example/usbbtonandroid/DualSenseBtAudio.kt)
- [hbashton/VIIPER DualSense V5](https://github.com/hbashton/VIIPER/blob/main/docs/devices/dualsense.md)
- DS5Dongle 已验证的蓝牙报告与状态机经验。

Moonlight V+ 与 Artemis fork 均使用 GPL-3.0，允许在遵守 GPL、保留版权和修改说明的前提下复用代码。实现时应：

- 在复制或实质性改写的文件头记录来源。
- 在第三方 notices 中记录仓库、commit 和许可证。
- 优先根据 golden test 重新实现小型协议组件，避免无差别复制整个应用模块。
- 不将未经审计的第三方 APK、二进制或驱动加入发行包。

## 23. Definition of Done

首版只有同时满足以下条件才能从实验开发进入公开 Beta：

- 一个 Generic HCI 和一个 CSR profile 通过实机矩阵。
- 一只实体 DS5 可以完成首次配对、重新连接和忘记设备。
- 输入、触摸、IMU、电池、rumble、adaptive trigger、LED 全链路通过。
- 原生 HD Haptics 在至少两个支持游戏中通过左右 lane 和连续场景测试。
- haptics 负载下输入没有明显降频或长时间断流。
- 所有 USB 拔出、网络断开、串流结束和应用后台路径均无卡键、残留扳机或持续触觉。
- 队列、CRC、sequence、fragment 和状态机具备自动化测试。
- 设置页明确说明需要外置 USB 蓝牙适配器，不误导为手机内置蓝牙支持。
- 诊断可以区分 USB、HCI、ACL、HID 和 haptics 故障阶段。
- feature flag 能在不卸载应用的情况下完整关闭功能并回退现有 Android/USB 手柄路径。
