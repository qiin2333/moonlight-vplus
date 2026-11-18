# 王冠（Crown）UI 全量清单 · 现状诊断与重设计对照

> 配套设计稿：[`docs/crown-ui-redesign.html`](crown-ui-redesign.html)
> 基线：当前工作区（含未提交改动）。仓库里已有一次「串流内配色向 `ui_shell_*` 收口」的 WIP（`values/advance_setting_colors.xml` 的 `crown_*` 别名、若干 `keyboard_*` selector、`page_*_clean.xml` 的行高/圆角微调），本清单以工作区现状为准，并把它视为 P0 的起点而不是完成态。
> 旧稿 `docs/crown-ui-mockup.html`（只覆盖串流内、且明确排除超市）已被本次设计稿取代。

---

## 0. 一页结论

| 指标 | 现状 |
|---|---|
| 可辨识界面 / 面 | **56 个**（入口 5 · 串流面板 7 · 属性编辑 13 · 输入绑定 7 · 超市 8 · 弹窗 11 · 反馈 5） |
| 涉及布局文件 | 17 个活跃 SuperPage + 2 个占位（`page_null`、`page_add_element`）+ 7 个键盘相关 + 10 余个片段/条目 |
| UI 技术栈 | **3 套**：View+XML（主应用 / 串流王冠）、Compose（游戏菜单）、Compose（超市，裸 `MaterialTheme{}`）+ View `AlertDialog` |
| 品牌色实现 | 超市 **8 个私有角色色**；串流面板 **17 个 `crown_*` 别名**（部分已指回 `ui_shell_*`） |
| 字号 | 串流属性页 12–18sp（含 8–10sp 键盘文字）；超市 **18 种**，其中 7 个非整数值（10.4 / 10.5 / 10.8 / 11.6 / 12.2 / 13.5 / 15.4） |
| 行高/控件高 | 32 / 34 / 38 / 40 / 42 / 44 / 46 / 48 / 50 / 58 / 60 / 62 dp 共 12 种 |
| 圆角 | 4 / 8 / 12 / 16 / 20 / 24 + 两处 `RoundedCornerShape(50)` 百分比胶囊 |
| 硬编码颜色 | 属性页 **44 处** hex 默认值（`#FFFFFF` / `#00FF00` / `#000000` / `#80000000` 反复重抄） |
| 已知可用性缺陷 | 夜间主按钮对比度 ≈ **1.9:1**；夜间排序菜单落 Material 默认浅色；32dp 图标按钮低于 48dp 触控标准；删除仅长按 |

**一句话诊断**：王冠不是「某个页面丑」，而是**没有自己的设计系统**——每个子功能各自发明颜色、字号、行高和控件，于是既不像主应用，也不像游戏菜单。

---

## 1. 全量清单

### A. 入口与发现（5）

| ID | 界面 | 位置 | 现状要点 |
|---|---|---|---|
| A1 | 设置 → 「王冠」分类 | `res/xml/preferences.xml:1001-1016` | 整类只有 2 行：启用开关、管理配置；无状态、无当前配置、无超市入口 |
| A2 | 启用王冠开关 | 同上 `CheckBoxPreference` | 用复选框而非全局 `AppSwitchView`；标题「启用 Crown 配置」与技术名混用 |
| A3 | 游戏菜单顶栏王冠按钮 | `gamemenu/GameMenuScreen.kt:1306-1324` | 36dp 圆形，彩色 `ic_super_crown`，accent 10%/20% 自绘；与相邻单色线性图标不同族 |
| A4 | 游戏菜单「王冠功能」条目 | `GameMenu.kt:2621`、`game_menu_crown_control_item.xml` | 条目本体是通用列表项；专用布局文件在主源码中**无引用** |
| A5 | 新手引导卡 + 进出王冠 Toast | `GameMenuScreen.kt:265`、`feature_guide_crown_title/body`、`toast_back_key_menu_switch_*` | 引导卡是独特插画风，只在首次出现，与面板风格无延续 |

### B. 串流内面板（7）

| ID | 界面 | 文件 / 入口 | 现状要点 |
|---|---|---|---|
| B1 | Crown Super 菜单 | `page_super_menu.xml`（320dp）／`PageSuperMenuController` | 4 行等权 46dp：配置、编辑、关闭王冠、断开串流；终止动作也画右箭头 |
| B2 | 配置页 | `page_config.xml`（320dp）／`PageConfigController` | 3 段（配置/触控/震动）；**原生 Spinner**；7 开关 + 2 滑杆 + 4 个 32dp 图标按钮；14 个交互控件挤在 320dp |
| B3 | 编辑菜单 | `page_edit.xml`（320dp）／`ElementController` | 2 开关 + 1 滑杆 + **12 行等权「添加…」**，无分组；右上 2 个 32dp 图标 |
| B4 | 面板标题条 | `crown_page_title.xml` | 24dp 彩色皇冠 + 18sp 粗体；`paddingEnd=76dp` 硬编码给 B2 的 4 个按钮让位 |
| B5 | 配置命名/删除页 | `page_window.xml`（280dp）／`PageConfigController` | 48dp 下划线式输入 + 两枚 44dp 按钮；删除模式运行时移除输入框 |
| B6 | 页面容器与切换 | `super_pages_box.xml`、`SuperPagesController.kt:111-181`、`SuperPageLayout.kt:110-131` | 宽 320dp、上下 20dp 内缩；300ms 线性滑动；双指横滑 70px 翻页；页面宽度写两遍（layout + `android:tag`） |
| B7 | 「无页面」空页 | `page_null.xml`（0dp） | 参与滑动动画，返回时整块面板收缩到 0，视觉上像闪一下 |

### C. 元素属性编辑（13）

共享底座：`NumberSeekbar`（58/60dp 行，± 为缩放位图）、`ElementEditText`（假输入框）、`ElementController.applyCrownDetailStyle()` 在运行时**重写所有文字/背景色**、`promoteCrownDetailActions()` 把页内「复制/删除」按钮**隐藏后用代码重建图标条**。

| ID | 页面 | 控件数 | 段落 |
|---|---|---|---|
| C1 | 数字通用按钮 `page_digital_common_button_clean` | 17 | 基础 / 位置尺寸 / 外观 / 颜色 / 文字 |
| C2 | 数字开关按钮 `page_digital_switch_button_clean` | 17 | 同上 5 段 |
| C3 | 数字移动按钮 `page_digital_movable_button_clean` | 19 | 基础（含 2 开关）/ 位置 / 外观 / 颜色 / 文字 |
| C4 | 组合按钮 `page_digital_combine_button_clean` | 19 | 基础（5 个键值）/ 位置 / 外观 / 颜色 / 文字 |
| C5 | 方向键 `page_digital_pad_clean` | 15 | 方向配置（4 值十字）/ 位置 / 外观 / 颜色 |
| C6 | 数字摇杆 `page_digital_stick_clean` | 15 | 摇杆配置（4 方向 + 中键）/ 位置 / 外观 / 颜色 |
| C7 | 模拟摇杆 `page_analog_stick_clean` | 17 | 基础 / 位置 / 外圈 / 摇杆 / 颜色 |
| C8 | 隐形模拟摇杆 `page_invisible_analog_stick_clean` | 16 | 基础 / 位置 / 样式 / 操作 |
| C9 | 隐形数字摇杆 `page_invisible_digital_stick_clean` | 17 | 方向 / 位置 / 样式 / 操作 |
| C10 | 组合按键组 `page_group_button_clean` | 20 | 分组 / 子控件 / 位置 / 外观 / 子控件管理 / 颜色 / 文字 / 删除确认 |
| C11 | 滚轮 `page_wheel_pad_clean` | 21 | 基础 / 滚轮 / 键值（动态行）/ 位置 / 边框色 / 文字 / 操作（**唯一 320dp**） |
| C12 | 性能简化 `page_simplify_performance_clean` | 11 | 位置 / 外观 / 文字 / 操作 |
| C13 | 属性页公共片段 | 各页复制粘贴 | 60+ 个滑杆、5–6 个色值框、6 种按钮高度、4 种标签字号 |

### D. 输入与绑定（7）

| ID | 界面 | 文件 | 现状要点 |
|---|---|---|---|
| D1 | 设备/按键选择页 | `page_device.xml`（720dp）／`PageDeviceController` | 4 个 48dp 等宽 tab；操作列表 16×50dp；键盘 600×242dp 硬编码 |
| D2 | 操作键列表 | `page_device.xml:57-179` | 16 个 50dp 行，`KeyboardKeyStyle` 直接复用键帽样式，长得像键盘 |
| D3 | 键盘选择器 | `crown_keyboard_key_picker.xml` + `layout_keyboard_{main,nav,numpad}.xml` | 3 页 + 42dp tab；主键盘 47 键；`DeviceKeyboardPagesLayout` 按高度算宽度，最低 600dp |
| D4 | 键帽四态 | `keyboard_*_selector.xml`、`KeyboardKeyRefined*` | 常态 1dp 描边 / 聚焦 2dp / 选中 accent27 / 锁定 accent-soft；三套 selector 数值不一致 |
| D5 | 鼠标示意图 | `page_device.xml:187-271` + `mouse_left/right/bottom` | 50×150dp 手掌形贴图拼装，无选中态 |
| D6 | 手柄示意图 | `page_device.xml:272-496` | 40×40dp 方块拼装（十字键/ABXY/扳机），无摇杆轴 |
| D7 | 浮动键盘 | `layer_6_keyboard.xml` + `KeyboardUIController` | 400×220dp 可拖拽；46dp 侧栏、52dp 页按钮、8sp 透明度标签、28sp 弹字、可调宽度 |

### E. 超市与工作台（8）

| ID | 界面 | 位置 | 现状要点 |
|---|---|---|---|
| E1 | 外壳 / 顶栏 / 底栏 | `CrownStoreActivity.kt:316-466` | Compose `Scaffold` + M3 `NavigationBar` 默认 80dp；顶栏自绘 20sp 居中标题 + 旋转右箭头当返回 |
| E2 | 超市浏览 | `:468-659` | 2 列瀑布流、10dp 间距；简介 + 2 按钮 + 搜索 + 排序 + 结果数全塞在 header |
| E3 | 超市卡片 | `:854-914` | 标题/游戏/作者/摘要/页脚 + **两个全宽按钮**；1dp 阴影 + 1dp 描边双重边界 |
| E4 | 详情 | `:916-979` | 纯文字字段堆叠，无布局预览；底部两枚全宽按钮 |
| E5 | 我的：Hero / 区块头 / 本地卡 / 工具卡 | `:697-852`、`:990-1051` | 52dp 圆形头像 + 计数胶囊；本地卡 **2×2 四个按钮**；长按删除无提示 |
| E6 | 排序下拉 | `:592-605` | M3 `DropdownMenu` **未着色**，夜间落到默认浅色 `#FFFBFE` |
| E7 | 五种状态 | `:614-650`、`:1077-1129` | 加载/错误/未加载/空/无结果；共用文字卡 + 固定刷新按钮 |
| E8 | 状态与标签 chip | `:1260-1284`、`:1224-1258` | `CrownStatusChip` 用 accent_focus（20%）；`CrownTags/CrownTagChip` **不可达死代码** |

### F. 对话框（11）

统一 `AlertDialog + R.style.AppDialogStyle`，但内部输入/列表各写各的。

| ID | 弹窗 | 位置 | 现状要点 |
|---|---|---|---|
| F1 | 布局兼容性检查 | `:274-285` | 标准双按钮 |
| F2 | 删除本地配置 | `:1336-1356` | 标准双按钮；默认配置被拦截 |
| F3 | 投稿元数据 | `:1463-1599` | 5 个 `EditText` 用 `backgroundTintList` 画下划线；容器 padding 22dp |
| F4 | GitHub 授权 | `:1742-1762` | 标准双按钮 |
| F5 | 投稿成功 | `:1764-1780` | 双按钮 |
| F6 | 设备码授权 | `:1829-1883` | 三按钮，自持引用、手动清理 |
| F7 | 未 star 提示 | `:1992-2000` | 复用开发者文案 |
| F8 | URL 导入 | `:2027-2062` | `EditText` **完全未设色/未设背景**；容器 padding 24dp（与 F3 不一致） |
| F9 | 导入预览 | `:2107-2131` | 三按钮（新装 / 合并 / 取消） |
| F10 | 合并目标 | `:2145-2170` | `setItems` 单选列表，`AppDialogStyler` 统一行样式 |
| F11 | 直接切换配置 | `CrownConfigPickerDialog.kt` | `ArrayAdapter(android.R.layout.simple_list_item_1)`，系统列表样式；手动方向键/滚动重复逻辑 226 行 |

### G. 反馈与覆盖（5）

| ID | 面 | 现状 |
|---|---|---|
| G1 | Toast | 约 45 处；成功/失败/拦截全靠 Toast，无内联反馈 |
| G2 | 空态/错误态 | `CrownStateCard` + `CrownLoadingState`：图标 24dp + 17sp 标题 + 正文 + 可选按钮 |
| G3 | 状态/标签 | 两套胶囊：状态 chip 20% 底 + 6dp 点；tag chip（死代码）8dp 圆角 |
| G4 | 动效 | 仅面板 300ms 滑动；无按压/焦点/切换动效，与主应用 `ExpressiveMotion`（弹簧按压）不一致 |
| G5 | 死资源 | `item_page_super_menu.xml`、`page_add_element.xml`（空）、`game_menu_crown_control_item.xml`、`crown_right_arrow.xml`、`crown_color_input_bg.xml`（与 input 逐字节相同）、`crown_store_action_edit_publish`（8 语言定义、0 引用） |

---

## 2. 问题清单（按主题，附证据）

### 2.1 体系分裂
1. 三套技术栈：View/XML（串流王冠）、Compose（游戏菜单）、Compose 裸 M3（超市）。`CrownStoreActivity.kt:331` 的 `MaterialTheme { }` 没有任何参数，`DropdownMenu`、`NavigationBar` 的默认形状/字号/涟漪都来自 M3 baseline。
2. 超市没有复用任何共享色：`crown_store_colors.xml` 用 8 个角色名复制了 `ui_shell_*` 的角色集，值不同。
3. 图标两个族：`ic_super_crown`（5 色插画）与 `phc_crown`（单色线性）同时代表「王冠」。

### 2.2 颜色与对比度
4. 夜间色板方向反了：页底 `#4D464A`（褐灰）比卡片 `#CC252833` 叠出来的 `≈#2D2E38` 更亮，卡片读作凹洞。
5. 夜间主按钮：`CrownActionButton:1296` 前景用 `app_dialog_title_color`（夜间 `#F7F3F7`），压在夜间 accent `#FF8FA3` 上，对比 ≈ **1.9:1**；仓库已有 `add_pc_on_accent=#1F2026` 未使用。
6. 浅色描边过重：`crown_store_section_border=#FFE5E0E8` 是不透明预混色，视觉重量约为 `ui_shell_outline`（15% 墨）的 6–7 倍。
7. accent 槽位误用：超市所有强调底用 `appAccentFocus`（20%/40%）而不是 `accent_soft`（10%/20%），整个界面「粉得更重」。

### 2.3 字号与密度
8. 超市 18 种字号、8 个小数号；最小按钮文字 10.5sp。
9. 键盘 8–10sp（`layer_6_keyboard.xml:94`、`layout_keyboard_nav.xml:15-55`），与键帽 15sp 之间没有中间档。
10. 属性页正文 11–12sp、区块标题 14sp、页标题 18sp，全部挤在 280dp 内；60+ 个 58/60dp 滑杆单列平铺，滚动长度极长。

### 2.4 几何与布局
11. 12 种行高/控件高（32/34/38/40/42/44/46/48/50/58/60/62），同一语义多种值：复制/删除按钮有 46/48/44 三种高度，色值框 38 与 40 两种，滑杆 58 与 60 两种。
12. 三个 tab 条三种规格：设备页 48dp `Button`、键盘选择器 42dp `TextView`、浮动键盘 52dp 页按钮。
13. 两个逐字节相同的 drawable：`crown_config_input_bg.xml` / `crown_color_input_bg.xml`；两份重复箭头：`right_arrow` / `crown_right_arrow`。
14. `crown_page_title.xml:8` 的 `paddingEnd=76dp` 是 B2 四个 32dp 按钮的宽度耦合，改按钮即破版。
15. 页面宽度写两遍（`layout_width` + `android:tag`），`SuperPagesController.kt:173-174` 读 tag 当 dp 用。

### 2.5 控件与状态
16. 原生 `Spinner`（`page_config.xml:53-60`）还带了只属于 `NumberSeekbar` 的 `android:max/min`、`app:progress` 三个无效属性。
17. 弹窗列表 `simple_list_item_1`；输入框下划线；原生 `RadioButton` 只在两个摇杆页出现。
18. 「假输入框」：颜色字段是 `ElementEditText`，运行时被设为不可聚焦、无光标，只当色块；值字段是 `TextView` 却画成输入框。
19. 面板内的卡片/输入框全是静态 `<shape>`，没有按下/聚焦态；只有两处按钮 drawable 有 selector。
20. 32dp 图标按钮（配置页 4 个、编辑页 2 个）低于 48dp 触控标准。
21. 删除藏在长按（`CrownStoreActivity:996-998`），无任何可见提示；分组页的删除要输入确认文本。

### 2.6 信息架构与交互
22. 设置入口只有 2 行，「管理配置」直接跳进商店；王冠没有「家」。
23. 超市卡片与本地卡片都平铺多个同权按钮（2 个 / 4 个），主操作不突出。
24. 属性页的复制/删除先在 XML 里写、再被代码隐藏并重建，维护双份。
25. 12 个「添加…」行无分组、无搜索；元素类型多时找不到。
26. 面板标题只写「王冠」，不显示当前配置；切换配置要先退出到菜单再到配置页。
27. 终止动作（关闭王冠、断开串流）画右箭头，暗示还有下一级。
28. 键盘绑定弹窗点行即关闭，无法确认；无「当前已绑定」回显。

### 2.7 反馈与空态
29. 45 处 Toast 承担全部结果反馈；投稿校验、导入失败等无字段级提示。
30. 无「未保存/已保存」状态；编辑布局后退出没有确认。
31. 空态/错误态是同一张文字卡，只换标题和按钮。

### 2.8 无障碍与本地化
32. 键盘 8sp、标签 9.5sp 在小屏/大字号设置下不可读。
33. 大量 `ImageView` 无 `contentDescription`（箭头、示意图、色块）。
34. 固定 `paddingStart/End` 与 `layout_marginStart` 混用，RTL 语言下箭头方向没有镜像。
35. 文案不一致：`crown_store_tab_store` 简中为「超市王冠」、英文为 "Crown Market"；`crown_toggle_button`、`crown_mode_normal/crown` 等 4 个键已废弃未删。

---

## 3. 新设计如何对应（L / P 编号见设计稿）

| 设计稿 | 覆盖清单 | 核心变化 |
|---|---|---|
| L1 概览 | A3、B1、B2、B4、B6 | 380dp 侧栏；头部 = 皇冠标记 + 配置名 + 控件数；配置卡可切换；开关分组；唯一主操作「编辑布局」 |
| L2 方案设置 | B2 | 分段导航（布局/触控/反馈）；滑杆重做（数值 pill + 轨道 + 端点）；去掉原生 Spinner；分组小标题 |
| L3 编辑画布 | B3、B6、B7 | 选中态（描边+手柄+尺寸）；顶部浮动工具条；添加控件变 FAB（12 行收进弹层） |
| L4 Inspector | C1–C13 | 84dp 实时预览；两列滑杆；颜色行 = 色块 + hex + 箭头；折叠「触控与动作」；页脚固定操作 |
| L5 键盘 | D1–D4 | 664dp 居中面板；4 段输入切换；30dp 键帽四态；子页 chip；规则进页脚 |
| L6 手柄 | D6 | 560dp 示意图重绘；摇杆/扳机可绑定；A 键选中与键盘同源 |
| L7 切换弹窗 | F11 | 366dp 对话框；带缩略图/控件数/最近使用的行；当前项勾选；取消/切换 |
| L8 夜间 | 全部 | 夜间值全部来自 `values-night`；卡片抬升；accent #FF8FA3 + on-accent #20161B（≈7.6:1） |
| P1 设置分类 | A1、A2 | 状态头（启用开关 + 当前配置）+ 编辑/配置/超市/导入四行 |
| P2 超市 | E1–E3、E6、E7 | 搜索+筛选一行；布局缩略图卡片；列表只留扫读信息与箭头 |
| P3 详情 | E4 | 大图预览 + 作者 + 标签 + 描述 + 控件构成/兼容性/分享包；导入为唯一主操作 |
| P4 我的 | E5、E8 | 当前配置块 + 列表；每行 ⋯ 菜单承载导出/合并/投稿/删除 |
| P5 投稿 | F3、F8 | 统一输入（36dp 圆角）、chip 标签、勾选同意、双按钮 |

---

## 4. 落地顺序与验收

### P0 · 颜色与圆角（1–2 天）
- 删除 `crown_store_colors.xml`（两个主题文件），超市全部改用 `ui_shell_*` / `app_dialog_*`。
- 新增 `crown_on_accent`（= `add_pc_on_accent`）、`crown_surface_raised`（超市夜间卡片）。
- 面板 20dp、卡片 16dp、输入 10–12dp、键帽 8dp，全部走 `@dimen/corner_radius_*`。
- **验收**：浅/夜间截图对照；夜间主按钮对比度 ≥ 4.5:1；夜间卡片比页底亮 ≥ 5%。

### P1 · 串流面板与属性页（3–4 天）
- 抽 5 个组件：`crown_panel_header`、`crown_section`、`crown_row`、`crown_slider_row`、`crown_color_row`。
- 面板 320→380dp；滑杆 58/60→40dp；属性页两列；复制/删除只保留页脚一套。
- **验收**：属性页滚动高度 ≤ 原来 60%；所有点击目标 ≥ 48dp；深浅色同一套 drawable。

### P2 · 超市与对话框（3–4 天）
- Compose 侧加 `CrownTheme`（读 `colorResource`），删掉逐处 `fontSize`，改 5 档。
- 卡片加布局缩略图；四按钮收进 ⋯；详情加导入主操作。
- 10 个 `AlertDialog` 统一输入/列表；`CrownConfigPickerDialog` 换自定义行。
- **验收**：超市 0 个 hex 字面量、0 个小数 sp；排序菜单夜间可读；删除有可见入口。

### 回归清单
- [ ] 串流内：Super 菜单 → 配置 → 编辑 → 添加 12 类控件 → 属性页 → 键盘/鼠标/手柄绑定 → 返回，全链路可用
- [ ] 配置 CRUD：新建、重命名、删除、直接切换
- [ ] 导入导出：`.crown` 分享包、旧版 `.mdat`、URL 导入、合并
- [ ] 超市：加载/错误/空/无结果、搜索、排序、详情、投稿、GitHub 授权
- [ ] 深浅色、横竖屏、大字号、RTL
- [ ] 手柄全流程可聚焦，焦点环可见
