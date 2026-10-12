# Android 11 远程文本框自动弹出本地键盘设计

## 目标

让 Android 11 及以上设备在收到可信的远程文本框激活时，自动弹出手机当前输入法。输入法仍由 Android 系统提供，Moonlight 只提供临时 `InputConnection` 并把提交的文字转发给主机。

## 当前问题

现有实现只在 API 33 及以上启用自动弹出。Android 11 和 Android 12 已经具备 `InputMethodManager.showSoftInput()`、自定义 View 的 `InputConnection` 以及 IME Insets，因此这两个版本没有必要被排除。

API 33 引入的 `View.setAutoHandwritingEnabled(false)` 只用于阻止系统把串流画面当作本地手写区域。Android 11/12 没有这个 API，也不应为了调用它而放弃自动弹出。

## 触发合同

自动弹出同时满足以下条件：

1. 系统版本为 API 30 或更高。
2. 用户打开“远程文本框自动弹出本地键盘”。API 29 及以下隐藏该设置。
3. 主机发送可信的 `RemoteTextContext`：
   - UIA 来源要求 `INPUT_MATCHED + ACTIVE + EDITABLE + ELEMENT_RECT`，并且来源是远程触摸或鼠标。
   - InputPane 来源要求 `INPUT_MATCHED + PANE_VISIBLE + AUTO_SHOW`，并且来源是远程触摸；该路径不要求 `EDITABLE`。
5. 同一个 `activationId` 尚未尝试过自动弹出。

API 30–32 与 API 33+ 使用同一套可信激活、activationId、失焦收起和手动键盘所有权规则。普通焦点变化、不可信上下文和标准 Sunshine 未提供的上下文不会触发自动弹出。

## 平台行为

| Android 版本 | 自动弹出 | 自动手写隔离 | 输入连接 |
| --- | --- | --- | --- |
| API 22–29 | 不支持，设置隐藏 | 不调用 API 33 方法 | 仅保留现有手动入口 |
| API 30–32 | 支持 | 没有 API 33 手写接管开关 | 可信激活时临时启用 |
| API 33+ | 支持 | 调用 `setAutoHandwritingEnabled(false)` | 可信激活时临时启用 |

API 30–32 的键盘显示依赖现有 `StreamView` 输入连接和 `InputMethodManager`。键盘隐藏、串流重置、Activity 销毁时撤销临时输入连接。Android 11/12 不新增厂商判断，不读取隐藏 API，也不改变远程手写笔事件路径。

## 所有权与生命周期

- 自动弹出的键盘由当前 `activationId` 所有。
- 用户手动打开或重新打开的键盘不属于自动所有权，不会被远程失焦自动关闭。
- 可信失焦只关闭当前自动拥有的键盘，并清理临时输入连接。
- 自动显示失败只清除本次尝试记录，允许同一激活后续 revision 重试。
- 同一激活成功显示或已确认键盘可见后不重复弹出。
- `resetSession()` 和 `dispose()` 先隐藏自动拥有的键盘，再撤销输入连接。

## 实现范围

- 将 `RemoteImeController` 的自动弹出能力门槛从 API 33 调整为 API 30。
- 将设置项可见性门槛从 API 33 调整为 API 30。
- 保留 `StreamView` 的 API 33 手写隔离调用和版本保护。
- 增加 API 29/30/33 门槛测试，避免以后误把输入法能力与手写隔离能力绑定。

## 验证清单

- [ ] API 30 真机使用 Gboard：远程点击 Windows 文本框后弹出手机键盘。
- [ ] API 30 真机验证中文候选、删除、Enter 和密码输入。
- [ ] API 30 键盘手动关闭后，同一激活不重复弹出；新的文本框激活可以再次弹出。
- [ ] API 30 远程失焦后自动键盘收起，手动重新打开的键盘保持可用。
- [ ] API 33+ 继续关闭串流画面自动手写接管。
- [ ] API 29 及以下不显示该设置，也不触发自动弹出。
- [ ] 横竖屏、浮动键盘、断开重连和不同厂商输入法进行人工验收。
