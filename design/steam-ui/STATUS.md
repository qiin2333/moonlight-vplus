# Moonlight V+ · Steam 风格双屏 UI 交接说明

> 更新时间：2026-11-11
> 分支：`feat/steam-ui`（基于 `4532ae6d`）
> 目的：把这轮做过的设计、代码、验证结果和未完成项交接给下一个 agent。

## 0. 一句话现状

主屏的 Steam 大屏样式和串流时的副屏伴生面板已经写完，能在 AYN Thor 上编译、安装、
预览；**但还没有完成一次真实的串流端到端验证**（debug 包尚未与主机配对），
代码也还没有提交/推送。本文档与代码会一起提交到本地 `feat/steam-ui` 分支。

## 1. 背景与硬约束

- 设备：AYN Thor 双屏掌机。
  - 主屏：display 0，1920×1080，120Hz。
  - 副屏：display 4，横屏 1240×1080，density 369（约 2.30625×），触摸 source 为 `EXTERNAL`。
- 需求：主屏改成 Steam 大屏风格；副屏同时显示串流菜单 + 实时监控。
- 硬约束（用户明确要求）：
  1. **只改 UI，不新增功能，不改业务逻辑**。
  2. 不编造收藏、最近游玩、搜索、简介、游玩时长等原型里不存在的数据。
  3. 副屏**不允许滚动**，用 Tab 在同一屏内切换。
  4. 副屏字号/触控目标整体放大约 **1.3 倍**。
  5. 交互要符合掌机：方向键焦点 + 可触摸目标。

## 2. 仓库与提交状态

- 当前分支：`feat/steam-ui`。
- 所有改动目前都在工作区，尚未 commit。
- `origin` = `https://github.com/qiin2333/moonlight-vplus`。
- 已通过 `gh repo fork qiin2333/moonlight-vplus --clone=false` 在用户账号下创建 fork：
  `https://github.com/0x1428571429/moonlight-vplus`。
- **尚未添加 `fork` remote，也尚未 push**（上一次 `git remote add` 被用户取消）。
  下一个 agent 如要继续推送：
  ```bash
  git remote add fork https://github.com/0x1428571429/moonlight-vplus.git
  git push -u fork feat/steam-ui
  ```
- `framegen/src/main/cpp/lsfg-vk-android` 是**子模块脏状态**（`m`），与本轮无关，
  提交时不要 `git add -A` 把它带进去，建议显式列出路径。
- `design/steam-ui/` 约 13MB，主要是截图；是否全部入库可按需决定。
- `.playwright-mcp/` 已加入 `.gitignore`。

## 3. 已完成的内容

### 3.1 设计原型

目录 `design/steam-ui/`：

- `index.html`：主屏 + 副屏的可交互原型（双屏并排，可直接浏览器打开）。
- `README.md`：视觉 token、主/副屏功能映射、原生实现位置、预览命令。
- `screenshots/`：原型关键状态截图 + 真机截图。
  - `native-host-grid-thor-v2.png`：Thor 主屏真机主机列表。
  - `browse-current.png`：主屏浏览态。
  - `second-*.png`：原型里的副屏监控/码率/输入/快捷和浏览态页面。

原型预览建议窗口：主屏 1920×1080，副屏 1240×1080。

### 3.2 主屏（PcView 主机列表）

- `layout-land/activity_pc_view.xml`
  - 壁纸上方新增 `#99070C12` 压暗层。
  - 左侧新增 `steamRail` 深色纵栏（宽度复用 `pc_toolbar_button_size`），
    现有设置/信息/EasyTier/更多按钮浮在栏上，没有新增按钮。
  - 新增 `steamHeader`：42dp `V` 品牌标记 + `Moonlight V+` 24sp +
    副标题「按 A 连接 · 长按查看更多」。
  - 新增 `hostSectionHeader`：「主机」22sp + 「全部主机」14sp。
  - `pcFragmentContainer` 改为位于 `hostSectionHeader` 下方。
- `layout/pc_grid_item.xml`
  - 主机卡从 104dp 横向布局改为 **220dp 全出血封面卡**。
  - 封面 `grid_image` 铺满，底部 150dp `steam_card_scrim` 渐变。
  - 主机名 22sp 粗体白字，单行省略；新增 `grid_status`（在线绿 `#6FD49A` /
    离线灰 `#8FA6B6`）。
  - 原网络质量行移到底部信息区，颜色改深色主题。
  - 旧图标装饰 `pcIconGlow` / `pcIconBg` 保留 ID 但设为 1dp + `gone`，
    避免 `PcGridAdapter` 找控件时崩溃。
- `grid/PcGridAdapter.kt`
  - 添加主机卡的加号图标改为 78dp 内边距（约 64dp 图标），不再拉伸成整张卡。
  - 主机名统一白色；按 `ONLINE` / `OFFLINE` 设置状态文案与颜色，`UNKNOWN` 隐藏。
  - 复用 View 时重置 `grid_image` 的 padding。
- `layout/pc_grid_view.xml`：内边距/间距改为 18dp，顶部 margin 8dp。
- `PcView.kt`：动态列宽的最小列宽 180dp → **300dp**，间距 15dp → 18dp，
  1080p 横屏约每行 2 列，突出封面。

### 3.3 主屏（AppView 应用列表）

- `layout/app_grid_item.xml`
  - 卡片改为封面铺满的 220×300dp（尺寸来自 `values-h400dp-land/appview.xml`）。
  - 标题压在底部渐变上，20sp 粗体白字，两行省略。
  - 右上角状态图标 40dp。
- `values-h400dp-land/appview.xml`：`150×200dp` → `220×300dp`。
- AppView 的顶部栏、排序/搜索等其它 UI **没有改**。

### 3.4 副屏伴生面板（串流时）

核心文件：

- `app/src/main/java/com/limelight/SecondScreenCompanion.kt`
- `app/src/main/res/layout/companion_screen.xml`
- `app/src/main/res/values/companion_styles.xml`
- `drawable/companion_panel_bg.xml`、`companion_tab_bg.xml`、`companion_key_bg.xml`
- `color/companion_tab_text.xml`

行为：

- 用 `Presentation` 把面板显示在副屏，**串流画面仍留在主屏**。
- 只在「第二块屏物理面积小于主屏」且串流渲染在主屏时出现；
  电视/显示器等大屏仍沿用原来的 `ExternalDisplayManager` 串流到外屏逻辑。
- 四个 Tab，所有页面固定高度、无 `ScrollView`：
  1. **监控**：FPS 64sp、帧时间、1% Low、解码耗时、码率 / RTT / 丢包 / 电量
     四个统计瓦片，底部四段延迟链（网络/主机/解码/渲染，按 `ChainSegment.ms`
     设 weight、按颜色设背景）。
  2. **码率**：当前码率 64sp、0.5–200 Mbps SeekBar（松手立即应用）、
     动态码率开关。
  3. **输入**：陀螺仪、音频振动、手柄触控板三个开关。
  4. **快捷**：3×3 快捷键（Esc / Win / Alt+Tab、键盘 / 性能 / HDR、
     麦克风 / 虚拟手柄 / 断开）。
- 顶栏右侧「菜单」调用现有 `Game.showGameMenu(null)`。
- 「断开」调用 `Game.disconnect()`。
- 为放大到 1.3× 且不滚动，原设计里的底部常驻操作条被移除，功能并入顶栏和快捷页。
- 焦点：Tab / 菜单 / 开关 / 滑块 / 快捷按键都可获得焦点；焦点态有 2dp 蓝色描边；
  进入面板时焦点先落在「监控」Tab。

功能映射（全部是现有能力，没有新增业务）：

| 界面 | 现有实现 |
| --- | --- |
| 帧率 / 延迟 / 丢包 / 电量 | `PerfHudSnapshot`（`PerformanceOverlayManager`） |
| 延迟链 | `LatencyChain.segments()` |
| 码率滑块 | `BitrateCardController` 映射 + `NvConnection.setBitrate()` |
| 动态码率 | `prefConfig.enableAdaptiveBitrate` + `startAdaptiveBitrateIfEnabled()` / `stopAdaptiveBitrate()` |
| 陀螺仪 | `GyroCardController` |
| 音频振动 | `AudioHapticsCardController` |
| 手柄触控板 | `ScreenDs5Touchpad.toggle()` |
| 快捷操作 | `StreamActionExecutor` |
| 主屏菜单 | `Game.showGameMenu(null)` |
| 断开串流 | `Game.disconnect()` |

### 3.5 支撑改动

- `PerformanceOverlayManager.kt`
  - 新增单槽监听 `setSnapshotListener(...)`。
  - `updatePerformanceInfo()` 每次都在主线程构建 `PerfHudSnapshot`、写入
    `lastHudSnapshot` 并回调监听，**即使主屏没有开启 HUD**，副屏也能拿到数据。
- `Game.kt`
  - `bindExternalDisplay()` 末尾调用 `updateSecondScreenCompanion(...)`。
  - 选择副屏逻辑：`DISPLAY_CATEGORY_PRESENTATION` 中 displayId 不是
    `DEFAULT_DISPLAY`、有效、且物理面积小于主屏的第一块。
  - `onDestroy()` 中 `dismiss()` 并置空。
- `app/src/debug/java/com/limelight/CompanionPreviewActivity.kt`（仅 debug）
  - 不串流时把 `companion_screen.xml` 投到副屏预览，填的是假数据，
    只用于看布局/字号/焦点，不是产品功能。
  - `debug/AndroidManifest.xml` 中新增该 Activity；原有
    `AuthoredIrTestActivity`、`MicrophoneTestActivity` 已恢复保留。

## 4. 新增资源文件

| 文件 | 用途 |
| --- | --- |
| `drawable/steam_brand_mark.xml` | 品牌 `V` 标记底 |
| `drawable/steam_rail_bg.xml` | 主屏左侧栏渐变 |
| `drawable/steam_card_scrim.xml` | 卡片底部压暗渐变 |
| `drawable/companion_panel_bg.xml` | 副屏面板圆角底 |
| `drawable/companion_tab_bg.xml` | 副屏 Tab 选中/焦点态 |
| `drawable/companion_key_bg.xml` | 副屏快捷键按下/焦点态 |
| `color/companion_tab_text.xml` | 副屏 Tab 文字颜色 |
| `values/companion_styles.xml` | `CompanionQuickKey` 快捷键样式 |

## 5. 已验证

- `./gradlew assembleNonRootDebug` 可编译通过。
- APK 已多次安装到 Thor 并启动成功。
- 主屏 PcView 新样式在真机显示正常（见 `screenshots/native-host-grid-thor-v2.png`）：
  左栏、品牌页头、「主机」分节、220dp 封面卡、在线状态、深色压暗。
- `CompanionPreviewActivity` 在 display 4 能正常显示，四页切换正常，
  1240×1080 下不需要滚动；Tab 焦点描边可见。
- 曾经误用 `wm size` 改分辨率导致主屏方向异常，已 `wm size reset` 恢复
  （Physical size 1080×1920）。

## 6. 未验证（重要）

debug 包尚未与 Sunshine / GeForce Experience 配对，所以下面这些**都没有真机跑通**：

- `Game` 里真正创建 `Presentation` 的串流路径。
- 副屏实时监控数据、延迟链在真实数据下的比例和颜色。
- 码率滑块松手后 `setBitrate()` 是否即时生效。
- 动态码率开关的启动/停止。
- 陀螺仪、音频振动、手柄触控板三个开关与主屏游戏菜单里的状态是否同步。
- 快捷按键是否真的送到主机（`StreamActionExecutor`）。
- 手柄焦点在游戏输入捕获下是否还能到达副屏；如果 `InputCapture` 优先消费按键，
  可能只能用触摸，需要在真机上确认。
- AppView 在真实应用封面下的 220×300dp 卡片效果。
- 旋转、重连、串流中途插拔副屏、`Presentation.onStop/onStart` 生命周期。
- 外接大屏（`use_external_display` 开启）路径是否保持原行为。

## 7. 未完成 / 建议下一个 agent 做的事

按优先级：

1. **端到端真机验证**（最重要）
   - 用 debug 包完成主机配对。
   - 设置里关闭 `Send stream to external display` / 「把串流发送到外接显示器」。
   - 启动串流，确认副屏 Presentation、监控数据、码率、ABR、三个输入开关、快捷操作。
   - 验证手柄焦点；若被游戏输入捕获，记录实际可行的操作方式。
2. **决定是否实现浏览态副屏**
   - 设计原型里还有「详情 / 串流设置 / 主机」等浏览态副屏页面。
   - 当前 `SecondScreenCompanion` 只在串流时创建，浏览态副屏**没有做**。
   - 如果要做，建议复用 `PcView` 现有数据源；不要编造数据。
3. **文案国际化**
   - `companion_screen.xml`、`activity_pc_view.xml` 里目前是硬编码中文
     （「主机」「全部主机」「按 A 连接 · 长按查看更多」等）。
   - 应抽到 `strings.xml`，并补中/英（至少英文）资源。
4. **生命周期/热插拔**
   - `updateSecondScreenCompanion()` 只在 `bindExternalDisplay()` 时调用；
     串流建立后副屏才出现/被移除的情况没有监听。
   - `CompanionPresentation.onStop()` 会 `dispose()` 陀螺仪/音频控制器，
     但没有 `onStart` 重新 `start()`；需要确认游戏菜单弹出/收起是否会触发。
5. **AppView 头部与空态**
   - 目前只改了应用卡片，顶部栏、空列表、加载态仍是旧样式。
6. **主屏动效与焦点态**
   - 当前是静态样式；如果需要 Steam 式聚焦放大/移动动画，需要另做
     `stateListAnimator` 或焦点监听，注意不要影响手柄操作。
7. **提交与推送**
   - 本地提交后 `git remote add fork ...` + `git push -u fork feat/steam-ui`。
8. **体积**
   - `design/` 13MB 截图；如果只需要原型，可只提交 `index.html` + `README.md`。

## 8. 常用命令

```bash
# 编译（必须带 audioHapticsSdkDir，否则 settings.gradle 报错）
./gradlew assembleNonRootDebug -PaudioHapticsSdkDir=D:/workspace/moonlight-audio-haptics

# APK
app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk

# 安装
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk

# 主屏启动 PcView（debug 包名）
adb shell am start -n com.limelight.vplus_debug/com.limelight.PcView

# 副屏预览面板（必须新建 task，否则会和 PcView 抢同一个 task）
adb shell am start --display 4 -f 0x10000000 \
  -n com.limelight.vplus_debug/com.limelight.CompanionPreviewActivity

# 副屏截图
adb shell screencap -d 4 -p /sdcard/companion.png
adb pull /sdcard/companion.png
```

## 9. 坑与注意事项

- **不要再用 `adb shell wm size` 模拟副屏**。会改变主屏方向和显示比例，
  曾经导致用户看到「大屏反转」。已经 `wm size reset`，别再动。
- `am start --activity-new-task` 不是有效参数；用 `-f 0x10000000`。
- `gh repo fork <repo> --remote ...` 会报
  `the --remote flag is unsupported when a repository argument is provided`。
  正确做法是先 `gh repo fork <repo> --clone=false`，再手动 `git remote add`。
- 构建缺少 `-PaudioHapticsSdkDir=...` 会直接失败。
- `debug/AndroidManifest.xml` 里原有的 `AuthoredIrTestActivity` 和
  `MicrophoneTestActivity` 不能删，上一轮曾误删后恢复。
- `PerformanceOverlayManager.setSnapshotListener` 是**单槽**；
  目前只有副屏使用。以后再加消费者要改成列表，或注意 `dismiss()` 会清空。
- 副屏 `Presentation` 使用了已废弃的 `systemUiVisibility`，
  文件头有 `@Suppress("DEPRECATION")`；适配 Android 15 时可换成
  `WindowInsetsController`。
- 副屏布局只做了横屏一套（`layout/companion_screen.xml`），
  没有 `layout-land` 变体；Thor 副屏按横屏使用。
- `design/steam-ui/screenshots/` 里的 `second-*` 是 HTML 原型截图，
  不是真机副屏截图，别混淆。
