<div align="center">
  <img src="./app/src/main/res/drawable/vplus.webp" width="100" alt="Moonlight V+ Logo">
  
  # Moonlight V+ 威力加强版
  
  [![Version](https://img.shields.io/badge/version-12.3.1-blue.svg)](https://github.com/qiin2333/moonlight-android/releases/tag/shortcut)
  [![Android](https://img.shields.io/badge/Android-5.0+-green.svg)](https://developer.android.com/about/versions)
  [![License](https://img.shields.io/badge/license-GPL%20v3-orange.svg)](LICENSE.txt)
  [![Stars](https://img.shields.io/github/stars/qiin2333/moonlight-android?style=social)](https://github.com/qiin2333/moonlight-android)
  
  **基于 Moonlight 的增强版 Android 串流客户端** 🎮
  
  *让您的 Android 设备成为强大的游戏串流终端！Gawr！* ✨
</div>


## 📱 应用截图展示

<div align="center">
  <img src="https://github.com/user-attachments/assets/10800322-d8ab-4419-bd05-5fc37fd4c8f3" width="360" alt="主界面">
  <img src="https://github.com/user-attachments/assets/5e227bab-afaa-4452-ae38-ac2cc9d22988" width="360" alt="游戏列表">
  <img src="https://github.com/user-attachments/assets/c755d228-d9f5-4068-ae6c-c3f8ea0a0f2f" width="360" alt="串流界面">
  <img src="https://github.com/user-attachments/assets/5046dd58-7013-494e-9f17-26e4de56a7dd" width="360" alt="设置界面">
</div>


## ✨ 特性

### 🎯 核心功能
- **高性能串流**: 解锁 144/165Hz 刷新率，支持最高 800Mbps 码率，动态调节！
- **HDR 支持**: 完整 HDR 内容串流，自动启用设备关联的 HDR 校准文件，串流画质上限！
- **自定义分辨率**: 支持自定义分辨率和不对称分辨率串流，想怎么玩就怎么玩！
- **多场景预设**: 快速切换不同游戏场景的串流设置，右下角鲨牙长按就能保存哦！

### 🎮 游戏体验
- **增强触控**: 支持触控笔和多点触控，可切换触控板模式，触控体验超顺滑！
- **自定义按键**: 可移动按键布局，支持手柄瞄准，按键想放哪里就放哪里！
- **快捷操作**: 一键睡眠、常用 PC 指令一键操作！
- **性能监控**: 实时显示串流性能指标，支持拖动位置，性能一目了然！

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

## 📋 更新日志

### v12.2.6 最新版本
- 🎮 **发送特殊按键可自定义**，并支持键盘选取
- 🖱️ **添加切换触控菜单**，可切换为触控板模式
- ⚡ **菜单集成实时码率调节面板**，调节更快速

### v12.2
- 🎨 **重构游戏菜单**，与 Sunshine 应用编辑页风格统一
- 🔗 **优化连接体验**，分享串流最佳实践
- 🎤 **快捷功能增强**：麦克风按钮显示控制 + 实时码率调节
- 📱 **更友好的主机与APP详情展示**
- 🌍 **补全设置菜单英文翻译**

### 2025/07/26
- 🔧 **修复部分 Rockchip SOC 不能开启 HEVC HDR 的问题**
- ✏️ **开启增强式多点触摸后触控笔也可正常使用**
- 🎤 **优化麦克风长时间使用延迟增大的问题**

### 2025/07/21
- 🎤 **支持麦克风重定向**，需 Sunshine 基地版 2025.0720+

### 2025/07/17
- 📺 **外接屏幕支持**：可选择复制或沉浸式投屏
- 🔋 **沉浸式投屏性能覆盖层**：本机屏幕展示并添加实时电量
- 🏷️ **支持 sunshine 端修改客户端配对名字**

### 2025/07/06
- ⚡ **优化部分联发科 SOC 的显示解码时间** (天玑9300以下可能有效果)
- 🔧 **修复 ColorOS 串流 HDR 内容时无法正确激发亮度**

### 2025/06/22
- 📊 **盯帧能力升级**：性能覆盖层可配置展示项目、位置、方向
- 🖱️ **串流中可拖动性能覆盖层位置**

### 2025/06/01
- ⌨️ **外接物理键盘使用 ESC 键不首先弹出返回菜单**
- ⚡ **优化部分骁龙 SOC 的显示解码时间** (8Gen2+)

### 2025/04/08
- 🎮 **可移动按键增加手柄瞄准**
- 📈 **非线性码率调整**
- 🔧 **修复关闭增强触摸恢复经典鼠标模式**

### 2025/03/24
- 📱 **反转分辨率（竖屏）功能**
- 🎯 **串流画面位置设置**，支持八个方向加偏移量

### 2025/02/23
- 💾 **增加多场景预设切换能力**：右下角鲨牙长按保存当前预设，点击应用对应预设


## 🔧 高级功能（隐藏技能）

### 需要 Sunshine 基地版支持的功能
- 🎤 麦克风重定向
- 🎮 超级菜单指令
- 🎨 应用桌面美化
- 🎯 自定义分辨率串流
- 🔋 串流自动启用 HDR 校准文件


## 🤝 贡献

欢迎提交 Issue 和 Pull Request！感谢每一位贡献者！

### 贡献者
- [@cjcxj](https://github.com/cjcxj) - 特殊按键自定义、触控菜单（xxx！）
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
