# PyroWave 动态 HDR 帧交付与呈现

PyroWave 使用 Sunshine 从桌面画面生成的动态 HDR 元数据，并通过 Vulkan 呈现链路逐帧消费。
Vivid 在设备支持 `VK_HUAWEI_hdr_vivid` 时走原生扩展；HDR10+ 与 Dolby Vision 使用
客户端的受限应用映射 shader，输出仍保持协商的 PQ/HLG 基础信号。PyroWave 不声称自己是
经过 Dolby 或 HDR10+ 认证的硬件解码器，也不改变网络码流的基础颜色合同。

## 格式与生成范围

| 用户选择 | 主机动态类型 | 基础信号与输出 | 消费范围 |
| --- | ---: | --- | --- |
| HDR Vivid（PQ 基础信号） | 2 | BT.2020/PQ、10-bit | system_start_code=1，四个 PQ 统计字段；仅 Vulkan 原生 Vivid |
| HDR Vivid（HLG 基础信号） | 3 | BT.2020/HLG、10-bit | system_start_code=2，四个 PQ 统计字段和 UWA base curve；仅 Vulkan 原生 Vivid |
| HDR10+ | 1 | BT.2020/PQ、10-bit | 解析 ST 2094-40 统计/曲线并在 Vulkan 中应用映射 |
| Dolby Vision 8.1 / 8.4 | 4 / 5 | BT.2020/PQ 或 HLG、10-bit | 解析受支持的 RPU reshape、矩阵和 trim 字段并在 Vulkan 中应用映射 |

所有模式保持 4:2:0，并支持用户选择的 limited/full range。基础静态 HDR10/HLG 选项
不隐式请求动态格式，SDR 不启用动态处理。没有独立 SDR 10-bit、4:4:4 或 4:2:2 选项。

解析器只接受上述生成子集。HDR10+ 只生成和消费单窗口统计、knee/Bezier 及饱和度字段，
不处理多窗口、空间峰值网格或 authored curves；Vivid 不处理 authored tone curves。Dolby
客户端消费 polynomial/MMR 的全部分段曲线，并解析 CM2.9 L1/L2/L5/L6；Sunshine 当前
只生成无增强层的 identity reshape、L1/L5/L6，L2 仅作为兼容接收字段。Profile 5/7、
enhancement layer、NLQ 和其他影片 RPU 仍拒绝。遇到不支持的语法不会假装成功。

## 连接与能力

客户端复用现有 HDR 模式设置。Vivid 菜单项仍要求原生 Vulkan Vivid 能力；HDR10+ 与
Dolby Vision 菜单项要求对应的显示基础信号，并在 PyroWave 会话中使用应用映射路径。
非 PyroWave 的设置列表及解码策略继续按原有厂商能力筛选。

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
    A[Sunshine 图像处理后的有效场景统计] --> B[每个编码帧的 HDR10+ Vivid 或 RPU 生产器]
    B --> C[同帧 protected TLV 加 codec bytes]
    C --> D[Frame Envelope 与块级 FEC]
    D --> E[common-c 完整重组]
    E --> F[DECODE_UNIT bufferList 与 pyrowaveMetadata]
    F --> G[callbacks 与 MoonBridge 专用 PyroWave 回调]
    G --> H[PyrowaveDecoderSession submit]
    H --> I[JNI 与 PyrowaveVulkanDecoder]
    I --> J[解析当前帧 metadata]
    J --> V[GPU YUV 转换与原生 Vivid PQ 或 HLG 提交]
    V --> L[PQ 或 HLG swapchain present]
~~~

动态元数据与码流由同一个 decode-unit 分配拥有，直到 `LiCompleteVideoFrame()` 才释放。
JNI 将元数据区复制到当前 Java 回调的 byte array，native 在同步 submit 内消费；没有
“全局最近动态元数据”回调。丢失、过期、替换的帧同时丢弃其动态载荷。

完整元数据可以跨 DATA 包，参与相同的 FEC。FRAME_HEADER 的单包副本不是权威数据，
必须从受保护 DATA/PARITY 区取得完整 TLV。动态 TLV 必须匹配协商格式并标记
`PROTECTED | REQUIRED`；缺失、重复、截断、错前缀、CRC 错误或未知 required 类型拒绝该帧。

## 动态元数据限制

PyroWave Vulkan 路径对 HDR10+ 与 Dolby Vision 执行受限的应用侧动态映射，
对 Vivid 优先使用原生呈现，不在原生 Vivid 路径重复映射。
Vivid 逐帧 payload 只有在原生 Vivid Vulkan feature、`VK_EXT_hdr_metadata`、函数入口和
有效 `SS_HDR_METADATA` 都满足时才提交；不满足时当前格式失败并等待重新协商。
HDR10+ 与 Dolby Vision 的动态载荷不能通过 Vulkan 通用 swapchain 原生提交，
因此由客户端 shader 解析后应用到 PQ/HLG 基础信号；这不等同于厂商认证的 Dolby 输出。
应用映射仍要求有效的 `SS_HDR_METADATA` 快照来确定源峰值和输出限制；快照缺失或非法时
拒绝该动态会话，不把静态默认值冒充主机元数据。

颜色范围归一化、BT.2020 YUV 转换和静态 HDR metadata 提交仍在 GPU 上完成，图像平面
不读回 CPU。没有动态元数据时，静态 HDR10/HLG 继续按基础色彩合同呈现。

## 原生 Vivid/PQ 与 Vivid/HLG

实际 Vulkan device 同时支持 `VK_HUAWEI_hdr_vivid`、`hdrVivid` feature、
`VK_EXT_hdr_metadata` 和 `vkSetHdrMetadataEXT`，且当前帧开始前已有有效的主机静态快照时，
Vivid/PQ 与 Vivid/HLG 可以使用原生呈现。扩展与 feature 在 logical device 创建时启用，不能仅根据
设备品牌或显示器宣称的 HDR 能力判断。

消费者在第一帧选择，并在该 decoder 生命周期内固定。Vivid 条件不满足时不伪造原生
Vivid；HDR10+ 与 Dolby 走应用映射，不因此额外启用 Vivid feature。资源重建后保持已选
消费者，能力缺失则按现有恢复路径处理故障。

受保护 TLV 内仍保留完整 T.35 payload。原生接口使用 UWA 005.1 §7.3 的
`dynamic_metadata()` 正文：去掉 country/provider/oriented code 共 5 字节，保留
`system_start_code`。在当前帧转换完成后、present 前，将正文通过
`VkHdrVividDynamicMetadataHUAWEI` 链到 `VkHdrMetadataEXT`；不缓存跨帧的载荷指针。
静态源快照不经过应用侧亮度调整；该帧只提交原生 Vivid metadata，避免重复 tone mapping。

当前原生输入只接受主机生成的统计和 base curve 子集：PQ 使用 system_start_code=0x01，
HLG 使用 0x02；该字段表示 UWA 系统版本，不是可任意替换的 transfer 标签。两端必须按
协商格式生成并校验对应载荷，HLG 仍使用 HLG swapchain。没有原生条件时拒绝该动态格式，
不开放任意影片载荷或 authored curves。
Vulkan metadata 接口没有成功返回值；调用完成及 present 提交不等于已证明面板最终效果。

接口依据：[Vulkan Vivid metadata](https://docs.vulkan.org/refpages/latest/refpages/source/VkHdrVividDynamicMetadataHUAWEI.html)、
[UWA 005.1-2022 §7.3、§8.2 与附录 B.3](https://www.uhd-world-association.com/wp-content/uploads/2025/03/TUWA-005.1-2022-HDR-Video-Technology-Part-1-Metadata-and-Tone-Mapping.pdf)。

## 静态元数据与生命周期

`SS_HDR_METADATA` 继续走控制通道，原生 `color_metadata` 继续只描述颜色信号。
原生提交保存有效主机快照；向输出 swapchain 提交时保留主机亮度、色度和 white point，
不通过 PyroWave shader 改写显示目标。源快照不被覆盖，缺失或非法快照不伪造 mastering 数据。
启用期间的缺失、全零或非法更新保留本会话最后接受的有效快照；没有有效值时只使用基础
PQ/HLG 色彩空间。明确关闭 HDR 会清空快照，不继续以固定 HDR 会话提交图像。

Surface 重绑、extent 或分辨率变化、decoder recovery 重建资源并恢复会话格式、目标峰值
和有效静态快照；旧帧动态状态清空，下一帧必须携带自己的 payload。Resume 通过新连接
重新协商，不依赖旧 handle。基础信号冲突或连续呈现失败按现有有界恢复结束连接。

成功消费 metadata 并提交 present 后，详细诊断标记 `native-vivid`。仅协商、解析或创建
swapchain 不能报告动态处理成功。

## 验证入口

- Sunshine `pyrowave_unit_tests`：生产器 golden、实际发布函数、跨包 metadata 和 FEC 恢复。
- common-c `pyrowave-decode-unit-tests`：实际队列中的同帧所有权与 metadata 校验；使用静态 common-c 构建。
- `framegen/src/test/cpp`：客户端生产解析器、固定主机样本、非法 TLV/RPU、Vivid 正文边界，以及实际静态/Vivid 提交辅助的 mock Vulkan 回归。
- Android 单元测试：预检、同帧传参、恢复/尺寸重建、实际消费状态和传统 HDR 策略。

真实黑位、亮部、色域、场景切换、Surface 重建、Resume 和设备丢失需用目标设备验收；
编译和数值测试不证明厂商 compositor 或面板的最终输出效果。

## 许可

本文档及新增项目实现采用 GNU GPLv3（`GPL-3.0-only`），见 [LICENSE.txt](../LICENSE.txt)。
第三方代码与依赖保留各自的许可；原生动态 HDR 提交不代表 Dolby、HDR10+ 或 Vivid 的认证或背书。
