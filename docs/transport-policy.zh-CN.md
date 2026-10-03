# 实验传输策略接入

本分支接入 Sunshine 的逐包反馈、配对 HTTPS 会话策略及可靠状态通知，公共库依赖见 [PR #31](https://github.com/qiin2333/moonlight-common-c/pull/31)。主机总预算包含源视频、FEC、音频、控制和反馈成本；客户端滑块在新控制模式下表示总网络上限。

## 权限与状态

观测、反馈和控制分别配置；反馈或状态通知不授予控制权。只有原连接明确协商控制能力、配对查询新鲜且主机允许实时控制时才开放策略写入。只读观测维持旧码率交互。策略请求、主机接受、SDK 应用、首包提交分别展示；首发回执不证明送达、解码或体验改善。

按 sessionId、connectionEpoch、controlEpoch、revision 对账。拒绝旧连接、倒退和不完整回复；64 位身份按十进制字符串传递。通知用于提前唤醒原连接的查询，策略值与权限继续由配对 HTTPS 确认，保留周期查询补偿。过期、失败和缺失反馈不展示为零丢包。手动接管明确撤销自动控制；连接停止取消旧任务及迟到回调，重连采用已确认的预算。

## 当前验收边界

策略与生命周期单元测试不能替代完整应用、真实控制会话、弱网故障和设备验收。当前发布版本的完整构建正在复验；此前工作区的实验结果不直接视作本提交通过。默认功能开关保持关闭，真机生命周期、共享预算/公平性、参考链与期限、同预算画质/冻结/延迟及资源成本仍需与主机实施文档逐项验收。

## Android 配置与复验

设置中的逐包反馈及逐包控制复选框默认关闭，控制请求同时启用反馈。状态通知和 JNI 快照绑定原 NvConnection；Game/主线程回调通过原连接所有权校验，旧连接不能停止或改写新连接。迁移保留最新 master 的本地化连接上下文、麦克风初始状态、控制器重绑定及触觉参数。

构建需要公共 haptics SDK：`AlkaidLab/moonlight-audio-haptics` 的 `21aae5018e36397d45b3dffff4005c952592ec0b`。以 `-PaudioHapticsSdkDir=<本地 SDK 目录>` 指定，Java、Android SDK/NDK 版本及完整子模块按项目配置准备。执行 `:app:testNonRootDebugUnitTest` 的策略/生命周期测试、`:app:assembleNonRootDebug` 和 `:app:assembleNonRootDebugAndroidTest`；[验证工作流](../.github/workflows/transport-validation.yml)记录固定依赖与具体命令。

`-PtransportValidationApplicationId=com.limelight.vplus.transportvalidation` 只隔离 Debug 验证包，普通包 ID 保持原设置。编译 instrumentation APK 不证明测试已经在设备执行；设备串流、Game/JNI 重连与回调、CPU/温升和体验仍需单独验证。
