# PyroWave Vulkan 解码与 YUV 转换同步设计

本设计规定 PyroWave GPU 解码、YUV 转换和 Android swapchain 呈现之间的资源交接。整体调用链见[解码器设计](pyrowave-decoder-design.md)，格式与错误语义见[客户端接入合同](pyrowave-client-contract.md)。

## 1. 同步边界

PyroWave 与客户端使用同一个 Vulkan device/queue，但分别提交解码和转换命令。客户端不访问 PyroWave 的内部 CommandBuffer；通过解码 release Semaphore 表达写入完成，再用转换 Fence 控制客户端资源复用。

正常帧路径不调用 `vkQueueWaitIdle`。它等待当前帧的 Semaphore 和 Fence，而不是由 CPU 等待整个队列清空。

~~~mermaid
sequenceDiagram
    participant W as 解码工作线程
    participant P as PyroWave
    participant Q as Vulkan queue
    participant S as Swapchain
    W->>P: decode_gpu_buffer / release sync
    P->>Q: 提交解码并 signal decode_complete
    W->>S: acquire / acquire_semaphore
    W->>Q: 转换提交等待两个 Semaphore
    Q-->>W: submit_fence 完成
    W->>S: vkQueuePresentKHR
~~~

单个会话串行处理帧，只有一套 YUV 平面、CommandBuffer 和 DescriptorSet。下一帧不能覆盖仍由上一帧转换读取的资源。本层同步使用 Binary Semaphore，不使用 Timeline value 交接。

动态 HDR LUT 同样属于当前会话：逐帧验证 metadata 后更新 host-visible storage buffer，
需要时 flush 非 coherent 内存，并使用 HOST_WRITE → COMPUTE_SHADER_READ barrier。
上一帧 submit Fence 完成后，下一帧才可以写同一 LUT；静态模式不启用亮度映射。

## 2. 同步对象

| 对象 | 所有者与用途 |
| --- | --- |
| `decode_complete_semaphore` | 客户端创建；PyroWave signal，转换提交 wait |
| `acquire_semaphore` | 客户端创建；swapchain acquire signal，转换提交 wait |
| `submit_fence` | 客户端转换提交完成信号，也用于平面初始化提交 |
| `drain_fence` | 失败路径空提交消费解码信号时使用 |
| `decode_signal_pending` | 表示成功 decode 提交后、尚未交给转换 wait 的解码信号 |

这些对象属于 decoder session，不跨 Surface 重建或 decoder 销毁复用。信号不能重复 signal 而没有对应 wait；Fence 只在其提交已经完成后 reset。

## 3. 正常帧路径

### 3.1 解码提交

`pyrowave_decoder_decode_gpu_buffer()` 接收以下 release operation：

- `sync.semaphore = decode_complete_semaphore`；
- `sync.value = 0`，按 Binary Semaphore 解释；
- `images = nullptr`，`num_images = 0`。

三个输出平面是客户端创建的原生 `VkImage`，通过 image view 提供给 PyroWave，不是导入的 `pyrowave_image`。因此不把它们伪装成 external reference 交给 release operation，布局和可见性由客户端 barrier 管理。

decode 返回成功表示提交已完成，不表示 CPU 可以读取平面。客户端设置 `decode_signal_pending=true`，后续转换必须等待 release signal。

### 3.2 获取与转换

客户端获取 swapchain 图像并录制转换命令：

1. YUV 平面从 `GENERAL` 转为 `SHADER_READ_ONLY_OPTIMAL`；源访问与阶段匹配 PyroWave 的 compute 或 fragment 写入路径。
2. 输出 swapchain 图像进入 `GENERAL`，作为 storage image。
3. 执行当前模式对应的 YUV 到 RGBA/RGB10A2 shader。
4. 输出图像转为 `PRESENT_SRC_KHR`。
5. YUV 平面恢复为下一帧解码可写入的 `GENERAL`。

`VkSubmitInfo` 同时等待 `decode_complete_semaphore` 和 `acquire_semaphore`，等待阶段覆盖平面写入阶段与转换使用的 compute 阶段。compute 解码使用 `COMPUTE_SHADER`；fragment 解码还包含 `COLOR_ATTACHMENT_OUTPUT`，使布局转换的源阶段与 Semaphore wait 形成依赖链，不能提前转换尚未写完或尚未获取的图像。提交关联 `submit_fence`。

只有 `vkQueueSubmit` 成功后，转换提交才接管对解码 Semaphore 的 wait，客户端才清除 `decode_signal_pending`。不能在录制完成或尝试提交时提前清除。

### 3.3 完成与呈现

客户端等待 `submit_fence`，成功后 reset Fence，再调用 `vkQueuePresentKHR`。由于 CPU 已确认转换完成，present 不再使用额外的 render-complete Semaphore。

`VK_SUCCESS` 和 `VK_SUBOPTIMAL_KHR` 视为该次 acquire/present 可继续处理；`VK_ERROR_OUT_OF_DATE_KHR` 等失败返回上层恢复，不继续使用无效的旧 swapchain。

帧级 acquire/Fence 等待设置 5 秒超时。超时是故障恢复边界，不是正常延迟目标，也不表示整个驱动销毁路径具有同样的时间上限。

提交失败后，该 native handle 不再接受后续帧；检查位于 push、LUT 更新及 GPU 资源复用之前。
Fence 超时不代表 GPU 已完成，因此不能把返回错误当成资源已空闲，也不在旧 handle 上重试。
Kotlin 沿用已有的有界 decoder 重建；native 异常同样使当前 handle 失效。普通不完整帧仍只丢帧，不使 handle 失效。

## 4. 失败路径

| 失败阶段 | 解码信号归属 | 处理 |
| --- | --- | --- |
| decode 返回失败 | 没有确认产生 release signal | 不提交转换 wait，返回后端错误 |
| decode 成功，转换尚未成功提交 | `decode_signal_pending=true` | 用空 queue submit 等待解码 Semaphore，再等待/reset `drain_fence` |
| 转换已经成功提交 | wait 已由 queue 接管 | 不重复消费解码信号；Fence/present 错误交给上层恢复 |
| 消费失败、device lost 或同步对象不可用 | 状态不可安全复用 | 返回故障并重建/结束会话，不重复 signal 旧对象 |

空提交不含 CommandBuffer，仅等待 `decode_complete_semaphore`，等待阶段使用 `ALL_COMMANDS`。消费提交、Fence 等待和 reset 都成功后，才清除 pending 状态。

该路径只处理尚未交给转换 wait 的信号，不能与已经提交的 wait 竞争。普通网络丢帧不进入此 GPU 同步恢复。

## 5. 生命周期与所有权

`submit()`、Surface 绑定、metadata 更新和销毁由 native mutex 串行化。Kotlin 的 Surface 回调只发布 generation；工作线程负责调用 native，避免 Activity 回调执行 GPU 等待。

Surface 重绑定时同时检查窗口身份和 extent；同一个 `ANativeWindow*` 的尺寸变化也需要重建。资源清理路径使用 `vkDeviceWaitIdle` 保护旧 GPU 对象，它位于销毁/重绑定阶段，不是正常每帧路径。

清理顺序为：停止新提交，进入串行清理，等待设备访问旧资源结束，销毁 PyroWave decoder/device 包装，释放客户端图像、pipeline、同步对象和 swapchain，最后销毁 Vulkan device、Surface/instance 和窗口引用。

native 保留静态 HDR 快照，创建新 swapchain 后对新的句柄重新应用；不得向已经销毁的 swapchain 调用 metadata 接口。Kotlin 重建整个 decoder handle 时也重新提交会话快照。

## 6. 计时语义

| 指标 | 客户端线程区间 |
| --- | --- |
| decode | push/校验开始至 GPU decode 提交返回 |
| conversion/present | 之后的 acquire、同步、转换提交、Fence 等待与 present 调用 |

decode 指标不等于 GPU 内部纯解码时间，conversion/present 包含等待解码完成的时间。两项合计到 `vkQueuePresentKHR` 返回为止，不证明图像已经完成扫描显示。

## 7. 适用范围与不变量

- 只用于 PyroWave Vulkan 呈现后端；SDR CPU staging 和传统 MediaCodec 不使用这些同步对象。
- 不完整帧不得读取旧的 decoder 输出并伪装为本帧成功。
- 一次成功 decode 的 release signal 必须由转换 wait 或失败 drain 接管，不能无人消费后重复 signal。
- CommandBuffer、DescriptorSet 和 YUV 平面不能在关联提交完成前被下一帧复用。
- 恢复是当前后端的有界重建；失败后结束连接，不在活动会话中改换 codec。
- 设计不引入多帧并发、额外 FrameSlot 或新的 wire/HDR 协议。

## 8. 许可

本文档采用 GNU GPLv3（`GPL-3.0-only`），协议全文见 [LICENSE.txt](../LICENSE.txt)。PyroWave 及其依赖的许可范围按各自声明保留，详见 [PyroWave NOTICE](https://github.com/AlkaidLab/pyrowave/blob/43c8316c31f9bbe4b26822e70ff73254ebba7fcc/NOTICE.md)。
