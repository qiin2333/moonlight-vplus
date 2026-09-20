# 串流手写笔与本地输入法手写识别隔离

## 问题与证据边界

用户反馈 v12.12.2 手写笔点按、拖动正常，v12.12.3 出现只能点按、拖动显示本地临时笔迹的问题。主机相同，切换触控方式无效。

v12.12.3 的 #576 为 `StreamView` 增加了文本编辑器声明和 `InputConnection`，用于将 Android 输入法提交的文字发送到主机。原生手写笔发送路径没有在此次改动中重写。Android 的自动手写输入可在带输入连接的 View 上启动，向应用发送 `ACTION_CANCEL`，随后把手写笔事件交给输入法墨迹窗口。

版本边界与现象使自动手写接管成为修复目标，但现有反馈不包含问题设备的事件/输入法日志；不能把本地笔迹的具体绘制者、固件行为或某个旧配置值写成已实测确认的根因。完成修复后仍需反馈设备复测。

## 设计选择

保留 `StreamView` 的文字输入连接，但不让串流画面永久成为文本编辑器，明确禁用**串流画面上的自动手写识别**：

1. 默认 `onCheckIsTextEditor() = false`、`onCreateInputConnection() = null`；API 33+ 同时调用 `setAutoHandwritingEnabled(false)`，覆盖 XML、主题或系统默认值。
2. 用户明确点击“打开键盘”时临时启用输入连接并请求焦点；软键盘从可见变为隐藏后调用 `restartInput()` 并撤销输入连接。
3. 串流断开、重连或 Activity 销毁时撤销输入连接。
4. 原有 `commitText`、组合文字、删除、Enter、物理键盘以及原生 PenEvent 保持原来的通道。
5. 远端 UIA/InputPane 上下文继续只提供密码/多行属性和键盘避让提示，不作为允许输入的前置条件。

动态输入连接是必要的生命周期边界：小米等系统可能根据“当前 View 是文本编辑器且有输入连接”决定是否尝试本地手写接管。键盘隐藏回调、串流重置和 Activity 销毁都是撤销点。撤销时调用 `restartInput()`，旧连接的组合文字、删除、Enter 和按键回调不再转发；不主动提交未完成组合文字。远端焦点漏报、旧主机没有该协议、浮动键盘可见性差异不会阻止用户手动输入。

这只关闭在视频画面划笔自动进入 IME 的入口；用户在输入法自身的手写面板中输入，仍可通过同一个 `commitText` 通道发送文字。

## 用户与开发者前后对比

| 场景 | 修改前 | 修改后 |
| --- | --- | --- |
| 用笔在串流画面绘画/拖动 | 启用自动手写的系统可能将画面当作本地书写区 | 串流 View 明确退出自动手写，笔事件仍由现有输入路径处理 |
| 用户手动打开 Android 软键盘 | 可提交中文、候选词等文字 | 临时启用输入连接后提交；键盘隐藏即撤销 |
| 没有 UIA 信息、旧主机、自绘文本框 | 手动键盘可用，避让依赖可用上下文 | 相同 |
| 远程文本框获得焦点 | 最新代码记录属性和避让上下文，不自动弹键盘 | 相同，不重新开放画面手写 |
| 开发者维护 | 输入连接与画面手写的边界不明确 | 边界集中在 StreamView，带构造及输入合同回归测试 |

## 输入合同与版本兼容

串流画面是原始输入区域，不是菜单导航页面。不新增焦点节点或更改初始焦点、方向键、确认/返回、USB 手柄路由。现有手动键盘入口与关闭顺序保持原样；普通设置文本框不受影响。Local Sunshine WebUI 不受影响。

- API 22–32：不访问 API 33 的 View 方法；手动键盘期间按原输入连接工作。
- API 33–34：关闭 View 自动手写；普通串流期间不创建输入连接。
- API 35+：同样关闭 View 自动手写；普通串流期间不创建输入连接。
- 不修改 Sunshine、common-c、协议、用户配置或备份，不添加厂商名单/隐藏 API。

## 验证与验收

仪器测试覆盖四个构造入口和生产 XML；使用启用自动手写的测试样式证明显式关闭能覆盖默认值。输入连接测试验证默认关闭、用户开启后提交组合文字、中文/表情、删除、Enter 和按键，隐藏键盘后再次关闭；远程密码/多行属性本身不激活输入连接。

模拟器集成验证：无远端上下文时手动显示/隐藏实际 IME，完整 stylus DOWN/MOVE/UP 仍到达应用监听器；已有 RemoteImeAvoidanceTest 验证键盘避让、隐藏恢复和手动视口控制。上述回调合同/合成事件测试不等于问题固件上的真实手写接管复现。

构建门禁：单元测试、AndroidTest 编译、Lint 和 nonRootDebug APK。实际执行结果在 PR 描述记录。反馈设备验收需确认：普通画面绘画与桌面拖拽、远端文本框已聚焦时仍能划笔、软键盘开关后再次划笔、中文候选提交、断开重连后的输入。

本次验证：758 项 JVM 单元测试通过；Android 16 / API 36 模拟器上 `StreamViewInputTest` 的 5 项与 `RemoteImeAvoidanceTest` 的 7 项全部通过；覆盖默认关闭、用户打开、键盘隐藏撤销、断开/重置撤销；Lint、AndroidTest 编译、应用与测试 APK 构建通过。API 22–32 完成版本门控与 Lint 检查，未执行低版本设备测试；反馈设备尚待复测。

Debug APK 启用了 R8，测试新增的构造入口与 AndroidX 查询方法仅在 Debug 保留，避免跨 APK 调用被裁剪。既有缩放用例补齐 detached Game 配置，并增加跨越缩放阈值后的 MOVE 样本，使其实际执行缩放后再验证视口保持。

## 参考

- [Android 自定义文本编辑器](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/custom-text-editors)：自动手写退出、输入法接管及 ACTION_CANCEL。
- [Android 文本框手写输入](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)：绘画区域与文本编辑器重叠时关闭自动手写。
- [View.setAutoHandwritingEnabled](https://developer.android.com/reference/android/view/View#setAutoHandwritingEnabled(boolean))（API 33）。

实现使用公开 Android/AndroidX API，无新增依赖，无复制第三方仓库代码或素材。
