<div align="center">
  <img src="./app/src/main/res/drawable/vplus.webp" width="100" alt="Moonlight V+ Logo">

  # Moonlight V+

  [![GitHub Release](https://img.shields.io/github/v/release/qiin2333/moonlight-vplus?label=latest&style=flat-square)](https://github.com/qiin2333/moonlight-vplus/releases/latest)
  [![Android](https://img.shields.io/badge/Android-5.0+-34A853?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/about/versions)
  [![License](https://img.shields.io/badge/license-GPL%20v3-EF9421?style=flat-square)](LICENSE.txt)
  [![GitHub Stars](https://img.shields.io/github/stars/qiin2333/moonlight-vplus?style=flat-square)](https://github.com/qiin2333/moonlight-vplus/stargazers)
  [![Downloads](https://img.shields.io/github/downloads/qiin2333/moonlight-vplus/total?style=flat-square&color=blue)](https://github.com/qiin2333/moonlight-vplus/releases)

  **基于 [Moonlight](https://github.com/moonlight-stream/moonlight-android) 的增强版 Android 游戏串流客户端**

  [English](README_EN.md) | 中文

</div>

---

## 截图

<div align="center">
  <img src="https://github.com/user-attachments/assets/bb174547-9b0d-4827-81cf-59308f3cfa9e" width="640" alt="主界面">
  <br/>
  <img src="https://github.com/user-attachments/assets/5e227bab-afaa-4452-ae38-ac2cc9d22988" width="240" alt="游戏列表">
  <img src="https://github.com/user-attachments/assets/9101bf19-782e-4c6f-977f-34b138b93990" width="240" alt="串流界面">
  <img src="https://github.com/user-attachments/assets/5046dd58-7013-494e-9f17-26e4de56a7dd" width="240" alt="设置界面">
</div>

## 与上游 Moonlight 的区别

Moonlight V+ 在 [moonlight-android](https://github.com/moonlight-stream/moonlight-android) 的基础上增加了大量实用功能，同时保持与原版串流协议的完全兼容。

| 类别 | 功能 | 说明 | 起始版本 |
|------|------|------|:--------:|
| **串流** | 超高刷新率 | 解锁 144 / 165 Hz，最高 800 Mbps 码率 | |
| | HDR / HLG | 自动加载设备专属 HDR 校准文件，支持 HLG | `12.6.6` |
| | 自定义分辨率 | 任意分辨率、宽高比、不对称分辨率 | |
| | 多场景预设 | 一键保存/切换不同游戏串流配置 | `12.3` |
| **输入** | 自定义按键 | 拖动/缩放/隐藏，支持组合键、连发、手柄瞄准 | `12.3.3` |
| | 多配置档案 | 按键布局支持多配置实时切换 | |
| | 轮盘按键 | 轮盘分区自定义按键绑定 | `12.3.7` |
| | 增强触控 | 触控笔、手写笔、多点触控、触控板模式 | `12.3.10` |
| | 体感辅助 | 陀螺仪体感瞄准 / 视角，灵敏度可调 | `12.3.3` |
| | 多手柄 | Xbox / PS / Switch / 国产手柄自动识别 | `12.5.3` |
| **界面** | 应用桌面美化 | 缩略图背景同步、自定义排序 | |
| | 功能卡片 | 自定义快捷操作、快捷指令、性能面板 | |
| | 实时码率调节 | 菜单内直接调节码率，无需断开连接 | `12.3.10` |
| | 悬浮球 | 手势动作配置，快捷交互入口 | `12.7.3` |
| | QR 配对 | 扫码快速配对主机 | `12.7.4` |
| **串流增强** | 外接显示器 | 一键副屏通道，旋转双向同步 | `12.6.5` |
| | 不断开连接 | 切换应用无需重新建立串流 | `12.6.6` |
| | 多屏幕选择 | 选择主机屏幕进行串流 | `12.5.0` |
| **监控** | 性能覆盖层 | 帧率、1% Low 帧、码率、延迟、丢包等 | `12.4.1` |
| **音频** | 麦克风重定向 | 远程语音通话（需配合 Foundation Sunshine） | `12.3.12` |
| | 7.1.4 环绕声 | Atmos 空间音频支持 | `12.7.4` |
| | 音频震动 | 实时低频能量驱动触觉反馈（设备 / 手柄 / 双路） | `12.7.0` |
| | | 三种场景模式：游戏（持续低频）、音乐（节拍脉冲）、自动识别 | |

想按使用场景上手？请看 [Moonlight V+ 特色功能指引](docs/VPLUS_FEATURES.md)，从王冠配置、Foundation Sunshine 联动、显示器、触觉与插帧中选择适合自己的入口。

## 快速开始

### 系统要求

- Android 5.0+ (API 22)
- 支持 HEVC / AV1 硬解的设备（推荐）
- 局域网 5 GHz Wi-Fi 或有线连接
- 推荐运行 [Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine) 的主机；标准串流也兼容 [Sunshine](https://github.com/LizardByte/Sunshine)，旧版 NVIDIA GameStream 可供已有设备尝试

### 安装

从 [Releases](https://github.com/qiin2333/moonlight-vplus/releases/latest) 下载最新 APK，安装后按应用内引导完成配对即可。

首次串流建议从 1080p、60 FPS、H.264 或自动编码、关闭 HDR、10–20 Mbps 开始，主机尽量使用有线网络。确认稳定后，再逐项提高分辨率、帧率、码率并启用 HEVC / AV1、HDR 等功能。

### 选择主机端

- **Foundation Sunshine（首推）**：与 Moonlight V+ 配套，提供标准串流及其支持的 V+ 扩展功能，例如麦克风重定向和主机显示控制；具体功能取决于主机端版本。
- **Sunshine**：可用于标准 Moonlight 串流；部分 V+ 扩展功能需要 Foundation Sunshine。
- **NVIDIA GameStream**：供仍在使用旧主机的用户尝试兼容；新安装建议选择 Foundation Sunshine。

### 遇到问题

- 黑屏或解码崩溃：先切换 H.264、关闭 HDR、降低分辨率与帧率，再重新连接。
- 卡顿或提示连接慢：先降低码率，再查看性能覆盖层的丢包与延迟波动，并检查 Wi-Fi。
- Android TV / 盒子：先验证 1080p60 H.264，再尝试 HEVC、AV1、HDR 或高刷新率。
- 外网或虚拟局域网：先确认本地局域网串流正常，再排查 EasyTier、Tailscale 等网络路径。

更多步骤见 [常见问题](FAQ.md) 和 [功能使用指南](docs/USER_GUIDE.md)。

从头安装请看 [Foundation Sunshine 首次连接教程](docs/GETTING_STARTED.md)；需要确认某项增强功能的主机端条件，请看 [兼容矩阵](docs/COMPATIBILITY.md)。

### 从源码编译

先安装 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 28.2.13676358 和 CMake 3.22.1。项目还需要两个 Git 子模块及单独的 [audio-haptics SDK](https://github.com/AlkaidLab/moonlight-audio-haptics)；其版本以 [CI 配置](.github/workflows/android-ci.yml) 为准。

```bash
git clone --recurse-submodules https://github.com/qiin2333/moonlight-vplus.git
cd moonlight-vplus
git clone https://github.com/AlkaidLab/moonlight-audio-haptics.git ../moonlight-audio-haptics
git -C ../moonlight-audio-haptics checkout b3f97c3bb7500ea7b1985aea568e5c7b40308d3b
./gradlew :app:assembleNonRootDebug -PaudioHapticsSdkDir="$PWD/../moonlight-audio-haptics"
```

Windows PowerShell 请将最后一行改为 `.\gradlew.bat :app:assembleNonRootDebug -PaudioHapticsSdkDir="$PWD/../moonlight-audio-haptics"`。正式发布还需签名配置；缺少 `app/google-services.json` 时 Firebase 服务不会正常上报，普通本地构建不需要把该文件提交到仓库。

## Foundation Sunshine 增强功能

以下功能需要搭配 **[Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine)** 使用：

| 功能 | 说明 | 主机端要求 |
|------|------|----------|
| 麦克风重定向 | 设备麦克风音频实时传输至主机 | Foundation Sunshine 2025.0720+ |
| 实时码率调整 | 串流中动态调节码率，网络波动自动适应 | — |
| 超级菜单指令 | 从串流菜单向主机发送高级控制指令 | — |
| 应用桌面美化 | 自动同步主机应用图标，自定义排序与分组 | — |
| 主机自动优化 | 自动协商分辨率/DPI、适配触屏键盘、状态记忆 | — |

未列出最低版本的功能仍需核定最早可用的发布包；版本依据与核验方法见 [兼容矩阵](docs/COMPATIBILITY.md)。备份、恢复及配置分享的边界见 [备份与迁移指南](docs/BACKUP_AND_MIGRATION.md)。

## 贡献

欢迎提交 Issue 和 Pull Request！

提交问题前请先阅读 [常见问题](FAQ.md)；有关统计和崩溃报告的数据处理见 [隐私说明](PRIVACY_POLICY.md)。

### 贡献者

| 贡献者 | 方向 |
|--------|------|
| [@cjcxj](https://github.com/cjcxj) | 按键自定义、触控菜单、无障碍 |
| [@alonsojr1980](https://github.com/alonsojr1980) | SoC 解码优化 |
| [@Xmqor](https://github.com/Xmqor) | 手柄瞄准 |
| [@TrueZhuangJia](https://github.com/TrueZhuangJia) | 增强多点触控 |
| [@WACrown](https://github.com/WACrown) | 自定义按键系统 |

## 致谢

- [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) — 上游项目
- [Sunshine](https://github.com/LizardByte/Sunshine) — 开源串流主机端

## 许可证

本项目基于 [GPL v3](LICENSE.txt) 许可证开源。

---

<div align="center">
  <sub>觉得有用？给个 ⭐ 支持一下吧！</sub>
</div>
