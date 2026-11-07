# DS5 模拟的离线 PCM 实验

这是分阶段算法实验，不是 E2E、游戏录制、DS5 物理响应模拟或手感验收。
本目录包含可重复的信号生成器和调用实际 SDK 的小型命令行适配器。
客户端阶段由 `AuthoredOfflineCorpusTest` 调用实际 `AuthoredIrAllocator` 和
`RichTapAuthoredHaptics`。没有网络、Android 设备调用、调度队列或串流协议。
旧/新短点增益使用数值模型；同样的公式由现有协调器单元测试覆盖。
SoundPool 现在调用实际素材选择/调度器，另对 Ogg 解码信号做检查；未模拟 Android 混音器或马达。

## 最终集成：主机分层、连续渲染与频率过渡

集成分支为 `codex/ds5-fidelity-integration`，基于 Android `cadb66e2d`。
SDK 固定为 `e334a24`（[SDK #8](https://github.com/AlkaidLab/moonlight-audio-haptics/pull/8)，
持久标签 `pins/ds5-fidelity-20261107-r3`），
主机依赖更新见 [Sunshine #1224](https://github.com/AlkaidLab/foundation-sunshine/pull/1224)。
以下历史候选保留诊断过程，最终行为以本节和紧接的连续渲染说明为准。

- 保留三阶段主机重构及 96 组合的弱触击回归保护。
- 清理未调用的滤波器重置方法，将瞬时计算值局部化；移除客户端旧标量入口，
  测试转换留在测试源中，调试回放统一拦截结构化入口。清理后 272 段 PCM
  以 240/137 帧块重跑，持续层和事件 CSV 与清理前逐字节一致；1331 项 JVM、
  11 项 SDK CTest 通过，instrumentation 测试仅编译，未运行 E2E。
- 后续 review 修复：持续层基线历史不再记录 pending ceiling 压低的输出，避免
  重叠候选继承临时静音；RichTap 已建立的最低档背景不会因新短点而丢失。
  补充非零背景、稳定区间和不对称声道回归。组件仍共用源触击的确认时限，
  不以重启滤波半周期计时的方式延长事件新鲜度。
- 保留客户端完整连续信息、线性短点增益、12 ms 波形保护和移除长时间背景压制。
- 协同的 IR 输出是未经标定的双幅度 rumble API，因此将 authored 连续层交给可用的
  原生机身后端。真实控制器 PCM 波形走独立路由，并在播放时抑制普通马达写入；
  本次没有取消这条原生控制器路径。普通 HOST rumble、单输出和原生故障回退保持。
- 对粗略频率提示在对数频率上做 40 ms 一阶平滑，再进行后端量化与迟滞。
  这属于渲染状态，不进入观测或触击判定，不滤波幅度，也不延迟弱短点。
  RichTap 换频等待上一次 20 ms 频率曲线到达终点，期间增益仍可立即更新；
  到期唤醒采用最新目标，新的短点可立即抢占。停止时清理频率状态。

同源主语料和不同种子语料均检查 PCM 散列、事件参数和源时间；主语料的雨滴
225/225、攀爬 72/72、背景弱触击 70/72 保持。频率提示平滑不是恢复 PCM 相位：
SoundPool 仍使用有限素材替换，RichTap 的 HE 单位也不是标定后的 Hz。
`continuous_regression.json` 同时记录点数、连续波形提交数和扫频跨度，不能用
“零离散事件”证明硬件没有切换接缝。没有做 E2E 或实机体感验证。

同源 91 段的渲染提交对照（每家族 9 段，前者为集成前客户端）：

| 家族 | RichTap 持续提交：前→后 | SoundPool 持续提交：前→后 |
|---|---:|---:|
| 高频跑步 | 59→36 | 154→145 |
| 带噪跑步 | 505→217 | 422→393 |
| 纯扫频抽刀 | 302→216 | 160→168 |
| 带噪抽刀 | 151→124 | 176→166 |
| 拍频 | 47→37 | 74→72 |
| 背景弱触击 | 45→27 | 102→98 |

前后均用生产 `frequencyHint` 处理每个声道的无效 ZCR、截断与能量加权，旧客户端
在独立工作树重新运行同一探针；没有沿用手写加权公式的潜在差异。最终 1331 项 JVM、
11 项 SDK CTest 与 91+61+120 段离线回归通过。
这是每 5 ms 输入一次的离线封包探针，未模拟厂商执行时钟。SoundPool 部分家族
提交增加，不能据此宣称整体接缝消失；频率提示平滑也会改变扫频响应速度。
主语料和 61 段不同种子语料均保留扫频参数跨度、逐段短点和持续强度约束。

最终产物位于 `build/haptics-offline/review-final/{corpus,holdout,stress}`，
修正后的旧客户端对照位于 `build/haptics-offline/baseline-corrected/corpus`。
用下文“运行”命令构建该 SDK 的分析器；生成主语料和 `--seeds 13 53` 的验证语料后，
分别设置 `HAPTICS_OFFLINE_DIR` 运行 `AuthoredOfflineCorpusTest`，再执行：

```powershell
python tools/haptics-offline/regression.py --before "$Out/corpus" --after "$Out/integration/corpus"
python tools/haptics-offline/continuous_regression.py --before "$Out/refactor/corpus" --after "$Out/integration/corpus"
python tools/haptics-offline/stress.py --before-exe "$Out/analyze_pcm.exe" --after-exe "$Out/integration/analyze_pcm.exe" --out "$Out/integration/stress" --check
python tools/haptics-offline/verify_native_assets.py --reference 682a912c6
```

基线与候选输出必须使用不同目录。原始 SDK 为 `445eaa8`；`refactor` 是固定事件
对照，不能用候选覆盖后再声称事件未变化。生成 PCM 仅依赖 NumPy，素材验证另需
soundfile 和 mutagen，绘图可选。459 个素材解码校验通过，孤立短点 12 ms 后的最大
剩余能量比例为 0.0248%；素材解码为 PCM16 共约 19.4 MB，实际 SoundPool 内存另计。

## 连续渲染修复（集成前候选）

2026-11-07，Android 改动继续位于 `codex/ds5-transient-fidelity` 独立工作树。本轮没有
改动 SDK 检测器；使用已完成三阶段重构的 SDK，分别重建主机分析器和客户端探针。
相对于 `refactor/corpus`、`refactor/holdout`，逐段 PCM 散列及事件 CSV 完全一致。
因此下面的客户端改善不依赖悄悄删除主机短点。

- `NativeAuthoredBody` 将低/高频 RMS 和频率提示传过时间线、协调器、latest-wins
  worker、原生后端及故障回退。提示来自现有协议的 40 ms 过零率除以二，按源能量
  合并左右通道；无有效提示时使用双频带重心。混合信号/噪声的过零率不是精确音高。
  未增加协议字段，未对每段信号做满量程归一化。
- RichTap 根据频率提示输出 HE 连续频率曲线，稳态同频仅更新增益；换频时曲线从
  上次频率和非零强度衔接。HE 的 35–75 单位只是受限相对音色映射，不是实际 Hz。
  继续使用孤立 transient 和原有 2/12 ms 混合短点形状；背景更新只保护 12 ms 的
  attack。孤立 preset 的 40 ms 清理尾只防止静默心跳取消它，不阻止新的连续背景。
  移除 100 ms 混合保持和 1.5 s 的 70% 背景上限。满量程背景叠加短点可能饱和，
  不再为了突出短点而人为长时间压低背景。
- SoundPool 保留 16 档背景、8 档短点，增加 100/160/260 Hz 三档载波及切换迟滞。
  将 180 ms 短点保持缩为 12 ms，混合素材由 400 ms 缩成有限的 40 ms。
  459 个素材共 2,372,947 字节；按三通道 PCM16 计算的解码量为 19,388,160 字节，
  低于旧 153 个长素材的 20,563,200 字节（这不是 Android 实际堆内存测量）。
- 协同 + 可用原生机身后端时，完整 authored 持续层交给机身。未经标定的双转子
  不再按低频占比接管它，也不从手机输出中扣除虚构的跨设备能量。独立 HOST rumble
  仍驱动手柄；仅手柄、无原生机身、机身强度为零、其他玩家等回退保持原有路由。
  这是保守的输出归属策略，不是已经完成 ERM 物理频响补偿。

验证完成：1328 项 Android JVM 测试、11 项 SDK CTest；91 段主语料、61 段不同种子
语料、120 段跨载频压力语料。240/137 帧块尺寸检查通过。没有 Android 设备 I/O、
游戏串流或 E2E。压力语料中 96 个纯音背景组合仍满足原 SDK 的召回和误报下限。

| 主语料 | 主机事件/标注命中 | RichTap 短点 | SoundPool 短点 |
|---|---:|---:|---:|
| 雨滴 | 225 / 225 | 225 | 225 |
| 攀爬 | 72 / 72 | 72 | 72 |
| 高频跑步 / 低频跑步 / 纯扫频抽刀 | 0 个多余事件 | 0 | 0 |
| 背景叠加弱触击 | 70 / 72 | 70 | 70 |
| 带噪跑步（无短点标注） | 214 个误报 | 214 | 203 |
| 带噪抽刀（无短点标注） | 243 个误报 | 243 | 218 |

纯扫频抽刀的各个样本覆盖 HE 35–75 频率参数，未生成短点。不同种子语料雨滴
150/150 命中，另有 1 个额外事件；攀爬 48/48，两个后端保留所有存活雨滴/攀爬事件。
SoundPool 的极弱事件仍受 8 档量化限制；没有为补数量设置强度下限。

Ogg 解码验证：24 个孤立短点素材在 12 ms 以后的最大剩余能量为 0.024766%；
原 160 Hz 素材库的 8 个孤立短点，前 12 ms 解码样本与旧版完全一致。
新单测覆盖短点后 20 ms 的背景/频率更新、密集雨滴、故障切换、断连重连和
独立 HOST rumble 共存。频率变化不再编码成短点，但 SoundPool 换素材仍不保证
载波相位连续，RichTap 的实际频响/衔接也依赖固件。离线结果不等于 DS5 实机手感。

产物在 `build/haptics-offline/continuous/`。检测器残余误报和既有弱点漏检明确保留，
不能把这次后端修复描述为“所有场景额外 tick 已消失”。

复现客户端新增检查（先按下方构建步骤生成新的分析器及 corpus，再运行 JVM 探针）：

```powershell
$env:HAPTICS_OFFLINE_DIR = (Resolve-Path "$Out/continuous/corpus").Path
.\gradlew.bat :app:testNonRootDebugUnitTest
python tools/haptics-offline/continuous_regression.py --before "$Out/refactor/corpus" --after "$Out/continuous/corpus"
# 同样对 continuous/holdout 运行探针，再与 refactor/holdout 比较。
python tools/haptics-offline/verify_native_assets.py --reference 682a912c6 --out "$Out/continuous/assets-check.json"
```

`native_render.csv` 保存实际后端频率、素材和命令计数；`continuous_regression.json`
校验连续双频带保留、短点不重播/不丢失、素材存在及扫频变化。`assets-check.json`
记录解码检查。它们属于指令/信号层验证，未建立马达或 Android 混音器响应模型。

## 上轮主机：三阶段重构与召回修复

2026-11-07，主机新增内部 `authored_signal_analysis.h`，分离以下状态及职责：

| 阶段 | 职责 | 禁止依赖 |
|---|---|---|
| 输入观测 | 滤波、半周期峰值及变化、频谱、5/20 ms 实测能量历史 | 候选状态、持续层暂扣或输出 |
| 候选与判定 | 候选生成、局部形状与背景证据、pending/confirmed/rejected 状态、强度估计 | 持续层修整后的历史 |
| 持续层分解 | pending 暂扣、确认后的归属、否决后的原包络恢复 | 不得修改观测或判定 |

事件引擎保留跨带/通道分组、事件 ID、源时间与发布不可变性。所有阶段按样本时钟
执行，仍无处理期内存分配；历史样本缓冲约 120 KiB/engine，上一候选为 96 KiB。协议布局、确认
时限和客户端分配曲线不变。本轮理清归属边界，未引入新的恢复平滑或额外等待。

召回修复保留短时形状要求，但允许两条佐证路径：整体能量确有短时隆起，或同频带
相对既有周期变化出现新的局部突起。第二条仅在起始前的快/慢能量比例处于 0.5–1.5
时开放，避免把整体启动/衰减当成弱触击。复用原有局部变化 4 倍条件，不按游戏名
或载频白名单放行。它仍是工程判据，需要持续扩充反例。

120 段压力语料中，96 个纯音背景组合全部恢复到原始 SDK 的逐段召回下限，且误报
不超过对应基线。其余 24 个噪声背景组合只报告结果，不把偶然命中当作可靠召回。
原先 85 Hz 背景中的退化修复如下，每组各有 60 个标注触击：

| 触击载频 | 原 SDK 命中 | 重构前候选 | 本轮 |
|---|---:|---:|---:|
| 100 Hz | 28 | 25 | 28 |
| 160 Hz | 40 | 31 | 40 |
| 270 Hz | 40 | 40 | 40 |
| 330 Hz | 60 | 54 | 60 |

这表示补回新增漏检，不表示原有漏检已消失。新的回归保护也覆盖 260 Hz 纯音背景，
不限于上述 85 Hz 背景。`stress.py --check` 现在检查所有纯音背景的逐段召回与误报，
并校验两种块尺寸的事件参数、源时间和共同时间点持续层一致性。

原 91 段 PCM 的 SHA-256 保持一致：雨滴 225/225、攀爬 72/72、背景触击 70/72；
高频跑步/拍频/纯扫频抽刀仍零误点，带噪跑步仍 214 个误点。**取舍：带噪抽刀从
重构前 239 增至 243，较原始 427 仍减少 43.1%。** 61 段不同种子验证中带噪跑步
158→161，带噪抽刀保持 166；雨滴命中 150/150 且仍有 1 个额外点，攀爬 48/48，
背景触击仍 46/48。没有通过收紧统一门限换取表面上更少的事件。

验证：11 项 SDK CTest 通过，包含新增 96 组合的 C++ 回归和观测/持续层状态边界
检查；Android 本轮 16 项定向 JVM 测试通过，实际 RichTap 封包仍保留每个存活事件。
原 91 段、61 段不同种子和 120 段压力语料均完成 240/137 两种块尺寸检查。
未做 E2E 或实机体感验证，手机持续层变化量上升等既有设备映射问题不在本轮解决。

本轮产物在 `build/haptics-offline/refactor/`，旧 baseline、optimized 和 stress 目录
保留作为历史证据。重新编译主机 SDK 后，将适配器输出到 `refactor/analyze_pcm.exe`，
沿用下面命令，但把输出目录改为 `refactor/corpus`；随后执行：

```powershell
python tools/haptics-offline/stress.py --before-exe "$Out/analyze_pcm.exe" --after-exe "$Out/refactor/analyze_pcm.exe" --out "$Out/refactor/stress" --check
python tools/haptics-offline/regression.py --before "$Out/corpus" --after "$Out/refactor/corpus"
```

主机必须集成重构后的 SDK 才能生效；仅更新 Android 不会改变事件识别。
以下章节保留历史实验过程，历史候选的退化说明不代表本轮上述已修复用例仍然失败。

## 调研与信号设计

- [Bernard 等，The high/low frequency balance drives the perception of noisy vibrations](https://arxiv.org/abs/2311.10644)
  通过纹理振动感知与重合成实验强调高低频平衡。因此跑步纹理同时覆盖高频占优、
  低频占优和带限噪声，而不只使用纯音。
- [Ahmaniemi 等，Design of Dynamic Vibrotactile Textures](https://pubmed.ncbi.nlm.nih.gov/27788110/)
  研究用运动信息和单个振动执行器构造动态纹理。本实验借鉴“纹理也可以是持续动态
  信号”的设计方向，不声称复现其装置或实验参数。
- [Humans Use a Temporally Local Code for Vibrotactile Perception](https://www.eneuro.org/content/8/6/ENEURO.0263-21.2021)
  将局部脉冲形状与脉冲频率的变化分开研究。因此测试不把总体 RMS 相近当作短点
  保真，也单独统计触击是否被漏掉/额外产生。
- [Apple：Updating Continuous and Transient Haptic Parameters in Real Time](https://developer.apple.com/documentation/corehaptics/updating-continuous-and-transient-haptic-parameters-in-real-time)
  展示连续与瞬态事件的独立表达。本实验的抽刀负例用平滑强度包络和相位连续扫频，
  加入噪声变体；没有把连续效果预先编码成点击序列。

具体频率、强度、噪声比例和时间常数是本实验的工程假设，不是这些文献提供的
影之刃零参数，也不是实际 DS5 的已知驱动波形。

## 预先定义的语料

每段为 3.2 秒、48 kHz、双声道 S16LE。双声道代表左右执行器，不代表声音音箱。
每类覆盖种子 7/19/41、峰值 0.03/0.06/0.12，共 90 段，另加一段静默。
每段同时生成 `.pcm`、有格式头的 `.wav`、标签及 SHA-256。WAV 用于信号工具查看，
通过扬声器听取不能代替触觉验证。

| 家族 | 信号 | 生成时的意图 |
|---|---|---|
| rain | 不规则 25 次、270 Hz 快起振/6 ms 衰减短脉冲 | 25 个短点 |
| climb | 300 ms 间隔的 8 次短触击，左右力度不同 | 8 个短点 |
| run_fine | 260 Hz 主体 + 0.18 倍 95 Hz，2.8 Hz 平滑调幅 | 连绵纹理，无独立短点 |
| run_low | 95 Hz 主体 + 0.18 倍 260 Hz，同样调幅 | 低频占优纹理，无独立短点 |
| run_noise | 80–380 Hz 带限噪声，平滑起停/调幅 | 连绵碎振，无独立短点 |
| draw_clean | 90→320 Hz 相位连续扫频，sin² 包络，120/300/600 ms 三段 | 三次连续嗡振，无点击 |
| draw_noise | 同样扫频包络，混合 50–400 Hz 带限噪声 | 三次粗糙连续嗡振，无点击 |
| beating | 160 Hz + 200 Hz 两个持续分量 | 连续拍频纹理，无独立短点 |
| bass | 85 Hz 平滑持续载波 | 低频主体对照 |
| mixed | 弱 85 Hz 背景叠加 8 次触击 | 8 个短点与持续层并存 |
| silence | 全零 | 无输出 |

“多余事件”是相对于上述合成意图，而不是人类感知标签。特别是拍频、带限噪声包含
局部起伏，人可能感到颗粒感；此测试检查算法是否把它们全部强制重塑成原生短点。
短点匹配窗口预设为源时刻 -5 到 +30 ms，一对一匹配，另记漏检和未匹配事件。
不把软件实验中已知不通过的负例断言为通过。

## 运行

依赖：CMake、C++17 编译器、Python 3 + NumPy；绘图另需 Matplotlib；客户端需要
项目正常的 Android SDK/JDK/Gradle 环境。以下为 PowerShell 示例，路径按本机修改：

```powershell
$Sdk = 'C:/path/to/moonlight-audio-haptics'
$Out = 'build/haptics-offline'
cmake -S $Sdk -B "$Out/sdk-build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMOONLIGHT_HAPTICS_BUILD_TESTS=OFF
cmake --build "$Out/sdk-build" --target moonlight_haptics_core
g++ -std=c++17 -O2 -Wall -Wextra -Werror -DMOONLIGHT_HAPTICS_STATIC=1 -I "$Sdk/include" tools/haptics-offline/analyze_pcm.cpp "$Out/sdk-build/libmoonlight_haptics_core.a" -o "$Out/analyze_pcm.exe"
python tools/haptics-offline/corpus.py --sdk $Sdk --analyzer "$Out/analyze_pcm.exe" --out "$Out/corpus"
$env:HAPTICS_OFFLINE_DIR = (Resolve-Path "$Out/corpus").Path
.\gradlew.bat :app:testNonRootDebugUnitTest --tests '*AuthoredOfflineCorpusTest' --tests '*NativeAuthoredDeviceTest' --tests '*AuthoredIrAllocatorTest' "-PaudioHapticsSdkDir=$Sdk"
python tools/haptics-offline/corpus.py --out "$Out/corpus" --report
```

常规单测未设置 `HAPTICS_OFFLINE_DIR` 时，这个可选语料测试明确标为 skipped。
SDK 需要包含 authored event API；此次运行版本为 `445eaa82a7b9b8e8c725620394bea8fce23381db`。
生成器分别用 240 和 137 帧块调用 SDK，并核对事件 ID、源时间、强度、锐度及相同
时间点的持续层值。首次观测时刻因块边界可以不同，多个事件的发布顺序也可能不同，
所以比较时按 ID 对齐。SDK 冗余事件先按 ID 去重。

输出的 `manifest.json` 记录 SDK commit、dirty 状态、生成器散列及各 PCM 的散列。
`frames_*.csv` 是实际 SDK 持续层，`events_*.csv` 是实际 SDK 事件，`client.csv`
是实际分配器/RichTap 封包运行记录加上旧/新增益模型，`summary.csv/json` 是统计。
`comparison.png/pdf` 是代表样本曲线。输出不进入源码版本控制。

## 2026-11-07 首轮基线结果（优化前）

91 段、两种块尺寸全部完成并通过块尺寸一致性检查。客户端阶段及相邻原生/分配
单元测试通过。此处“单测通过”只说明计算、输入输出契约和实验执行正确，绝不代表
下面的感知目标全部达到。

下表每行汇总该家族的 9 段，短点数为去重后的 SDK 事件：

| 家族 | 预期短点 | 实际事件 | 匹配 / 漏检 / 多余 | RichTap 改前→改后 |
|---|---:|---:|---|---|
| 雨滴 | 225 | 227 | 225 / 0 / 2 | 227→227 |
| 攀爬 | 72 | 72 | 72 / 0 / 0 | 72→72 |
| 高频跑步纹理 | 0 | 2605 | 0 / 0 / 2605 | 2605→2605 |
| 低频跑步纹理 | 0 | 0 | 0 / 0 / 0 | 0→0 |
| 噪声跑步纹理 | 0 | 1549 | 0 / 0 / 1549 | 1549→1549 |
| 纯扫频抽刀 | 0 | 0 | 0 / 0 / 0 | 0→0 |
| 带噪抽刀 | 0 | 427 | 0 / 0 / 427 | 427→427 |
| 连续拍频 | 0 | 899 | 0 / 0 / 899 | 899→899 |
| 低频主体 | 0 | 0 | 0 / 0 / 0 | 0→0 |
| 背景＋触击 | 72 | 70 | 70 / 2 / 0 | 70→70 |

结论：此前客户端改动降低了弱点增益，但未减少这些语料的 RichTap 事件数量。
单靠更改力度不能解决连续复合波被拆成点击的问题。纯扫频通过而带噪扫频失败，
说明只测正弦波会遗漏重要边界。混合场景也存在低强度触击漏检，不能简单上调统一阈值。

协同分配的效果同样不是全面改善：

- 低频指令平方和的改后/改前比例，在高频跑步纹理为 0，噪声跑步约 6.2%，
  但低频跑步约 96.4%，几乎保留原来的低频摇摆指令。
- 低频指令总变化量 `sum(abs(diff(command)))`，拍频类变为约 1.92 倍，带噪抽刀
  约 1.22 倍。虽然幅度/平方和降低，新的 `sqrt(max(L²-H²,0))` 分配在频带主导权
  交替时可能产生更多起伏。因此不能把“总量变小”当作“更平顺”。
- 这些都是幅度指令域的量，未模拟非对称转子的惯性、起振阈值、迟滞或握持耦合，
  不能换算成“物理摇摆降低多少”。平方和比例按每段计算后取平均；变化量比例按家族汇总。
- SoundPool 的量化模型使高频跑步 2605→2575、噪声跑步 1549→1445、带噪抽刀
  427→369；这是弱点量化消失，不是主机正确识别了连续纹理。

下一轮算法应以这些数据为反例：主机需要结合连续纹理上下文判断短点，而不是只看
半周期峰值的快速回落；客户端的频带分配需要检查主导权交替时的平顺性。任何候选
修改都要同时报告连续负例、雨滴/攀爬召回以及混合弱触击，不能只优化其中一组。
首轮只增加研究工具和数据；下面第二轮才修改主机识别及协同分配。

## 第二轮：连续纹理识别与平顺分配

**后续压力测试发现召回退化，当前候选尚不能作为普遍保真的定稿。** 原 91 段及
同家族随机验证中的弱点保留结论成立，但不适用于换载频的全部触击。详见下节。

主机 SDK 的 `src/core/authored_event_engine.cpp` 增加独立于触击所有权的实测能量历史。
低频候选需相对 20 ms 全频带背景形成明显的短时能量隆起并回落，防止高频持续载波
在低频滤波分支的起伏成为点击。高频候选保留较宽松的 5 ms 快速上下文，以保留密集
雨滴和弱触击。谱平坦度增加 93.75–328.125 Hz 内区间检查，并用三相邻频点的能量
集中度保护窄带衰减脉冲。原有全带噪声检查继续保留。

这些是工程判据，不是场景标签识别；阈值由反例及正例回归共同约束。更严的噪声门限
曾使密集脉冲漏检，已撤回。现有 25/50/60/80 次每秒脉冲、弱触击强度比例、无运行期
分配、事件不可变性及块尺寸一致性约束保持通过；没有改变协议或事件新鲜度窗口。

客户端协同原生分配使用平滑权重：令 `r=clamp(H/L,0,1)`，低频为零时 `r=1`，
转子输出 `C=L*(1-r)^2*(1+2r)`；手机低频为 `sqrt(max(L²-C²,0))`，高频仍为 `H`。
两个端点的权重斜率为零，消除旧 `sqrt(max(L²-H²,0))` 在交界处的无限斜率。
平方振幅在软件分配中守恒，不代表两种执行器的物理能量等价。没有额外延迟或最低力度。
单设备及显式单一路由不变；线性短点增益继续保留。

同一批 91 段 PCM 的 SHA-256 全部一致；表中为主机去重事件数量：

| 家族 | 首轮→本轮 | 本轮保留情况 |
|---|---:|---|
| 高频跑步 | 2605→0 | 连续层仍输出 |
| 带噪跑步 | 1549→214 | 误触击减少 86.2%，仍有残余 |
| 带噪抽刀 | 427→239 | 误触击减少 44.0%，仍有残余 |
| 连续拍频 | 899→0 | 连续层仍输出 |
| 低频跑步、纯扫频抽刀、低频主体、静音 | 0→0 | 无新增触击 |
| 雨滴 | 227→225 | 225/225 标注命中，去掉 2 个多余点 |
| 攀爬 | 72→72 | 72/72 标注命中 |
| 背景＋弱触击 | 70→70 | 70/72，原有 2 处漏检未改善也未增加 |

实际 RichTap 封包保留每个存活事件，并非通过量化丢掉弱点来降低数量。
另用未参与调参的种子 13、53 生成 61 段同家族验证：高频跑步 1531→0，带噪跑步
1037→158，带噪抽刀 285→166，拍频 496→0；雨滴命中 150/150（尚有 1 个额外点），
攀爬 48/48，背景弱触击 46/48（原有 2 处漏检不变）。这是同分布随机验证，不能证明
对未知游戏波形泛化。另有 SDK 单测使用不同载频、幅度及步频保护连续复合纹理。

相对首轮候选实现，家族汇总转子指令总变化量下降：低频跑步 6.1%、带噪跑步 85.3%、
纯扫频抽刀 32.1%、带噪抽刀 18.1%、拍频 97.3%。隔离主机变化、在本轮相同帧上比较
两种分配公式时也均下降。没有全面平顺的结论：低频主体约增 0.8%，背景触击约增
9.1%，雨滴约增 9.4%（其原本转子指令很小）。详见 `regression.csv` 的绝对量；这些
统计不包含转子惯性、起振阈值或真机握持，不能写成实测摇摆下降比例。

回归检查：

```powershell
# 将新 SDK 编译到新的 analyzer，保留首轮 corpus 和 analyzer。
python tools/haptics-offline/corpus.py --sdk $Sdk --analyzer "$Out/optimized/analyze_pcm.exe" --out "$Out/optimized/corpus"
$env:HAPTICS_OFFLINE_DIR = (Resolve-Path "$Out/optimized/corpus").Path
.\gradlew.bat :app:testNonRootDebugUnitTest
python tools/haptics-offline/regression.py --before "$Out/corpus" --after "$Out/optimized/corpus"
# 两个分析器分别运行 --seeds 13 53，再用 --host-only 比较，可复现随机验证。
```

`regression.py` 校验实际 PCM 散列、逐段正例召回不退化、连续纯音负例零事件、噪声
反例改善预算、RichTap 点数保留及重点场景指令平顺性。预算是本实验的工程回归线，
不是感知阈值。11 项 SDK CTest、1320 项 Android JVM 测试通过；未做 E2E 或真机验证。
改动位于独立 `codex/ds5-transient-fidelity` 工作树，主机使用新识别需要重新编译/集成
该 SDK；仅更新 Android 不会得到主机端的误判下降。

## 后续推演：已发现的恶化与下一步优先级

新增 `stress.py`，生成 120 段不同的 PCM：100/160/270/330 Hz 衰减触击、85 Hz 或
260 Hz 持续背景及带限噪声、背景幅度 0.01/0.03、触击系数 0.015/0.03/0.06，两种
纯音背景相位。每段加入 5 个预先标注的脉冲；脉冲系数不是合成后 PCM 的峰值。
这里没有更改生产算法，也没有继续调门限。

干净的 85 Hz 背景中，双方都没有多余事件，仍发现明确的新增漏检：

| 触击载频 | 标注数 | 基线命中 | 当前命中 |
|---|---:|---:|---:|
| 100 Hz | 60 | 28 | 25 |
| 160 Hz | 60 | 40 | 31 |
| 270 Hz | 60 | 40 | 40 |
| 330 Hz | 60 | 60 | 54 |

共 12 个干净背景用例发生召回退化。噪声背景中也有命中数下降，但旧算法每段大量
乱发点，部分时间窗匹配可能只是偶然，不能把这部分计数直接当成真实召回。
原先主要用 270 Hz 触击的语料不足以排除对其他载频的伤害。

诊断消融（只编译到 `build/haptics-offline/stress/diagnostic`，未写回 SDK）：

- 保留内区间频谱检查，单独移除新增能量确认条件：上述干净背景命中恢复至
  28/40/40/60，但原语料高频跑步的 2605、拍频的 899 个多余点也全部回来。
  因此问题确实关联能量判据，不能简单整块撤回。
- 保留检测，只取消 `visible_energy()` 对 pending 候选的暂时压低：原语料事件计数
  不变，但雨滴的机身持续增益总变化量约为当前 22.6 倍，攀爬约 46.5 倍，背景触击
  约 6.5 倍。纯扫频抽刀只改善约 2.8%。这暴露持续层提前泄出的问题，不能靠删除
  所有候选抑制修补抽刀包络。此处客户端分配为公式计算，未实际调用厂商后端。

另外，之前只看转子变化量会遗漏代价：当前原生机身持续增益的家族汇总总变化量，
相对首轮候选在低频跑步约增 65%、带噪跑步约增 94%、背景触击约增 60%；
背景触击的最大单步增益变化从 0.00531 升至 0.00868。它可能包含应当保留的纹理，
并不直接证明体感更差，但已足够否定“转子更平顺，所以整体一定更平顺”的推论。

下一步建议按顺序验证，以下均是待测假设：

1. **先补召回，再压残余误判。** 将同频带局部突起与全频带背景证据分开；只有证据
   支持“强持续载波在另一个分支的起伏”时才用严格抑制。新增独立短击不能仅因
   总背景强而被否决。不能把规律性或跨带相关性单独当成否决条件，密集攀爬也可能规律。
2. **改进候选归属过渡。** 记录候选暂扣的持续层，分别检查确认触击和否决触击时的
   恢复轨迹；避免否决时整段电平突返，也避免确认前持续层与短点重复输出。
   不统一延长确认窗口，不给所有信号加重低通。
3. **两端联合验收。** 同时报告转子和原生持续层的最大单步变化、总变化量、有效
   包络形状与延迟。平方幅度守恒只是软件记账，不能要求手机无条件接收所有转移量；
   设备校准后的分配和增益限制要独立验证，尤其是静止与起振时。
4. **扩充保真门槛。** 保留原 91 段和 61 段随机验证，再加入本轮跨频带正例，后续
   增加“带噪背景＋明确触击”的成对有/无脉冲对照，避免用误报碰巧命中标签来通过召回。

明确不建议：统一提高门限、按事件密度一刀切限频、给弱点统一设置最低力度、全面
取消候选持续层抑制、靠全局低通把所有振动磨平，或只优化转子而忽略手机输出。

复现压力测试（`--check` 在当前候选上应报告 12 个召回退化用例并失败，这是新发现
的保真缺陷，不是运行工具失败）：

```powershell
python tools/haptics-offline/stress.py --before-exe "$Out/analyze_pcm.exe" --after-exe "$Out/optimized/analyze_pcm.exe" --out "$Out/stress" --check
```

`stress.csv`、`summary.json`、`recall_regressions.json` 和 `manifest.json` 保存逐段结果、
标注及实际分析器散列。当前压力测试用 240 帧块，未声称新增组合均完成 137 帧复测。
