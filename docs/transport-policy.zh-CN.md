# 实验传输策略接入

本分支接入 Sunshine 的逐包反馈、配对 HTTPS 会话策略及可靠状态通知，公共库依赖见 [PR #31](https://github.com/qiin2333/moonlight-common-c/pull/31)。主机总预算包含源视频、FEC、音频、控制和反馈成本；客户端滑块在新控制模式下表示总网络上限。

## 权限与状态

2026 年 10 月 4 日主机移除历史突发回放的自动 FEC。客户端仅在状态中的 `experimentalAutomaticFecAvailable` 是 JSON boolean true 时允许开启，否则禁用开关并拒绝启用请求；缺失、false 或错误类型均不可用。自动码率和手动预算仍可操作，旧状态里的 FEC 意图不会阻断这些操作。手动 FEC 与现有 RS 编解码保留。

观测、反馈和控制分别配置；反馈或状态通知不授予控制权。只有原连接明确协商控制能力、配对查询新鲜且主机允许实时控制时才开放策略写入。只读观测维持旧码率交互。策略请求、主机接受、SDK 应用、首包提交分别展示；首发回执不证明送达、解码或体验改善。

按 sessionId、connectionEpoch、controlEpoch、revision 对账。拒绝旧连接、倒退和不完整回复；64 位身份按十进制字符串传递。通知用于提前唤醒原连接的查询，策略值与权限继续由配对 HTTPS 确认，保留周期查询补偿。过期、失败和缺失反馈不展示为零丢包。手动接管明确撤销自动控制；连接停止取消旧任务及迟到回调，重连采用已确认的预算。

重连预算只由 `confirmed` 的编码器应用证据更新；已接受但待应用、应用失败或结果未知的请求不能替代它。编码器重建期间暂缺确认状态时保留最后已应用预算；从未获得应用证据时沿用原连接配置，不从接受值推造重连目标。

## 当前验收边界

本轮能力收敛通过完整的 886 项 JVM 测试（其中 38 项策略测试）、普通 Debug、隔离 Debug 与对应 instrumentation APK 构建；新增控件测试覆盖 FEC 禁用而自动码率仍可操作。设备测试尚未执行，保留既有系统安装限制及真机验收边界。下文的自动 FEC 运行结果仅描述旧版本。

后续增量审查修复两个接入问题：两项策略 Switch 使用对应本地化无障碍名称；重复、部分或非法启动 scope 在 XML 解析边界抛出 XmlPullParserException，进入既有启动失败处理，继续严格拒绝异常身份。新增三项解析测试验证旧主机、省略字段、完整无符号身份及非法/重复字段。完整 889 项 JVM 回归（153 个 suite，无失败、错误或跳过）和普通/隔离 Debug 及对应 instrumentation APK 构建通过；控件测试补充无障碍名称断言，只构建未在设备执行。

策略与生命周期单元测试不能替代完整应用、真实控制会话、弱网故障和设备验收。源码 `9201f86`、common c `53116f5`、haptics SDK `b3f97c3` 的组合已通过 8 类共 78 项 JVM 回归，普通 Debug、隔离 Debug 及对应 instrumentation APK 构建通过。隔离包的 application ID 已核对；正常 ADB 安装返回 `INSTALL_FAILED_USER_RESTRICTED`，设备测试未执行。此前工作区的运行结果不直接视作本提交通过。

默认功能开关保持关闭，真机生命周期、共享预算/公平性、参考链与期限、同预算画质/冻结/延迟及资源成本仍需与主机实施文档逐项验收。

## Android 配置与复验

设置中的逐包反馈及逐包控制复选框默认关闭，控制请求同时启用反馈。状态通知和 JNI 快照绑定原 NvConnection；Game/主线程回调通过原连接所有权校验，旧连接不能停止或改写新连接。迁移保留最新 master 的本地化连接上下文、麦克风初始状态、控制器重绑定及触觉参数。

构建沿用当前 master CI 固定的公共 haptics SDK：`AlkaidLab/moonlight-audio-haptics` 的 `b3f97c3bb7500ea7b1985aea568e5c7b40308d3b`。以 `-PaudioHapticsSdkDir=<本地 SDK 目录>` 指定，Java、Android SDK/NDK 版本及完整子模块按项目配置准备。执行 `:app:testNonRootDebugUnitTest` 的策略/生命周期测试、`:app:assembleNonRootDebug` 和 `:app:assembleNonRootDebugAndroidTest`；[验证工作流](../.github/workflows/transport-validation.yml)记录固定依赖与具体命令。

`-PtransportValidationApplicationId=com.limelight.vplus.transportvalidation` 只隔离 Debug 验证包，普通包 ID 保持原设置。编译 instrumentation APK 不证明测试已经在设备执行；设备串流、Game/JNI 重连与回调、CPU/温升和体验仍需单独验证。
