# Moonlight V+ 12.11.10.dv-preview — Dolby Vision 串流预览版

> ⚠️ **功能预览版**：Dolby Vision Profile 8.1 串流链路首次公开。主机端需 Foundation Sunshine 新构建并开启实验开关；手机端 DV 面板输出受厂商策略限制（见下）。

## 新增：Dolby Vision 8.1 串流

完整链路：能力探测 → RTSP 协商 → HEVC Main10 + RPU 注入 → 设备原生 `video/dolby-vision` 解码器 → 终端 Dolby 引擎 tone mapping。

### 使用方法

1. **主机端**（Foundation Sunshine，需包含 DV 管线的构建）：配置开启 `dolby_vision = enabled`，显示器 HDR 开启，NVENC/AMD GPU
2. **客户端**：设置 → 启用 HDR → HDR 模式选择「Dolby Vision 8.1（实验性）」
3. 性能覆盖层 HDR 格式显示 `DV` 即协商成功；解码器回退时自动降至 HDR10，画面不中断

### 已验证

- OPPO 真机（Qualcomm）：协商/注入/解码全链路 ✓；含 RPU 码流对 HEVC 解码器零影响 ✓；失败逐级优雅回退 ✓
- RPU 布局经 dovi_tool 2.3.3 交叉验证；主机端 450 项测试全绿

### 已知限制

| 限制 | 说明 |
|---|---|
| **手机端 DV 面板输出** | OPPO/一加等厂商对第三方 App 关闭 DV 显示映射（逐 App 认证白名单）；本预览版在手机上以 HDR10/HDR10+ 呈现，管线就绪等待厂商开放或 Android TV 端点亮 |
| **电视端待验证** | 公开证据表明 Android TV（如 Sony Bravia）对第三方 DV 开放，本版为此准备（dvcC 信号梯子）；点亮判定：电视图像模式切换「杜比视界明亮/柔和」 |
| 编码器 | 仅 NVENC / AMD 硬件编码路径；软件编码自动回退 HDR10 |
| 兼容性 | 与画面补帧互斥（启用补帧时 DV 自动回退静态 HDR10）；旧版主机/客户端完全不受影响 |
