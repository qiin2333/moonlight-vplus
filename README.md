<div align="center">
  <img src="./app/src/main/res/drawable/vplus.webp" width="100" alt="Moonlight V+ Logo">
  
  # Moonlight V+ 威力加强版
  
  [![Version](https://img.shields.io/badge/version-12.5.3-blue.svg)](https://github.com/qiin2333/moonlight-android/releases/tag/shortcut)
  [![Android](https://img.shields.io/badge/Android-5.0+-green.svg)](https://developer.android.com/about/versions)
  [![License](https://img.shields.io/badge/license-GPL%20v3-orange.svg)](LICENSE.txt)
  [![Stars](https://img.shields.io/github/stars/qiin2333/moonlight-android?style=social)](https://github.com/qiin2333/moonlight-android)
  
  **基于 Moonlight 的增强版 Android 串流客户端** 🎮
  
  *让您的 Android 设备成为强大的游戏串流终端！Gawr！* ✨
</div>

## 📱 应用截图展示

<div align="center">
  <img src="https://github.com/user-attachments/assets/bb174547-9b0d-4827-81cf-59308f3cfa9e" width="640" alt="主界面">
  <div align="center">
    <img src="https://github.com/user-attachments/assets/5e227bab-afaa-4452-ae38-ac2cc9d22988" width="240" alt="游戏列表">
    <img src="https://github.com/user-attachments/assets/c755d228-d9f5-4068-ae6c-c3f8ea0a0f2f" width="240" alt="串流界面">
    <img src="https://github.com/user-attachments/assets/5046dd58-7013-494e-9f17-26e4de56a7dd" width="240" alt="设置界面">
  </div>
</div>

## ✨ 特性

### 🎯 核心功能

- **高性能串流**：解锁 144/165Hz 超高刷新率，支持最高 800Mbps 码率，动态自适应，畅享极致流畅画面。
- **HDR 支持**：完整 HDR 内容串流，自动启用设备专属 HDR 校准文件，画质更真实，色彩更鲜明。
- **自定义分辨率**：支持自定义分辨率、宽高比和不对称分辨率，满足各种显示需求，适配更多设备。
- **多场景预设**：一键切换不同游戏场景的串流设置，右下角鲨牙长按即可保存/切换，轻松应对多种使用场景。
- **功能卡片**：支持自定义功能卡片，快速访问常用操作、快捷指令、性能监控等，操作更高效。
- **多设备支持**：支持手机、平板、电视盒子、掌机等多种 Android 设备，体验一致。

### 🎮 游戏体验

- **增强触控**：支持触控笔、手写笔和多点触控，内置触控板模式，触控体验顺滑精准，适配更多场景。
- **自定义按键**：可自由拖动、缩放、隐藏按键布局，支持手柄瞄准、组合键、连发等高级功能，按键随心定制。
- **体感助手**：内置陀螺仪体感辅助，支持体感瞄准、体感转视角，灵敏度可调，手柄无体感也能体验。
- **快捷操作**：一键睡眠、快速切换输入法、常用 PC 指令一键发送，支持自定义快捷菜单，效率拉满。
- **性能监控**：实时显示帧率、码率、延迟、丢包等串流性能指标，支持自由拖动和自定义显示位置，性能一目了然。
- **多手柄支持**：支持多手柄同时连接，自动识别 Xbox/PS/Switch/国产手柄，按键映射灵活，联机更方便。

### 🎨 界面优化

- **美化桌面**: 应用缩略图同步背景，自定义排序，桌面超好看！
- **菜单重构**: 与 Sunshine 应用编辑页风格统一，界面超协调！
- **实时调节**: 菜单集成码率调节面板，操作更便捷，调节超快速！

### 🎤 音频功能

- **麦克风重定向**: 支持远程语音（需 Sunshine 基地版 2025.0720+），音质好的不像在串流！

## 🚀 快速开始

### 系统要求

- Android 5.0 (API 22) 或更高版本
- 支持 HEVC 解码的设备
- 稳定的网络连接

### 安装方式

#### 方式一：下载 APK（最简单的方式！）

1. 从 [Releases](https://github.com/qiin2333/moonlight-android/releases) 页面下载最新版本
2. 安装 APK 文件
3. 按照应用内指引完成设置

#### 方式二：从源码编译

```bash
# 克隆仓库
git clone https://github.com/qiin2333/moonlight-android.git
cd moonlight-android

# 编译项目
./gradlew assembleRelease
```

---

### 需要 [Foundation Sunshine](https://github.com/qiin2333/foundation-sunshine) 支持的功能

以下功能需要配合 [Foundation Sunshine](https://github.com/qiin2333/foundation-sunshine)（基地 Sunshine）使用，这是 Moonlight V+ 的增强功能集，提供更强大的串流体验：

- **🎤 麦克风重定向**

  - 将 Android 设备的麦克风音频实时传输到远程主机，实现远程语音通话、语音聊天等功能
  - 支持高质量音频编码，音质清晰，延迟低，让您在串流游戏中也能与队友流畅语音交流
  - 需要 Sunshine 基地版 2025.0720+ 版本支持

- **〰️ 实时码率调整**

  - 在串流过程中动态调整视频码率，无需断开连接即可根据网络状况自动优化画质
  - 网络波动时自动降低码率保证流畅度，网络恢复时自动提升码率获得更好画质
  - 支持手动实时调节，让您随时掌控串流质量与网络带宽的平衡

- **🎮 超级菜单指令**

  - 通过串流菜单发送高级控制指令到远程主机，实现更丰富的交互功能
  - 支持自定义快捷指令、系统控制、应用管理等高级操作
  - 让移动端也能像本地操作一样控制远程主机，提升使用便利性

- **🎨 应用桌面美化**

  - 自动同步远程主机应用图标，打造个性化的应用桌面
  - 支持自定义应用排序、分组管理，让您的游戏和应用库更加美观整洁

- **💻 主机自动优化**
  - 自动检测客户端硬件能力，创建最佳串流环境
  - 双端屏幕策略协商，客户端设置优先，移动设备显示与交互完美适配
  - 客户端状态记忆，告别桌面 DPI 与图标错乱的烦恼

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！感谢每一位贡献者！

### 贡献者

- [@cjcxj](https://github.com/cjcxj) - 按键自定义、触控菜单、无障碍等（V+进化之神）
- [@alonsojr1980](https://github.com/alonsojr1980) - SOC 解码优化（性能优化专家！）
- [@Xmqor](https://github.com/Xmqor) - 手柄瞄准功能（瞄准高手！）
- [@TrueZhuangJia](https://github.com/TrueZhuangJia) - 增强多点触控（搓屏专家！）
- [@WACrown](https://github.com/WACrown) - 最强自定义按键（按键之王！）

## 🙏 致谢

- 基于 [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) 项目（感谢原版！）
- 特别感谢 [Sunshine](https://github.com/LizardByte/Sunshine) 项目团队

---

<div align="center">
  <sub>如果这个项目对您有帮助，请给我们一个⭐ ！</sub>
</div>
