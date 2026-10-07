# PyroWave 解码器接入设计

本设计说明 Moonlight V+ 如何复用现有连接、视频回调、性能统计和 Surface 生命周期接入 PyroWave。接口与行为要求见[客户端接入合同](pyrowave-client-contract.md)，GPU 同步细节见[Vulkan 同步设计](pyrowave-vulkan-synchronization-design.md)。

## 1. 组件与职责

| 组件 | 职责 |
| --- | --- |
| [Game](../app/src/main/java/com/limelight/Game.kt) / [NvConnection](../app/src/main/java/com/limelight/nvstream/NvConnection.kt) | 构造当前会话策略，在连接工作线程中完成 Surface 预检与协商 |
| common-c | 协商合同、解析封套、块级恢复、完整帧重组、控制消息与视频交付 |
| [MediaCodecDecoderRenderer](../app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.kt) | 共享视频回调入口，按格式分派，处理尺寸、HDR 状态和性能统计 |
| [PyrowaveDecoderSession](../app/src/main/java/com/limelight/binding/video/PyrowaveDecoderSession.kt) | 串行管理 native handle、Surface generation、metadata 快照和有界恢复 |
| [PyrowaveVulkanDecoder](../framegen/src/main/cpp/pyrowave_vulkan_decoder.cpp) | Vulkan device、YUV 平面、转换 shader、swapchain 和静态 HDR 呈现 |
| [pyrowave_decoder_bridge.cpp](../app/src/main/jni/moonlight-core/pyrowave_decoder_bridge.cpp) | SDR CPU staging：解码后读回 YUV、CPU 转换和原生窗口输出 |
| [pyrowave-runtime](../pyrowave-runtime/README.md) | 构建并打包固定子模块的 C API 运行库，不增加 UI 或服务 |

PyroWave 对象独立于帧生成 context。GPU bridge 位于 `framegen` 模块并不表示 PyroWave 经过插帧器，也不允许 framegen reset 释放活动 PyroWave 会话。

## 2. 连接与后端选择

~~~mermaid
flowchart TD
    A[当前会话设置] --> B{明确选择 PyroWave}
    B -->|否| C[原有 codec 协商]
    B -->|是| D[等待目标 Surface]
    D --> E[连接工作线程完整预检]
    E --> F{GPU 呈现可用}
    F -->|是| G[PyroWave Vulkan Surface]
    F -->|否且为 SDR| H[尝试 CPU staging]
    F -->|否且为 HDR| I[报告连接失败]
    H --> J{后端创建成功}
    J -->|是| K[按 PyroWave 合同协商]
    J -->|否| I
    G --> K
~~~

预检使用与实际创建相同的尺寸、HDR 模式、范围和 Surface，创建候选会话后释放。只探测默认 Vulkan 设备或库的符号，不足以证明目标 Surface 可以呈现。

Vulkan 呈现后端检查 Vulkan 1.3、graphics/compute/present queue、subgroup 能力、storage image、所需 Surface 格式和 HDR 扩展。可用性结果还受实际设备和窗口能力限制。

实现的分配边界：GPU 路径宽高均为正偶数且不大于 8192；CPU staging 宽高均不大于 4096、总像素不超过 4096×2160。边界是资源校验上限，不是对设备性能的承诺。

## 3. 视频数据流

~~~mermaid
flowchart LR
    A[RTP payload] --> B[common-c 封套校验与 FEC]
    B --> C[完整 codec bytes]
    C --> D[PyrowaveDecoderSession]
    D --> E{呈现后端}
    E -->|Vulkan| F[GPU YUV 平面]
    F --> G[GPU YUV 转换]
    G --> H[Android swapchain]
    E -->|SDR staging| I[YUV 读回与 CPU 转换]
    I --> J[ANativeWindow 输出]
~~~

GPU 路径不将 YUV 图像映射到 CPU，也不通过 `ANativeWindow_lock` 写入帧。解码与颜色转换共享一个 Vulkan device，由 swapchain 向 Android Surface 呈现。

CPU staging 不是纯 CPU 解码器：PyroWave 仍使用 Vulkan 解码，然后读回平面并在 CPU 上转换为窗口像素。它是 SDR 后端选择，不能代表 HDR 呈现，也不能宣称与 GPU 路径具有相同复制成本。

PyroWave 提交可能等待 GPU 和窗口资源，因此不声明 `CAPABILITY_DIRECT_SUBMIT`；接收线程不直接承担阻塞解码。传统 MediaCodec 的 direct-submit 策略不由该路径修改。

## 4. 颜色转换与 HDR

### 4.1 颜色合同

- SDR 使用 BT.709 的 8-bit YUV420，输出 `R8G8B8A8_UNORM` 和 `SRGB_NONLINEAR`。
- HDR10/PQ 和 HLG 使用 BT.2020 的 10-bit YUV420，输出 `A2B10G10R10_UNORM_PACK32`，分别选择 ST2084 或 HLG 色彩空间。
- limited/full range 都来自当前会话参数，并由 shader 统一解释。
- 所选 swapchain 格式必须匹配 shader storage image 声明，不能随意接受列表首项或把 BGRA 当作 RGBA。

输出 shader 使用协商的 YCbCr 矩阵和范围。PQ/HLG 转换保留原有 transfer 信号，不在此阶段把 HDR tone-map 成 SDR。SDR 与 HDR 使用各自匹配的 shader 和输出格式。

### 4.2 静态呈现元数据

码流中的 `pyrowave_color_metadata` 描述 primaries、transfer、YCbCr transform、range 和 chroma siting。`SS_HDR_METADATA` 是独立的显示 mastering/content-light 快照，两者不得合并。

`MediaCodecDecoderRenderer.setHdrMode()` 保存并检查主机快照，`PyrowaveDecoderSession` 复制载荷并通过 JNI 传入 native。native 校验、保存结构化快照，并使用 `VkHdrMetadataEXT` 应用到当前 swapchain。

元数据更新与提交、Surface 重建共用 native mutex，避免更新已销毁的 swapchain。快照保留在会话中，swapchain 重建后重新应用；decoder handle 重建时 Kotlin 同样重新提交快照。

`maxFullFrameLuminance` 保留在快照中；`VkHdrMetadataEXT` 没有该字段，不能把它写入 `maxFrameAverageLightLevel`。后者只接收 MaxFALL。

缺失或非法静态快照不伪造默认 mastering 值，保留当前协商的 PQ/HLG 色彩空间。HDR10 要求 metadata 扩展和入口可用；HLG 可以在扩展缺失时使用 HLG 色彩空间，但不把这种情况称为完整静态元数据应用。

传统 MediaCodec 的 `KEY_HDR_STATIC_INFO` 路径仍保留自身逻辑，不使用 PyroWave 的 native 呈现接口。

## 5. 线程与对象所有权

| 状态 | 所有者与同步方式 |
| --- | --- |
| 当前 Surface / generation | Surface 回调发布不可变快照，不在 Activity 回调中初始化 Vulkan |
| native handle、尺寸、恢复计数和 metadata | `PyrowaveDecoderSession` 的会话锁 |
| Vulkan 对象、queue 操作和 metadata 应用 | `PyrowaveVulkanDecoder::impl::mutex` |
| `ANativeWindow` | JNI 提供临时引用，native 会话持有独立 acquire/release 引用 |
| borrowed PyroWave device | native 保持创建描述和 Vulkan device 有效，先销毁 decoder 再销毁 device |

解码工作线程在下一次 `submit()` 前检查 Surface generation。新 Surface、同一窗口的尺寸变化和窗口销毁都必须经过这个交接，不按 `ANativeWindow*` 指针相等直接判定资源可复用。

连接失败回调在 Kotlin 会话锁释放后触发，避免连接清理回入 `destroy()` 时死锁。会话销毁后不得继续向旧 handle 提交。

## 6. 生命周期

| 事件 | 处理 |
| --- | --- |
| 首次连接 | 预检后创建后端，绑定 Surface，提交当前 metadata，再处理视频 |
| Surface 暂不可用 | 工作线程释放旧呈现资源并等待新目标，不把它当作成功显示 |
| Surface 重绑定或 extent 改变 | 重建 Vulkan 呈现资源，重新应用会话快照 |
| 分辨率变化 | 先记录新尺寸，再创建替换 decoder；失败后不能继续用旧尺寸消费新帧 |
| 静态 metadata 变化 | 更新当前 swapchain，不改变码流信号模式 |
| SDR/HDR 模式冲突 | 结束当前连接，新的连接重新协商 |
| Resume / 新连接 | 使用新的连接参数和控制消息，不依赖旧 handle 的隐含颜色状态 |
| 断开或销毁 | 串行停止提交并释放后端；不得重用旧的 GPU 同步对象 |

创建替换 handle 时先使新对象可用，再发布替换并销毁旧对象。Surface 不可用与真实 backend 故障分开处理，不为 UI 生命周期中断耗尽恢复次数。

## 7. 故障处理

网络层未完成的帧在 common-c 丢弃。native 收到无法形成完整可解码图像的数据时返回 `PYROWAVE_SUBMIT_FRAME_DROPPED`，只清理该帧，不销毁 decoder，也不清除上一帧显示内容。

native 创建、解码、同步、颜色校验或呈现失败进入有界恢复。恢复重建当前 codec 的后端，沿用尺寸、HDR 模式、范围和 metadata；连续成功处理一帧才重置恢复预算，默认连续三次失败触发一次连接终止通知。

SDR GPU 后端可以切到 CPU staging；HDR 不走该路径。恢复失败结束当前连接，不在同一活动连接中把 PyroWave bytes 投递给 MediaCodec，也不承诺自动重新连接或改选另一种编码器。

日志区分运行库/API、创建阶段、颜色合同、GPU decode、同步、转换和 present 错误。重复同类错误可抑制输出，错误返回不能伪装成成功帧。

## 8. 性能指标

GPU 路径分别报告解码提交段和转换/呈现提交段的客户端线程耗时：

- 解码段包含码流 push、ready 检查、颜色校验和 GPU decode 提交。
- 转换/呈现段包含获取 swapchain 图像、同步等待、录制与提交转换、Fence 等待和 present 调用。
- 第一项不是纯 GPU decode 时间；两项之和也不是面板完成扫描输出的时间。

common-c 提供当前帧的 host processing latency，renderer 将 PyroWave 的帧数、处理时间和丢帧统计接入现有性能显示。不同 codec 的统计项必须按实际含义解释，不能仅凭 API 名称断言低延迟收益。

## 9. 构建与许可

应用依赖 `pyrowave-runtime`，模块通过 CMake 直接构建 `third-party/pyrowave` 及其固定依赖。运行库由 Android Gradle Plugin 打包，源目录不放手工生成的 `.so`；详见 [runtime 构建说明](../pyrowave-runtime/README.md)。

本文档采用 GNU GPLv3（`GPL-3.0-only`），协议全文见 [LICENSE.txt](../LICENSE.txt)。运行库的上游 MIT 与 AlkaidLab GPL-3.0-only 部分按 [PyroWave NOTICE](https://github.com/AlkaidLab/pyrowave/blob/43c8316c31f9bbe4b26822e70ff73254ebba7fcc/NOTICE.md) 保留；不修改 Granite、Vulkan Headers、volk 等依赖的原有许可和声明。
