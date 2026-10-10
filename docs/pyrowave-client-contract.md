# PyroWave 客户端接入合同

本文规定 Moonlight V+ 与 Sunshine 之间的 PyroWave 协商、视频交付、颜色解释和呈现接口。实现结构见[解码器设计](pyrowave-decoder-design.md)，GPU 资源交接见[Vulkan 同步设计](pyrowave-vulkan-synchronization-design.md)。

## 1. 合同边界

- PyroWave 原生码流描述图像和颜色信号；common-c Frame Envelope 描述帧、分片、FEC 和帧级运行信息。
- Sunshine 的静态 HDR 呈现元数据沿用 `SS_HDR_METADATA` 控制消息，不放入原生 `color_metadata` 或视频封套。
- H.264、HEVC、AV1 继续使用原有 MediaCodec 和 common-c 视频路径；音频、输入、手柄、USB、剪贴板等通道不由本合同修改。
- 用户明确选择 PyroWave 时优先按该格式连接。初始能力协商未选中 PyroWave、但沿用 common-c 的 H.264 兜底建立连接时，客户端弹窗明确提示实际编码格式，确认后继续串流，不修改用户保存的设置。自动选择模式不声明 PyroWave。
- Surface/设备预检失败、ANNOUNCE 拒绝或运行中 decoder 故障仍按原错误路径处理，不因此新增自动重连或活动串流内的 codec 切换。

包头字段、序列化和恢复规则以公共 [Frame Envelope 协议](https://github.com/qiin2333/moonlight-common-c/blob/mic/docs/pyrowave-frame-envelope.md)及随项目固定的 common-c 实现为准。本文件不另造一份 wire 格式。

## 2. 协商与运行库

| 项目 | 要求 |
| --- | --- |
| Native C API | `101.0.0`（基于上游 `1.0.0`），加载时精确校验 major/minor/patch 和必需函数入口 |
| Frame Envelope | `protocolVersion=2`、`bitstreamVersion=2`、`payloadVersion=3` |
| 服务端声明 | DESCRIBE 中的 `x-ss-pyrowave.*` |
| 客户端声明 | ANNOUNCE 中的 `x-ml-pyrowave.*` |
| 最大内层包 | `maxPacketSize` 包含 64 字节头；还受外层 RTP 包预算约束 |
| 所有模式必需能力 | `REASSEMBLY`、`FRAME_DEADLINE`、`BLOCK_AWARE_FEC`、`FRAME_METADATA` |

客户端还必须声明所选信号模式与 limited/full range 的能力。双方能力交集必须覆盖完整必需集合；版本必须匹配本地合同，不能只比较双方是否相等。整数范围必须在收窄和分配内存之前验证。

`101.0.0` 属于 AlkaidLab fork 的原生 API 版本线，与上游 1.x 隔离；
它不是 Frame Envelope 版本号。客户端不接受上游运行库代替所需的 fork 运行库。

`libpyrowave-shared.so` 由 `pyrowave-runtime` 模块随应用构建和打包。库存在不等于该设备可以解码或呈现；连接前还要验证目标尺寸、Surface、swapchain 格式、shader 和所需扩展。

decoder create-info 与上游 1.0.0 的字段布局一致，不附加 `output_bit_depth`。
GPU 解码通过 R8/R16 image/view format 区分 SDR/HDR 平面；CPU 解码通过
`pyrowave_cpu_buffer.format` 选择输出精度。客户端继续使用单帧 Vulkan 提交，
上游新增的批处理 frame context 和异步 CPU 读回接口不会自动改变呈现时序。

上游 `PWV1Header` 描述文件或容器的全局头，不用于本合同的网络包头。
原生 code=0 的 8 字节 sequence header 保持上游布局；HLG 仍使用双方协商的 code=1 扩展。
运行库 ABI 版本与 Frame Envelope 版本是不同边界，更新前者不改变后者。

## 3. 图像与颜色

| 模式 | `dynamicRangeMode` | 信号 | 位深 / 采样 | `encoderCscMode` limited / full |
| --- | ---: | --- | --- | --- |
| SDR | 0 | BT.709 | 8-bit / 4:2:0 | 2 / 3 |
| HDR10 | 1 | BT.2020 / PQ | 10-bit / 4:2:0 | 4 / 5 |
| HLG | 2 | BT.2020 / HLG | 10-bit / 4:2:0 | 4 / 5 |

颜色范围来自当前会话的 `StreamConfiguration.colorRange`。客户端不为选择 PyroWave 强制改写全局全范围偏好，主机编码与客户端转换必须使用同一范围。

解码器必须逐帧校验原生 color metadata 中的 primaries、transfer、YCbCr transform 和 range 与会话一致，不把不匹配的 PQ、HLG 或范围当作正常帧显示。

本合同不包含独立 SDR 10-bit、4:4:4 或 4:2:2。HDR10+、Vivid PQ/HLG 与 DV 8.1/8.4
的桌面生成子集由应用内消费，输出仍为 PQ/HLG，不代表厂商原生 Dolby Vision 输出；
见[动态 HDR 帧交付与应用内映射](pyrowave-dynamic-hdr.md)。PyroWave 会话不使用帧生成管线。

### 3.1 范围解释

limited-range 输入按对应位深的 nominal range 归一化，再执行 YCbCr 到 RGB 的转换：

| 位深 | Y 偏置 / 跨度 | C 中心 / 跨度 |
| --- | --- | --- |
| 8-bit | 16 / 219 | 128 / 224 |
| 10-bit | 64 / 876 | 512 / 896 |

full-range 输入按码流的全范围解释。HDR 转换保留 PQ 或 HLG 信号，并使用匹配的 Vulkan 色彩空间；不得把有效 HDR 信号当作 SDR 或用错误 range 重新解释。

## 4. 视频交付与恢复

1. common-c 解析封套并核对帧描述、块布局、长度和 metadata 标记。
2. 对缺失 DATA 块先执行组内 XOR 恢复；每组最多 16 个 DATA shard 和一个 PARITY shard，最多恢复一个缺失 DATA shard。
3. 去除填充，解析受保护 metadata，再只将完整 codec payload 交给解码器。
4. 帧的本地接收时限为首包到达后 100 ms；重复包不延长时限，较新帧可以替换旧的不完整帧。

动态 HDR 会话要求额外的 `DYNAMIC_HDR_MAPPING` 能力及唯一动态类型确认。完整 TLV 区随
`DECODE_UNIT.pyrowaveMetadata` 到达专用 PyroWave 回调，与码流共用分配和寿命。动态条目
为 `PROTECTED | REQUIRED`，HLG 动态会话另携带名义峰值；缺失或非法 payload 不能当作
成功，也不能将上一帧 metadata 套到下一帧。

重组上限为 16 MiB。未恢复完整、超时、超大、冲突描述或非法 required metadata 的帧不提交解码；丢失一个独立 FRAME_HEADER 包不阻止从 DATA/PARITY 的重复描述开始重组。

`HOST_PROCESSING_LATENCY` 为受保护的可选运行信息，value 是大端 `uint16_t`，单位为 0.1 ms。它用于当前帧统计，不是颜色或 HDR 呈现元数据；当前帧未携带时不得沿用上一帧的值。

PyroWave 帧内编码不依赖传统 codec 的参考帧失效机制。丢帧与 decoder 故障必须区分，不能因为普通网络丢帧反复销毁 decoder。

## 5. 静态 HDR 呈现元数据

实际控制消息链路为：

~~~text
Sunshine SS_HDR_METADATA
  → common-c ControlStream / setHdrMode
  → callbacks.c / MoonBridge.bridgeClSetHdrMode
  → MediaCodecDecoderRenderer.setHdrMode
  → PyrowaveDecoderSession.setHdrMetadata
  → FramegenInterceptor / JNI
  → PyrowaveVulkanDecoder.setHdrMetadata
  → VkHdrMetadataEXT / vkSetHdrMetadataEXT
~~~

`SS_HDR_METADATA` 由 13 个小端 `uint16_t` 组成，共 26 字节；其字段与 Vulkan 的映射如下：

| 主机字段 | 解码与单位 | Vulkan 字段 |
| --- | --- | --- |
| RGB primaries | 按 RGB 顺序，x/y 除以 50000 | `displayPrimaryRed/Green/Blue` |
| white point | x/y 除以 50000 | `whitePoint` |
| max display luminance | nits | `maxLuminance` |
| min display luminance | 除以 10000，转换为 nits | `minLuminance` |
| MaxCLL | nits，0 表示未提供该值 | `maxContentLightLevel` |
| MaxFALL | nits，0 表示未提供该值 | `maxFrameAverageLightLevel` |
| max full-frame luminance | nits | 保留在快照中；Vulkan 无独立对应字段 |

`maxFullFrameLuminance` 不得冒充 MaxFALL。有效主机值不能被客户端默认值覆盖。

应用内映射保留源快照；向映射后的输出 swapchain 提交时，亮度字段按输出目标约束，
不是覆盖主机原始值。基础静态 HDR 路径不经过该调整。

解析器拒绝不完整载荷、越界色度、非有限或负亮度，以及无效的 mastering 亮度关系。空载荷或全零快照视为缺失；非法快照不应用到 swapchain，也不伪造替代值。MaxCLL/MaxFALL 为零不使其他有效字段失效。

| 情况 | 呈现要求 |
| --- | --- |
| HDR10/PQ | 使用 `VK_COLOR_SPACE_HDR10_ST2084_EXT`；预检必须确认 `VK_EXT_hdr_metadata` 和函数入口可用 |
| HLG | 使用 `VK_COLOR_SPACE_HDR10_HLG_EXT`；有有效快照且扩展可用时应用静态元数据 |
| 缺失或无效快照 | 保持已协商的 PQ/HLG 色彩空间，不伪造 mastering 元数据，不改为 SDR |
| HLG 缺少 metadata 扩展 | 可以使用 HLG 色彩空间，但不得宣称已应用完整静态元数据 |
| HDR10 缺少 metadata 扩展或入口 | 预检失败；明确选择 PyroWave 的连接不静默降级到其他格式 |

`vkSetHdrMetadataEXT` 没有返回值。接口可用且调用完成不等于能够从驱动读取并证明面板的最终映射效果。

## 6. 生命周期与错误返回

会话尺寸、HDR 模式和范围是创建参数。分辨率变化重建 decoder；SDR/HDR 模式与现有会话冲突时结束当前连接，新的连接重新协商。静态 HDR metadata 更新本身不改变码流颜色合同。

| Native `submit` 结果 | 会话处理 |
| --- | --- |
| `0` | 本帧处理及呈现提交成功，清除连续恢复失败计数 |
| `1` / `PYROWAVE_SUBMIT_FRAME_DROPPED` | 丢弃当前帧，保留 decoder、Surface 和上一帧显示内容 |
| 负值 | 进入有界 decoder 恢复；默认连续三次失败后通知连接层终止 |

Surface 暂不可用不是成功显示，也不应消耗普通丢帧的恢复预算。Surface 重绑定、swapchain 重建和 decoder 恢复必须重新应用有效的静态 HDR 快照；新连接通过现有控制通道更新会话状态。

GPU 呈现失败时，SDR 可以使用同一 codec 的 CPU staging 后端；HDR10/HLG 不允许以 8-bit SDR staging 代替。恢复是 decoder 重建，不是在活动连接中自动切换为 HEVC、AV1 或 H.264。

## 7. 许可

本文档采用 GNU GPLv3（`GPL-3.0-only`），协议全文见 [LICENSE.txt](../LICENSE.txt)。PyroWave 上游代码保留 MIT 许可，AlkaidLab 新增和修改部分采用 GPL-3.0-only，详见 [PyroWave NOTICE](https://github.com/AlkaidLab/pyrowave/blob/43c8316c31f9bbe4b26822e70ff73254ebba7fcc/NOTICE.md)。引用接口和协议不改变各第三方组件原有的版权与许可。
