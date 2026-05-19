#pragma once

#include <android/hardware_buffer.h>

#include <cstdint>

namespace FramegenPipeline {

// 阶段 3.2 骨架：先做设备能力探测，不改现有 3.1 帧流路径。
// 返回 true 表示 Vulkan AHB 导入能力满足后续接线最低要求。
bool ensureVulkanAhbReady(AHardwareBuffer* ahb, int width, int height, int format);

// 阶段 3.2：创建 LSFG 的 AHB 共享上下文（owned input/output AHB）。
// 当前只 bootstrap 上下文，不提交帧、不输出到 SurfaceView。
bool ensureContextBootstrapped(AHardwareBuffer* decoderAhb, int width, int height, int format);

// 阶段 3.3a-ii：测试性 import decoder AHB 成 VkImage 然后立刻销毁。
// 调用方应自行节流（例如每 60 帧一次），返回 false 表示 import 失败。
// 仅用于验证 import 路径稳定，无 GPU 工作提交。
bool probeImportDecoderAhb(AHardwareBuffer* decoderAhb);

// 重置探测状态（在流重建时调用，便于重新打印一次探测日志）。
void reset();

// 阶段 3.3a-iii.b.1 修正：由 Java 在 bootstrap 之前传入是否 HDR 流，
// 决定 LSFG_3_1::initialize 的 isHdr 参数（影响 LSFG 内部颜色空间处理）。
void setHdrEnabled(bool enabled);

} // namespace FramegenPipeline
