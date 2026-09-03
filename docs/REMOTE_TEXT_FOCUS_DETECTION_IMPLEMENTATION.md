# Windows 远端文本焦点识别与 IME 联动实施方案

状态：首版端到端实现已落地；直接触控正向链路已完成双端真机闭环；可信失活、UIA 初始 caret、停靠式 IME Insets 避让和手动平移隔离已实现并通过针对性测试；待停靠式 IME 真机和兼容性矩阵验收

适用项目：

- Foundation Sunshine：本文中的 Sunshine 仓库根目录
- Moonlight V+ Android：本文所在仓库根目录

## 1. 结论

不能用单一信号准确覆盖所有 Windows 应用。正式方案只采用两种主机语义信号：

1. **Windows InputPane 自动出现 + 同一次远端触摸 token**：最强且低误报的触摸路径信号。
2. **UI Automation 可编辑焦点 + 同一次远端输入 token**：补齐鼠标、浏览器和 InputPane 不自动出现的应用。
InputPane 和 UIA 都不可用时保持不自动弹出，用户仍可通过 Android 手动键盘入口输入，不再增加第三种自动识别路径。

明确排除光标形状、I-beam、鼠标图标变化及其与点击的组合判断；这些信号不参与能力协商、归因、协议字段或客户端决策。

协议传递统一的“远端文本上下文事件”，并明确 `source`、`cause` 和 `input_token`；不把某一种 Windows 实现细节固化成协议语义。

```text
authoritative_touch = input_pane_hidden_to_visible
                      && correlated_remote_touch

authoritative_semantic = uia_editable_focus
                         && correlated_remote_pointer
```

## 2. 2026-09-03 本机 Spike 结果

环境：Windows NT `10.0.26200.0`，注册表 `EnableDesktopModeAutoInvoke=1`。Spike 源码：

`tools/input_pane_spike.cpp`（Sunshine 仓库内的本机验证工具，不进入产品构建）

观察器在独立进程中每 10ms 调用 `IFrameworkInputPane::Location()`；空矩形表示不可见，非空矩形表示可见。输入使用 `CreateSyntheticPointerDevice(PT_TOUCH)` / `InjectSyntheticPointerInput()` 注入，等价验证 Sunshine 虚拟触摸路径的关键假设。

| 场景 | 控件焦点 | InputPane | 结论 |
|---|---:|---:|---|
| Win32 可写 `Edit`，合成触摸 | 是 | 自动出现 | 正例通过 |
| Win32 可写 `Edit`，普通鼠标 | 是 | 未出现 | 鼠标路径不能依赖 InputPane |
| Win32 `Static`，合成触摸 | 否 | 未出现 | 非编辑控件未误报 |
| Win32 只读 `Edit`，合成触摸 | 是 | 未出现 | 仅有焦点不会误报 |
| Chrome/GitHub `textarea`，合成触摸 | 是 | 未出现 | InputPane 不是全应用覆盖 |

Win32 正例的观测时序：

```text
t=1512ms  注入远端触摸
t=1626ms  Edit 获得焦点       (+114ms)
t=1946ms  InputPane 可见      (+434ms from touch)
```

显式拉起系统触摸键盘时，`Location()` 还能连续观察到动画期间矩形变化，证明它可作为跨进程可见性观察器使用。

另一个重要结果是：现有 `qiin-tabtip.exe hide` 依赖英文窗口标题 `Microsoft Text Input Application`，在中文系统的 `Windows 输入体验` 上失效；`ITipInvocation::Toggle()` 能切换键盘，但属于私有接口。产品代码不得把本地化窗口标题查找作为隐藏键盘的正式路径。

Spike 只验证了 `Location()` 轮询；尚未验证 `IFrameworkInputPaneHandler::Showing/Hiding` 的跨进程订阅能力。因此首版采用已验证的轮询，事件订阅只能作为后续优化。

### 2.1 自动化模拟验证

不依赖 Windows 桌面或 Android 设备的部分已经形成三层测试闭环：

- Sunshine bridge：14 个用例覆盖 UIA 命中/caret/坐标编码、InputPane 强信号、双 session 路由、跨 session 重叠候选拒绝与焦点转移失活、每 session 独立序号、重复消费抑制、触摸拖动/cancel/cancel-all、活动指针上限、重复 down 幂等、鼠标拖动抑制、已聚焦编辑器缓存命中，以及可信失活与输入框切换的瞬态失焦；
- moonlight-common-c：固定 76 字节 golden vector 覆盖 little-endian、有符号坐标和全部关键字段；传输层拒绝错误版本/长度/header size、非零 reserved 与非法 capture size，但透传未知 flag/source/cause，由客户端策略决定是否信任；
- Android：8 个 JVM 用例覆盖 InputPane/UIA 信任门槛、未知来源拒绝、`u32` revision 去重/乱序/回绕、activation 匹配失活、`caret > element > anchor` 选择、只向上移动的 viewport offset，以及现代/旧式 Insets 屏幕坐标换算。

两份 moonlight-common-c 的 decoder/header/golden test 保持逐字节一致；Android NDK 的 arm64-v8a 与 armeabi-v7a 构建已实际编译该 decoder。

自动化模拟不能替代的边界仍包括：不同 Windows UIA provider、多屏/DPI/裁剪坐标，以及 Android 厂商 IME 的 Insets 动画和 `showSoftInput()` 行为。

### 2.2 双端真机闭环（2026-09-03）

实测组合：Foundation Sunshine 开发构建、Moonlight V+ `nonRootDebug`、一台 Android 测试设备、Windows 记事本、搜狗魅族版悬浮 IME。客户端模式为“直接触控”，并开启“增强触控”。

正向链路逐段确认如下：

1. Android `LiSendTouchEvent()` 的 down/up 均返回 `0`；
2. Sunshine 收到原生触摸，按当前 touch port 映射为捕获坐标 `(1300, 1000)`，捕获尺寸 `3840x2160`；
3. 该点命中 GUI agent 缓存的 UIA 可编辑元素矩形，同一输入 token 被消费一次；
4. `0x550C` 成功定向发送到发起触摸的 session；
5. Android 系统状态为 `mInputShown=true`、`mIsInputViewShown=true`，served connection 是 `StreamView` 创建的 `InputConnection`；
6. 厂商 IME 选择候选词后调用 `commitText()`，2 个 Unicode 字符经现有 UTF-8 输入通道送回主机；UIA 从记事本文档读取到长度由 0 变为 2。

这次联调同时修复了两个不属于协议格式、但会截断链路的实现问题：

- 相对鼠标模式在首次左键前没有消费最新 `touch_port_event`，导致点击候选缺少捕获视口；现在左键归因前会刷新 touch port；
- Android 增强触控原先把 `conn == null` 的结果也视为“不是 unsupported”；现在只有非空且不等于 `LI_ERR_UNSUPPORTED` 才算发送成功。

主机 Windows 触摸键盘和 Android IME 同时显示属于允许状态，不由主机强制二选一。客户端可提供选择/隐藏策略；v1 协议不增加 ACK，也不在 Sunshine 中盲目关闭主机键盘。

本机厂商 IME 使用悬浮窗，未向应用报告可遮挡的 IME Insets，因此本次只能确认输入框本身未被遮挡，不能据此验收纵向 `imeOffsetY`。停靠式 IME 的真实避让仍保留在兼容性矩阵中。

## 3. 主机组件与职责

### 3.1 Sunshine 核心输入线程

每个 `input_t` 会话维护独立输入 token：

```cpp
struct remote_input_token_t {
  std::uint64_t id;
  enum class kind_t { touch, mouse } kind;
  std::chrono::steady_clock::time_point down_at;
  std::chrono::steady_clock::time_point up_at;
  std::optional<POINT> capture_point;
  bool moved_beyond_slop;
};
```

- 触摸 down 时创建 token；up/cancel 后进入 1.2s 待匹配窗口。
- 鼠标左键 down 时创建 token；拖动超过阈值、右键、滚轮或更新点击会取消。
- token 必须属于具体串流 session，多客户端之间不得广播归因。
- 时间使用单调时钟/QPC，不使用系统墙上时间。

### 3.2 用户会话 GUI agent

InputPane 和 UIA observer 都放在 Foundation Sunshine 的用户会话 GUI agent 中，不放在 SYSTEM 服务、视频线程或 Android 客户端中。

GUI agent 已由 `sunshinesvc` 通过活动用户 token 启动，并已有 clipboard bridge/heartbeat/capability 模式可复用。core 侧实现集中在 `src/text_context/`，不要复用剪贴板消息语义。

首版 observer 随 GUI agent 启动，以 100ms 周期轮询；GUI 每 15s 向 Sunshine 核心发送一次能力心跳。核心只向协商了能力的会话发送事件。后续可把“存在订阅会话”反向同步给 GUI，以便空闲时停轮询。

## 4. InputPane 强信号实现

### 4.1 观察状态

GUI agent 以 100ms 周期调用 `IFrameworkInputPane::Location()`，记录当前可见状态并检测上升沿。这样不需要在注入触摸前等待 GUI agent“武装”完成，也不会给输入路径增加延迟。矩形从空变为非空才是 `AUTO_SHOW` 边沿；动画中的非空矩形变化不是新的激活。

### 4.2 因果匹配

Sunshine 在收到 GUI agent 的上升沿后与当前活动或最近完成的 session token 匹配：

- token 创建时间距观察结果到达不超过 1.2s；
- token 未 cancel、未拖动，且是该 session 最新待匹配触摸；
- GUI agent 能力心跳仍有效，且 token 所属串流 session 尚未结束；
- 一次 InputPane 上升沿最多消费一个 token。

匹配成功即产生 `source=INPUT_PANE`（Android 常量为 `SOURCE_INPUT_PANE`）、`cause=REMOTE_TOUCH` 的权威事件。InputPane 本身不提供焦点控件矩形，因此必须至少携带触摸点作为 Android 可见区域锚点；若 UIA 同时解析成功，再附加元素/caret 矩形。

### 4.3 已经可见的边界

系统键盘已经可见时，再点另一个输入框没有新的上升沿，不能仅凭“当前仍可见”再次判定。此时：

- 优先依靠 UIA 焦点变化；
- UIA 不可用时不自动触发，保留 Android 手动 IME 入口；
- 不把持续 `PANE_VISIBLE` 与任意后续点击拼成权威信号，否则会误判键盘未收起时点击普通控件的情况。

## 5. UI Automation 补充信号

GUI agent 在独立 COM MTA 线程创建 `IUIAutomation`，首版与 InputPane 共用 100ms 轮询。线程只读取必要属性并生成不可变快照，不在观察线程发网络请求或遍历 UIA 树。UIA 与 InputPane 分别保留最新待发送状态，异步 HTTPS 失败会重试，旧重试不得覆盖更新状态。

基础门槛：

```text
HasKeyboardFocus == true
IsEnabled == true
IsOffscreen == false
BoundingRectangle 非空且与当前捕获显示器相交
```

首版可编辑性按强到弱判定：

1. `TextEditPattern` 可用；
2. `ControlType == Edit` 且 `ValuePattern.CurrentIsReadOnly == false`；
3. `ValuePattern` 只读、disabled、静态 Document：不可编辑；
4. provider 超时、`NotSupported`、属性冲突、自绘/受权限隔离：`UNKNOWN`，不得伪装为权威结果。

首版发送元素 `BoundingRectangle`；caret 矩形和多行语义保留在 v1 协议中，后续增加 `IUIAutomationTextPattern2::GetCaretRange()`。实现不得读取或发送 `Value`、`Name`、选中文本或邻近文字；密码框仅发送 `PASSWORD` 标志。

UIA 快照与远端 token 的匹配窗口同样为 down 前 50ms 至 up 后 1.2s，并要求点击/触摸点落入元素矩形或 DPI 缩放后的 12px 容差。程序主动聚焦、Tab 导航、本地主机点击可以同步状态，但没有远端 token 时默认不触发 Android IME。

## 6. 协议扩展

### 6.1 为什么需要独立消息

使用独立消息承载文本上下文，不修改或依赖现有显示及输入控制消息。文本焦点检测仅由 InputPane、UIA 和对应的远端输入 token 决定。

新增加密控制流消息：

```text
0x550C REMOTE_TEXT_CONTEXT_UPDATE
```

建议 v1 固定负载，所有多字节整数为 little-endian：

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | `u8` | `1` |
| `header_size` | `u8` | v1 为 `76` |
| `flags` | `u16` | 状态与可选字段有效位 |
| `revision` | `u32` | 连接内单调递增，去重/丢弃乱序 |
| `activation_id` | `u64` | 一次文本焦点激活的稳定 ID |
| `input_token` | `u64` | 未匹配远端输入时为 0 |
| `source` | `u8` | `INPUT_PANE/UIA` |
| `cause` | `u8` | 当前策略定义 `REMOTE_TOUCH/REMOTE_MOUSE`；传输层允许扩展值 |
| `reserved` | `u8[2]` | 必须写 0；v1 接收方对非零值拒绝该消息 |
| `anchor_point` | `i32[2]` | 捕获局部坐标；无效时全 0 |
| `element_rect` | `i32[4]` | 捕获局部坐标；无效时全 0 |
| `caret_rect` | `i32[4]` | 捕获局部坐标；无效时全 0 |
| `capture_size` | `u32[2]` | 生成坐标时的捕获宽高 |

`flags`：

```c
ACTIVE         = 0x0001
EDITABLE       = 0x0002
PASSWORD       = 0x0004
MULTILINE      = 0x0008
ANCHOR_POINT   = 0x0010
ELEMENT_RECT   = 0x0020
CARET_RECT     = 0x0040
INPUT_MATCHED  = 0x0080
PANE_VISIBLE   = 0x0100
AUTO_SHOW      = 0x0200
```

客户端自动弹 IME 的 v1 条件：

```text
A. source == INPUT_PANE
   && INPUT_MATCHED && PANE_VISIBLE && AUTO_SHOW
   && cause == REMOTE_TOUCH

OR

B. source == UIA
   && ACTIVE && EDITABLE && ELEMENT_RECT && INPUT_MATCHED
   && cause in {REMOTE_TOUCH, REMOTE_MOUSE}
```

已建立 activation 后，同一次远端点击若命中 UIA 非编辑元素或使 UIA 焦点失活，Sunshine 发送同一 `activation_id`、递增 `revision`、带 `INPUT_MATCHED` 且缺少 `EDITABLE`/`ACTIVE` 的失活状态。Android 只恢复临时画面偏移，不强制关闭用户选择保留的本地 IME。输入框之间可能出现瞬态失焦；失活消息不消费该点击候选，随后同一点击仍可为最终获得焦点的编辑器建立新 activation。

### 6.2 生命周期与乱序

- 每个可信激活生成一个 `activation_id`，每次发送增加 `revision`；正向激活仍只消费 token 一次，避免 InputPane/UIA 双触发。可信失活沿用旧 `activation_id`，并允许同一点击在瞬态失焦后继续建立下一个编辑器 activation。
- 会话结束时删除该 session 的活动 token、候选 token 和待发送消息。
- Android 使用无符号 32 位回绕规则丢弃重复与旧 revision；Activity 重建会清空接收状态。
- 未协商能力的旧客户端不得收到新消息；common-c 只拒绝结构不安全的包（错误版本/长度、非零 reserved、非法尺寸），未知 source/flag/cause 继续上报，由 Android 信任策略拒绝当前不支持的语义，但不因此断开连接。

能力协商在现有 SDP `x-ml-general.featureFlags` 中增加实施时确认未占用的 `ML_FF_REMOTE_TEXT_CONTEXT`。主机通过文本上下文能力明细声明 InputPane/UIA 的可用状态。

### 6.3 Windows 键盘抑制与客户端 ACK

“识别系统键盘出现”与“隐藏系统键盘”是两个能力，不能混为一谈。本次 Spike 尚未得到可靠的公共跨进程 hide API；私有 `ITipInvocation::Toggle()` 可用但有状态竞态，窗口标题方案又受本地化影响。

因此 MVP 不在检测瞬间隐藏系统键盘。若后续完成 hide Spike，再增加反向消息：

```text
0x550D REMOTE_TEXT_CLIENT_STATE
{ version, state=IME_SHOWN/IME_HIDDEN/SHOW_FAILED, activation_id }
```

主机只能在收到同一 `activation_id` 的 `IME_SHOWN` 后尝试隐藏 Windows 键盘；失败不影响文本输入。未验证前，此能力默认关闭，禁止用发送 Escape、盲目 Toggle 或按窗口标题关闭作为正式实现。

## 7. Android 行为与可见区域

收到权威激活后：

1. 校验 Activity 在前台且连接一致；用户自动 IME 设置开关尚未实现；
2. 使用 `WindowInsetsControllerCompat.show(ime)` 与显式 `showSoftInput()`，自动路径不使用有状态竞态的 toggle；
3. 可见区域锚点优先级为 `caret_rect > element_rect > anchor_point`；UIA TextPattern2 可用时采集初始 caret，否则安全回退；
4. 串流 Activity 使用 `adjustNothing`，优先以 `WindowInsetsCompat.Type.ime()` 的真实 bottom inset 计算临时 `imeOffsetY`，旧系统才使用经过窗口屏幕原点校正的 visible-frame 兜底；
5. video surface 与本地 cursor overlay 使用同一个临时 offset；输入发往主机的坐标保持原捕获坐标，不把视觉偏移写回协议；
6. 同一远端点击造成可信失活、连接切换或用户关闭 IME 时恢复偏移；失活不强制关闭 IME；
7. 用户 pan/zoom 的基础变换与 IME 临时变换分离，IME 显示期间拖动画面不会重复吸收 `imeOffsetY`。

只移动到“caret/输入控件/触摸点附近可见”，不承诺完整输入框全部可见。偏移应做上下限裁剪并保留 16–24dp 安全边距；不得改变主机端坐标或视频分辨率。

## 8. 实施顺序

### Phase A：已完成 Spike

- `Location()` 跨进程可见性与动画矩形；
- 触摸可写、触摸静态、触摸只读、鼠标可写正反例；
- Chrome `textarea` 反例；
- 中文系统窗口标题 hide 失效验证。

### Phase B：Sunshine 主机（已实现）

1. 将 Spike 观察器收敛为 GUI agent `input_pane_observer`；
2. 增加 per-session 输入 token 和 QPC 关联；
3. 实现 UIA observer 与超时/权限安全降级；
4. 增加 `src/text_context/{bridge,http}`、能力位和 `0x550C`；
5. 写单元测试覆盖序列化、多 session token 隔离、重复消费和拖动/cancel 抑制。

### Phase C：Android 客户端（已实现首版）

1. moonlight-common-c 解析能力与 `0x550C`；
2. Java 回调携带 activation/source/cause/坐标；
3. IME 状态机与 `imeOffsetY`；
4. `StreamView` 提供真实 `InputConnection`，提交文本走现有 UTF-8 输入通道，删除/回车走键盘事件；
5. 密码语义会切换 Android 输入类型并禁止建议；手动 IME 入口保持可用，不实现第三种自动识别兜底。

尚待补齐的产品项：用户设置开关、结构化诊断计数、UIA multiline 与 activation 内连续 caret 更新、停靠式 IME 真机验收，以及多屏/DPI 验收。

### Phase D：可选客户端键盘选择

Windows 与 Android 键盘允许并存。若产品需要单键盘体验，优先在客户端提供明确选择/隐藏策略；除非后续出现必须由主机确认的需求，否则不增加 `0x550D` ACK，也不让 Sunshine 使用私有 Toggle、Escape 或本地化窗口标题强制关闭系统键盘。

## 9. 验收矩阵

至少覆盖：

- Notepad、Win32、WinForms、WPF、WinUI 的可写/只读/disabled/密码/多行控件；
- Chrome/Edge 的 `input`、`textarea`、`contenteditable`、普通正文和 PDF；
- 远端触摸、远端鼠标、Tab、程序主动聚焦、本地主机点击；
- InputPane 已可见后切换输入框、客户端手动关闭 IME 后再次点击；
- UIA provider 超时、管理员应用、自绘/游戏、非捕获显示器；
- 两个 Moonlight 客户端并发、GUI agent 重启、Session 切换、断流重连；
- 坐标缩放、DPI、多屏负坐标、视频裁剪和 Android Insets 动画；
- 网络丢包、重复包、乱序包、旧客户端未协商能力。

核心标准：

- 普通文本、PDF、只读框不误弹；
- Win32 标准编辑控件的远端触摸稳定自动触发；
- Chrome 等 InputPane 未出现的应用由 UIA 路径补齐；
- 本地操作或另一 session 的输入不归因给当前 Android；
- 任意 observer/provider/桥接异常均安全退化为不自动弹出；
- Android 始终能用 caret、控件或触摸锚点之一把输入位置移到 IME 可见区。

## 10. 隐私与日志

日志只记录 revision、activation、source、cause、token、延迟、矩形有效位和失败类别；不得记录控件文字、名称、AutomationId、窗口标题、选区、剪贴板或进程命令行。密码输入只传布尔标志，协议中永不传输主机 input 内容。
