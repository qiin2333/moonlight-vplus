# Steam UI 功能清单（只换皮，不改功能）

> 更新：2026-11-11。原则：每个界面元素必须对应现有功能/数据；原型里没有来源的内容一律不做。
> 验证环境：AYN Thor（主屏 1920×1080，副屏 1240×1080），Windows 主机（Sunshine）真实串流。

状态：✅ 真机验证 · 🟡 已实现未验证 · ⬜ 未做 · ✂️ 舍弃（无现有功能支撑）

## 1. 主屏 · 主机列表（PcView）

| 功能 | 现有实现 | 状态 |
| --- | --- | --- |
| 主机封面卡（名称/在线状态/锁图标） | `PcGridAdapter` | ✅ |
| 点击主机 → 配对/测试网络/更多操作菜单 | 现有 PcView 菜单 | ✅ |
| 左侧工具栏（设置/信息/EasyTier/更多） | 现有按钮，仅换位置 | ✅ |
| 品牌页头 + 「主机」分节标题 | 新增静态文案（已进 strings.xml） | ✅ |
| 添加主机卡 | `PcGridAdapter` | 🟡 |
| 手柄焦点移动 / A 键连接 | 现有焦点逻辑 | 🟡 |
| 焦点放大/移动动画 | — | ⬜ |

## 2. 主屏 · 应用列表（AppView）

| 功能 | 现有实现 | 状态 |
| --- | --- | --- |
| 应用封面卡（220×300dp） | `app_grid_item.xml` | 🟡 |
| 点击启动/恢复串流 | 现有逻辑 | ✅（串流已跑通） |
| 顶部栏、排序/搜索、空态、加载态 | 现有 | ⬜ 仍是旧样式 |

## 3. 副屏 · 串流伴生面板（串流时）

| Tab | 功能 | 现有实现 | 状态 |
| --- | --- | --- | --- |
| 监控 | 帧率/帧时间/1% Low/解码 | `PerfHudSnapshot` | ✅ 真实数据 |
| 监控 | 码率/RTT/丢包/电量 | 同上 | ✅ |
| 监控 | 延迟链（网络/主机/解码/渲染） | `LatencyChain.segments()` | ✅ |
| 码率 | 当前码率、滑块、松手应用 | `BitrateCardController`（与游戏菜单共用） | ✅ 读到真实 20 Mbps，滑块拖到 46 显示正常 |
| 码率 | 动态码率开关 | `BitrateCardController.setAdaptiveBitrate` | 🟡 |
| 输入 | 陀螺仪 / 音频振动 | `GyroCardController` / `AudioHapticsCardController` | 🟡 |
| 输入 | 手柄触控板 | `ScreenDs5Touchpad.toggle()` | 🟡 |
| 快捷 | Esc/Win/Alt+Tab、键盘/性能/HDR、麦克风/虚拟手柄/断开 | `StreamActionExecutor`、`Game.disconnect()` | 🟡 |
| 顶栏 | 「菜单」打开游戏菜单 | `Game.showGameMenu(null)` | 🟡 |
| 通用 | 不滚动、Tab 切换、1.3× 字号 | — | ✅ |
| 通用 | 手柄焦点能否到达副屏 | — | 🟡 需手柄实测 |
| 通用 | 副屏出现条件（第二屏面积小于主屏） | `Game.updateSecondScreenCompanion` | ✅ |

## 4. 副屏 · 浏览态（未串流时）

原型里有「详情 / 串流设置 / 主机」页。**当前未实现**。只在有真实数据来源时才做：

| 候选内容 | 数据来源 | 结论 |
| --- | --- | --- |
| 当前选中的主机/应用名称与封面 | PcView / AppView 选中项 | 可做 |
| 主机在线状态、配对状态 | `ComputerDetails` | 可做 |
| 串流设置摘要（分辨率/帧率/码率/编码） | `PreferenceConfiguration` | 可做，只读或跳转设置 |
| 「开始串流」按钮 | 现有启动流程 | 可做 |
| 最近游玩、游玩时长、最近会话 | 无 | ✂️ |
| 搜索、收藏、游戏简介/标签 | 无 | ✂️ |
| 「上次串流」「本次时长」统计卡 | 无 | ✂️ |

## 4b. 已落地的补充（2026-11-11）

| 功能 | 现有实现 | 状态 |
| --- | --- | --- |
| 有副屏时主屏隐藏设置工具栏，设置/关于/EasyTier/主题/恢复会话/未配对开关集中到副屏「菜单」页 | `BrowseScreenCompanion.MenuItem` | ✅ |
| 手柄快捷键：X 添加主机、Y 主机更多、Start 设置、Select 未配对设备 | `PcView.handleGamepadShortcut` | 🟡 X 已验证 |
| 串流中游戏菜单显示在副屏（TYPE_PRESENTATION=2037），主屏继续串流 | `Game.companionMenuDisplay` + `GameMenu` | ✅ |
| 游戏菜单固定 Steam 深蓝配色（不跟随系统亮/暗） | `game_menu_*` 颜色资源 | ✅ |
| 全局主题色统一为 Steam 蓝 | `appAccent` 相关资源 | ✅ |
| 游戏菜单子对话框、副屏「菜单」页里的对话框仍弹在主屏 | — | ⬜ |
| AppView 换皮：深蓝遮罩、左对齐标题、放大卡片、蓝色选中框与播放角标（灰色操作菜单仍待换） | `appview_*` 资源 | ✅ |
| 副屏浏览页跨 PcView/AppView/Game 常驻，返回键与 HOME 都不会让副屏回桌面，仅主屏应用退出才关闭 | `AppVisibility` | ✅ |

| 应用名 Moonlight Dual、新图标（`design/steam-ui/icon.html`，双环缎带）、开屏动画图标 | `dual_icon_*`、`Theme.App.Starting` | ✅ |
| 延迟链路四段配色（蓝/珊瑚/绿/黄）+ 带色点图例 | `PerfHudPalette`、`chainLegend` | ✅ |
| 圆角统一 12dp（胶囊/圆形除外） | `corner_radius_*`、`AppCornerRadii` | ✅ |
| 默认背景改 Steam 深色；对话框/操作菜单改深蓝；残留粉紫色清理 | `steam_default_background` 等 | ✅ |
| 旧版 com.limelight.qiin 已从测试机卸载，只保留 vplus_debug | — | ✅ |

## 5. 原型中被舍弃的内容

`design/steam-ui/index.html` 里的继续游玩英雄区、最近游玩横排、游戏库页、搜索框、
设备间状态胶囊等均无对应功能，不做。真机 AppView 另有现成的横向卡片样式，后续应
以现有布局为基础换皮，而不是照搬原型。

## 6. 工程待办

| 项 | 状态 |
| --- | --- |
| 硬编码中文抽到 `strings.xml`（中/英） | ✅ 副屏与主屏页头；其余界面沿用原有资源 |
| 副屏 `onStop` 后控制器重启 | ✅ 改到 `onStart` |
| 外接大屏路径保持原行为 | ⬜ 未验证 |
| 串流中拔插副屏 / 旋转 / 重连 | ⬜ 未验证 |
| 副屏动态出现（连接建立后才插入第二屏） | ⬜ 仅在 `bindExternalDisplay` 时判断 |
| 构建：Windows 构建 → scp 回 Mac → adb 安装 | ✅ 见 `moonlight-vplus-mac-build` 备忘 |
