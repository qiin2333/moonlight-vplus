# Moonlight V+ 威力加强版

<div align="center">
<img src="https://github.com/user-attachments/assets/10800322-d8ab-4419-bd05-5fc37fd4c8f3" width="360" alt="apps">
<img src="https://github.com/user-attachments/assets/5e227bab-afaa-4452-ae38-ac2cc9d22988" width="360" alt="apps">
<img src="https://github.com/user-attachments/assets/c755d228-d9f5-4068-ae6c-c3f8ea0a0f2f" width="360" alt="apps">
<img src="https://github.com/user-attachments/assets/5046dd58-7013-494e-9f17-26e4de56a7dd" width="360" alt="apps">
</div>

## Changes

v12.2.5
- 发送特殊按键可自定义, 并支持键盘选取 (#24) by @cjcxj
- 添加切换触控菜单，可切换为触控板模式 (#23)  by @cjcxj
- 菜单集成实时码率调节面板，调节更快速！

v12.2：
- 重构游戏菜单，与Sunshine应用编辑页风格统一
- 重构连接中的体验，分享串流最佳实践
- 快捷功能增加麦克风按钮的显示控制
- 快捷功能增加实时码率调节
- 更友好的主机与APP详情展示
- 补全设置菜单英文翻译

2025/07/26：
- 修复部分Rockchip SOC 不能开启HEVC HDR的问题
- 开启增强式多点触摸后触控笔也可正常使用
- 优化麦克风长时间使用延迟增大的问题

2025/07/21：
- *支持麦克风重定向，需Sunshine基地版2025.0720+

2025/07/17：
- 使用外接屏幕时可以选择复制或沉浸式投屏，沉浸式投屏性能覆盖层在本机屏幕上展示并添加实时电量。
- *支持sunshine端修改客户端配对名字。

2025/07/06：
- 优化部分联发科SOC的显示解码时间 (天玑9300以下可能有效果)  [Added test flags for Mediatek SoCs](https://github.com/alonsojr1980/moonlight-android-turbo/commit/5c6eeb98653a1c18661eaa9d6a32e2329994b0ed) @alonsojr1980
- 修复ColorOS串流HDR内容时无法正确激发亮度。

2025/06/22：
- 盯帧能力升级：性能覆盖层可配置展示项目、位置、方向，并可在串流中拖动位置。

2025/06/01：
- 外接物理键盘使用ESC键不首先弹出返回菜单 #15
- 优化部分骁龙SOC的显示解码时间 (8Gen2+)  [Added optimization flags for latest Snapdragons (Elite, SD8 gen 3, SD8 gen 2)](https://github.com/alonsojr1980/moonlight-android-turbo/commit/c5f203b69c29b0d2ba34454f9eb1f8a6393ceddb) @alonsojr1980

2025/04/08：
- 可移动按键增加手柄瞄准 by @Xmqor [#13]
- 非线性码率调整
- fix: 关闭增强触摸恢复经典鼠标模式

2025/03/24：
- 反转分辨率（竖屏）功能
- 串流画面位置设置，支持八个方向加偏移量

2025/02/23：
- 增加多场景预设切换能力：右下角鲨牙长按保存当前预设，点击应用对应预设

---

- 增加返回菜单快速操作常用功能  Inspired by PR [#1171](https://github.com/moonlight-stream/moonlight-android/pull/1171)
- 增加常用 pc 快捷键（win, esc, hdr) 在串流菜单上
- 更好的性能显示，适合常驻。
- 一键睡眠，支持win不退出游戏睡觉, 醒来即玩，主机级体验
- *支持自定义分辨率、不对称分辨率串流（缩放比例调节）
- 解锁码率限制，帧率限制，支持最高800m码率串流， 支持强制解锁144/165hz刷新率。
- 合入 [【砖家】](https://github.com/TrueZhuangJia/moonlight-android-Enhanced-MultiTouch/releases/tag/v12.1_AddNativeTouchKeyboardToggle) @TrueZhuangJia 版本特性
- 合入[【王冠】](https://github.com/WACrown/moonlight-android) @WACrown 最强自定义按键的特性
- *增加超级菜单指令，可在返回菜单中操作主机端配置的对应功能。
- *应用桌面美化，支持应用缩略图同步背景，应用列表自定义排序
- *串流自动启用 与当前设备关联的 HDR 校准文件

带有 * 开头的特性需配合基地版sunshine 使用
