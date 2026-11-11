# Moonlight V+ · Steam 大屏风格 UI

针对 AYN Thor 双屏掌机的界面改版方案。主屏 6" 1920×1080 横屏，副屏 3.92"
1080×1240（系统密度 369，约 2.30625×），两块屏都以横屏使用。

- `index.html`：双屏可交互原型，可直接在浏览器打开。
- `screenshots/`：原型关键状态截图，以及原生实现的真机截图。
- `STATUS.md`：已完成 / 未验证 / 未完成项的交接说明。
- 原型预览建议窗口：主屏 `1920×1080`，副屏 `1240×1080`。

## 视觉语言

| Token | 值 | 用途 |
| --- | --- | --- |
| 底色 | `#070C12` / `#0C121A` | 页面与卡片表面，深海军蓝而不是纯黑 |
| 卡片描边 | `#1FFFFFFF` | 1dp 细描边，保持层次 |
| 主强调色 | `#2F9BFF → #1A6FBF` | 品牌标记、选中 Tab、主按钮 |
| 正文 | `#F7F3F7` | 大数值与标题 |
| 次级文字 | `#A9C2D2` / `#8FA6B6` | 标签、说明 |
| 状态色 | `#6FD49A` / `#F2C14E` / `#F27166` | 正常 / 警告 / 异常 |
| 延迟链四色 | `#6FA8FF` `#B48CFF` `#4FD1C5` `#F5A3C7` | 网络 / 主机 / 解码 / 渲染 |

版式遵循 Steam 大屏模式：左侧窄工具栏、顶部品牌区、大尺寸封面卡片、底部常驻操作条。
主屏内容使用 `sp` 而非固定像素：主机名 22sp，应用名 20sp，大数值 44–46sp。

## 主屏

1. 左侧深色纵栏 + 现有工具栏按钮（设置、信息、EasyTier、更多）。
2. 品牌区：`V` 标记 + `Moonlight V+` + `选择主机 · 按 A 连接`。
3. 主机以 220dp 高的全出血封面卡片呈现，底部渐变遮罩压白字；列宽按 300dp
   最小宽度计算，1080p 横屏下每行 2 张，突出封面。
4. 应用列表使用 220×300dp 卡片，封面铺满、标题压在底部渐变上。

## 副屏（伴生面板）

副屏不支持滚动，所有内容固定高度，四个 Tab 在同一屏内切换：

- **监控**：实时帧率、帧时间、1% Low、解码耗时、码率 / RTT / 丢包 / 电量，
  底部为网络 / 主机 / 解码 / 渲染四段延迟链。
- **码率**：当前码率大数字、0.5–200 Mbps 滑块（松手立即应用）、动态码率开关。
- **输入**：陀螺仪、音频振动、手柄触控板三个开关。
- **快捷**：Esc / Win / Alt+Tab、键盘 / 性能 / HDR、麦克风 / 虚拟手柄 / 断开。
- 顶栏右侧「菜单」打开现有游戏菜单；`断开` 在快捷页。为了让 4.7" 副屏上的
  字号和触控目标整体放大约 1.3 倍，原来的底部常驻操作条被移除，功能并入顶栏和快捷页。

### 功能映射（只重排现有功能）

| 界面元素 | 现有实现 |
| --- | --- |
| 帧率 / 延迟 / 丢包 / 电量 | `PerfHudSnapshot`（`PerformanceOverlayManager`） |
| 延迟链 | `LatencyChain.segments()` |
| 码率滑块 | `BitrateCardController` 的进度映射 + `NvConnection.setBitrate()` |
| 动态码率 | `PreferenceConfiguration.enableAdaptiveBitrate`、`startAdaptiveBitrateIfEnabled()` / `stopAdaptiveBitrate()` |
| 陀螺仪 | `GyroCardController` |
| 音频振动 | `AudioHapticsCardController` |
| 手柄触控板 | `ScreenDs5Touchpad.toggle()` |
| 快捷操作 | `StreamActionExecutor` |
| 主屏菜单 | `Game.showGameMenu(null)` |
| 断开串流 | `Game.disconnect()` |

没有新增收藏、最近游玩、搜索、简介、游玩时长等原型里不存在的数据。

## 原生实现

- 主屏主机卡片：`app/src/main/res/layout/pc_grid_item.xml`、`PcGridAdapter.kt`
- 主屏应用卡片：`app/src/main/res/layout/app_grid_item.xml`、
  `values-h400dp-land/appview.xml`
- 顶部品牌区与左侧栏：`layout-land/activity_pc_view.xml`
- 副屏面板：`app/src/main/res/layout/companion_screen.xml`、
  `SecondScreenCompanion.kt`

副屏面板只在“第二块屏比主屏小”时出现（掌机内屏）；连接电视/显示器时仍使用原有的
“串流到外接屏”行为。出现条件是串流渲染在主屏（未开启 *Send stream to external
display*）。

副屏元素支持触摸和手柄焦点：Tab、菜单、开关、滑块、快捷按键都可获得焦点，进入面板时
焦点先落在「监控」Tab，十字键左右切 Tab、上下进入当前页的控件。调试包内置
`CompanionPreviewActivity`，可在不串流时把面板投到副屏预览：

```bash
adb shell am start --display 4 -f 0x10000000 -n com.limelight.vplus_debug/com.limelight.CompanionPreviewActivity
```
