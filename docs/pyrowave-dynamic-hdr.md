# PyroWave 动态 HDR 帧交付与应用内映射

PyroWave 使用 Sunshine 从桌面画面生成的 HDR10+、HDR Vivid 和 Dolby Vision RPU 子集，
在 Vulkan 图像转换中进行逐帧亮度映射，再输出 PQ 或 HLG。此路径消费动态元数据，
不调用厂商原生 Dolby Vision 显示引擎，也不使 PyroWave 成为标准 Dolby Vision HEVC 码流。

## 格式与生成范围

| 用户选择 | 主机动态类型 | 基础信号与输出 | 消费范围 |
| --- | ---: | --- | --- |
| HDR10+ → PQ | 1 | BT.2020/PQ、10-bit | Application 1 单窗口，maxSCL、mean、九项亮度分布；5%/10% 保留值按合同验证 |
| HDR Vivid → PQ | 2 | BT.2020/PQ、10-bit | system_start_code=1，min/average/variance/max 四个 PQ 统计字段 |
| HDR Vivid → HLG | 3 | BT.2020/HLG、10-bit | 同上，额外使用主机 HLG 名义峰值进行 OOTF 还原 |
| Dolby Vision 8.1 → PQ | 4 | BT.2020/PQ、10-bit | identity polynomial、CM2.9 L1/L5/L6、scene refresh 与 CRC |
| Dolby Vision 8.4 → HLG | 5 | BT.2020/HLG、10-bit | 同一桌面 RPU 统计子集，按协商的 HLG 基础信号解释像素 |

所有模式保持 4:2:0，并支持用户选择的 limited/full range。基础静态 HDR10/HLG 选项
不隐式请求动态格式，SDR 不启用动态处理。没有独立 SDR 10-bit、4:4:4 或 4:2:2 选项。

解析器只接受上述生成子集。不处理 HDR10+ 多窗口、空间峰值网格、authored curves
或饱和度映射，不处理 Vivid authored tone curves，也不处理 Dolby trim blocks、Profile 5/7、
enhancement layer 或任意影片 RPU。遇到不支持的语法不会假装成功。

## 连接与能力

客户端复用现有 HDR 模式设置，按 PQ/HLG 基础能力筛选 PyroWave 动态选项，不要求
MediaCodec Dolby 解码器。非 PyroWave 的设置列表及解码策略继续按原有厂商能力筛选。

连接工作线程使用实际尺寸、range、Surface、动态格式和目标峰值创建候选 Vulkan 会话。
预检成功后才声明 `DYNAMIC_HDR_MAPPING`，通过现有 `dynamicHdrCaps`、`dynamicHdrPreference`
和 `X-SS-Dynamic-HDR` 确认唯一格式。HDR10/PQ 沿用静态 HDR 的扩展要求；HLG 使用
HLG swapchain，不把缺少厂商动态扩展误判成具备原生动态输出。

Sunshine 需要开启 HDR 亮度分析，关闭时在 ANNOUNCE 拒绝动态请求。应用开启 RTX HDR 时
继续保留 DV 8.4/HLG 的互斥规则，使用 PQ 类型或关闭该应用增强功能。Surface 预检失败或
动态 ANNOUNCE 被拒绝时报告连接失败；用户可选择基础 HDR 或其他 codec 后重新连接。
初始能力协商沿用 H.264 兼容兜底时，客户端提示实际格式；这不表示 H.264 支持本合同的动态 HDR。

DV 生产器还要求主机提供有效的源 mastering 峰值；缺失时不伪造 L6，而是拒绝建立动态
编码器。HLG 信号未报告峰值时使用编码转换原有的 1000-nit 名义值，该值不冒充 mastering 快照。

## 同帧调用链

~~~mermaid
flowchart LR
    A[Sunshine 图像处理后的逐帧统计] --> B[HDR10+ Vivid 或 RPU 生产器]
    B --> C[同帧 protected TLV 加 codec bytes]
    C --> D[Frame Envelope 与块级 FEC]
    D --> E[common-c 完整重组]
    E --> F[DECODE_UNIT bufferList 与 pyrowaveMetadata]
    F --> G[callbacks 与 MoonBridge 专用 PyroWave 回调]
    G --> H[PyrowaveDecoderSession submit]
    H --> I[JNI 与 PyrowaveVulkanDecoder]
    I --> J[解析并生成当前帧亮度 LUT]
    J --> K[GPU YUV 转换与亮度映射]
    K --> L[PQ 或 HLG swapchain present]
~~~

动态元数据与码流由同一个 decode-unit 分配拥有，直到 `LiCompleteVideoFrame()` 才释放。
JNI 将元数据区复制到当前 Java 回调的 byte array，native 在同步 submit 内消费；没有
“全局最近动态元数据”回调。丢失、过期、替换的帧同时丢弃其动态载荷。

完整元数据可以跨 DATA 包，参与相同的 FEC。FRAME_HEADER 的单包副本不是权威数据，
必须从受保护 DATA/PARITY 区取得完整 TLV。动态 TLV 必须匹配协商格式并标记
`PROTECTED | REQUIRED`；缺失、重复、截断、错前缀、CRC 错误或未知 required 类型拒绝该帧。

## 像素映射

解析器将本帧统计转换为 nits，依据内容峰值、平均/中位亮度及 PQ 分布生成
256 项单调亮度 LUT。映射保留暗部、压缩超过目标显示峰值的亮部，不放大亮度。
不同统计会改变当前帧 LUT；没有 metadata 不能复用另一帧 LUT。

客户端先做原有 limited/full YUV 归一化与 BT.2020 转换，再在 GPU 上按 RGB 最大分量
执行保持色相比例的亮度压缩。PQ 直接转换为绝对亮度；HLG 使用主机发送的名义峰值
和 BT.2100 system gamma 还原线性显示光，映射后按目标峰值重新编码 HLG。

目标峰值来自用户的亮度覆盖设置，否则使用目标显示器报告的有效峰值；未报告时采用
500 nits 的应用映射目标。这不是主机 mastering 元数据，不能写回源 `SS_HDR_METADATA`。
此算法是应用侧桌面场景适配，不承诺与厂商 Dolby/Vivid 引擎或影片 authored mapping 等价。

小型 LUT 使用 host-visible storage buffer；更新后处理非 coherent flush，并在转换提交中
使用 HOST_WRITE → COMPUTE_SHADER_READ barrier。上一帧 Fence 完成后才复用缓冲区，
图像平面仍留在 GPU，不增加逐帧 YUV CPU 读回。

## 静态元数据与生命周期

`SS_HDR_METADATA` 继续走控制通道，原生 `color_metadata` 继续只描述颜色信号。
应用映射保存有效主机快照；向输出 swapchain 提交时，亮度字段按映射后目标约束，
色度/white point 保留原值。源快照不被覆盖，缺失或非法快照不伪造 mastering 数据。

Surface 重绑、extent 或分辨率变化、decoder recovery 重建资源并恢复会话格式、目标峰值
和有效静态快照；旧帧动态状态清空，下一帧必须携带自己的 payload。Resume 通过新连接
重新协商，不依赖旧 handle。基础信号冲突或连续呈现失败按现有有界恢复结束连接。

成功消费 metadata 并提交 present 后，性能信息才显示“格式 → PQ/HLG”，详细诊断标记
`application-mapped`。仅协商、解析或创建 swapchain 不能报告厂商原生动态 HDR。

## 验证入口

- Sunshine `pyrowave_unit_tests`：生产器 golden、实际发布函数、跨包 metadata 和 FEC 恢复。
- common-c `pyrowave-decode-unit-tests`：实际队列中的同帧所有权与 metadata 校验；使用静态 common-c 构建。
- `framegen/src/test/cpp`：客户端生产解析器、固定主机样本、非法 TLV/RPU 和 LUT 数值边界。
- Android 单元测试：预检、同帧传参、恢复/尺寸重建、实际消费状态和传统 HDR 策略。

真实黑位、亮部、色域、场景切换、Surface 重建、Resume 和设备丢失需用目标设备验收；
编译和数值测试不证明厂商 compositor 或面板的最终输出效果。

## 许可

本文档及新增项目实现采用 GNU GPLv3（`GPL-3.0-only`），见 [LICENSE.txt](../LICENSE.txt)。
第三方代码与依赖保留各自的许可；应用映射不代表 Dolby、HDR10+ 或 Vivid 的认证或背书。
