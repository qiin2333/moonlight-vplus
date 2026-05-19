# :framegen — Lossless Scaling Frame Generation 桥接模块

把上游 [`lsfg-vk-android`](https://github.com/FrankBarretta/lsfg-vk-android)（MIT）作为子模块引入，
在 moonlight-android 里提供 AHardwareBuffer → Vulkan compute 的插帧管线。

> **当前状态：阶段 1 — 骨架。** 只验证 CMake 链路 + JNI 装载，无任何业务功能。

## 路线图

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | submodule + CMake 骨架 + 占位 `FramegenInterceptor` | ✅ |
| 2 | MediaCodec → ImageReader 拦截 + 直通显示（验证零拷贝路径不破） | TODO |
| 3 | 接入 `LSFG_3_1::createContextFromAHB`，单倍率（2×）插帧跑通 | TODO |
| 4 | shader 提取：用户 SAF 选 `Lossless.dll`，pe-parse + dxbc → SPIR-V | TODO |
| 5 | UI 开关、GPU 白名单、HDR 互斥、frame pacing 重调 | TODO |

## 许可证

- **本目录**（除 `src/main/cpp/lsfg-vk-android/`）：与 moonlight-android 主仓一致（GPLv3）。
- `src/main/cpp/lsfg-vk-android/`：上游 MIT，详见该目录下 `LICENSE.md`。
- **`Lossless.dll` 永远不入仓**。用户必须自备正版，运行时通过 SAF 选择。

## 设备要求

- Android 10+（API 29+，AHardwareBuffer Vulkan import）
- arm64-v8a
- Vulkan 1.1+ 并支持 `VK_ANDROID_external_memory_android_hardware_buffer`
- 实测稳定：Adreno 7xx+（骁龙 8 Gen 2 及更新）
