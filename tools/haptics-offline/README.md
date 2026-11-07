# DS5 离线 PCM 回归

用于复现连续纹理误判、弱触击漏检和客户端渲染退化。输入是合成执行器 PCM，
分析器调用真实 SDK，JVM 探针调用实际分配器、RichTap 封包器和 SoundPool 调度器。
所有生成数据放在忽略的 `build/haptics-offline/` 下，不提交 PCM/WAV、CSV、图表或日志。

## 当前用法

| 工具 | 用途 |
|---|---|
| `corpus.py` + `analyze_pcm.cpp` | 生成带标签的 PCM；以 240/137 帧块运行 SDK，检查事件参数和共同时间点的持续层一致性 |
| `AuthoredOfflineCorpusTest` + `continuous_regression.py` | 导出真实客户端轨迹；检查固定主机事件、双频带传递、短点保留、素材及扫频跨度 |
| `continuous_fidelity.py` + `test_continuous_fidelity.py` | 计算连续幅度/频率诊断指标；验证衰减、静音、固定载频和缺失输出不会被误判为改善 |
| `stress.py --check` | 跨载频弱触击回归：纯音背景逐例检查召回下限和额外事件上限，噪声背景仅报告 |
| `regression.py` | 相对原始 SDK 的事件改善预算；可用 `--host-only` 跳过客户端，`--plot` 生成可选对比图 |
| `verify_native_assets.py` | 解码已提交的原生素材，检查触觉声道、载频、短点形状与尾部能量 |

### 构建与生成

依赖 Python 3 + NumPy、CMake/Ninja、C++17 编译器，以及项目正常的 Android SDK/JDK/Gradle
环境。素材检查另需 `soundfile`、`mutagen`；可选绘图需 Matplotlib。以下 PowerShell 示例
使用同一套 GCC/MinGW 工具链构建 SDK 和适配器，从 Android 仓库根目录运行：

```powershell
$Sdk = 'C:/path/to/moonlight-audio-haptics' # 独立 SDK checkout，版本见下方基线
$Out = 'build/haptics-offline/current'
cmake -S $Sdk -B "$Out/sdk-build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DMOONLIGHT_HAPTICS_BUILD_TESTS=ON
cmake --build "$Out/sdk-build"
ctest --test-dir "$Out/sdk-build" --output-on-failure
g++ -std=c++17 -O2 -Wall -Wextra -Werror -DMOONLIGHT_HAPTICS_STATIC=1 -I "$Sdk/include" tools/haptics-offline/analyze_pcm.cpp "$Out/sdk-build/libmoonlight_haptics_core.a" -o "$Out/analyze_pcm.exe"
python tools/haptics-offline/corpus.py --sdk $Sdk --analyzer "$Out/analyze_pcm.exe" --out "$Out/corpus"
python tools/haptics-offline/corpus.py --sdk $Sdk --analyzer "$Out/analyze_pcm.exe" --out "$Out/holdout" --seeds 13 53
```

修改 SDK 后必须重建分析器。`manifest.json` 记录源码版本、dirty 状态、分析器及 PCM
散列，但不会自动证明二进制来自所指定的源码。任一步失败应先处理，不继续使用旧产物。

### 客户端探针与回归

```powershell
foreach ($Set in @('corpus', 'holdout')) {
    $env:HAPTICS_OFFLINE_DIR = (Resolve-Path "$Out/$Set").Path
    .\gradlew.bat :app:testNonRootDebugUnitTest --rerun --tests '*AuthoredOfflineCorpusTest' "-PaudioHapticsSdkDir=$Sdk"
    if ($LASTEXITCODE -ne 0) { throw "Client probe failed: $Set" }
}
Remove-Item Env:HAPTICS_OFFLINE_DIR
python -m unittest discover -s tools/haptics-offline -p test_continuous_fidelity.py -v
python tools/haptics-offline/verify_native_assets.py --reference 682a912c6 --out "$Out/assets-check.json"
```

`--rerun` 确保切换语料目录后重新执行测试；未设置 `HAPTICS_OFFLINE_DIR` 时，此可选测试
明确标为 skipped。素材参考 `682a912c6` 是 Android 仓库中原 160 Hz 素材库的提交，需保留
该 Git 对象。输出包括 `frames_*.csv`、`events_*.csv`、`client.csv` 和 `native_render.csv`。
后者需由当前探针生成，包含 `rt_active`，不能直接沿用旧格式轨迹。

修改客户端前，先用已验收版本按上述步骤生成独立的冻结目录，并记录提交和散列。
`$Frozen` 指向该目录；它与 `$Out` 必须不同，且主机版本、语料种子相同：

```powershell
$Frozen = 'C:/path/to/accepted-offline-snapshot'
foreach ($Set in @('corpus', 'holdout')) {
    python tools/haptics-offline/continuous_regression.py --before "$Frozen/$Set" --after "$Out/$Set"
    if ($LASTEXITCODE -ne 0) { throw "Continuous regression failed: $Set" }
}
```

该检查要求主机事件不变，适用于客户端修改。修改检测器时，另在独立 SDK checkout
检出原始基线 `445eaa82a7b9b8e8c725620394bea8fce23381db`，重复构建/生成步骤，输出到
单独的 `$Original` 目录，使用相同种子：

```powershell
$Original = 'C:/path/to/original-sdk-offline-output'
foreach ($Set in @('corpus', 'holdout')) {
    python tools/haptics-offline/regression.py --before "$Original/$Set" --after "$Out/$Set" --host-only
    if ($LASTEXITCODE -ne 0) { throw "Host regression failed: $Set" }
}
python tools/haptics-offline/stress.py --before-exe "$Original/analyze_pcm.exe" --after-exe "$Out/analyze_pcm.exe" --out "$Out/stress" --check
```

`regression.py` 固定要求带噪跑步/抽刀误报分别不超过原始基线的 30%/70%，不能用两个
已优化候选替代这组对照。省略 `--host-only` 前须在两组输出上都运行客户端探针。
`stress.py` 也可与已验收分析器比较，以进一步约束后续退化。禁止覆盖基线后再自比。

## 当前基线

SDK 为 `e334a247366555f067fd52d841bdc7ca858a777b`，持久标签
`pins/ds5-fidelity-20261107-r3`（[SDK #8](https://github.com/AlkaidLab/moonlight-audio-haptics/pull/8)）；
客户端行为基线为 `160a799f0`，连续指标及轨迹格式由 `3dc864433` 引入。
主机集成见 [Sunshine #1224](https://github.com/AlkaidLab/foundation-sunshine/pull/1224)。

主语料为 91 段：每段 3.2 秒、48 kHz 双声道 S16LE，种子 7/19/41，峰值
0.03/0.06/0.12，另加静默。独立种子 13/53 生成 61 段。信号定义和标签由 `corpus.py`
维护：雨滴/攀爬为衰减短脉冲，跑步覆盖高低频复合载波与带限噪声，抽刀为平滑扫频，
另有拍频、低频主体及背景叠加触击。短点按源时间 -5～+30 ms 一对一匹配。

| 主语料 | 当前结果 |
|---|---|
| 雨滴 | 225/225 命中，无额外点 |
| 攀爬 | 72/72 命中，无额外点 |
| 背景弱触击 | 70/72 命中，保留两处漏检 |
| 高频/低频跑步、干净抽刀、拍频、低频主体、静默 | 无额外点 |
| 带噪跑步 | 214 个误报 |
| 带噪抽刀 | 243 个误报 |

雨滴、攀爬的存活事件在两个客户端后端均保留。61 段验证语料中，雨滴 150/150 命中但
有 1 个额外点，攀爬 48/48，背景触击 46/48；带噪跑步/抽刀误报为 161/166。
120 段压力语料涵盖 100/160/270/330 Hz 触击、85/260 Hz 纯音或噪声背景；96 个纯音
组合守住原始 SDK 的召回和误报基线，其余 24 个噪声组合只报告。两种块尺寸均检查。

459 个素材解码检查通过：孤立短点在 12 ms 后的最大剩余能量约 0.0248%，PCM16
解码体积约 19.4 MB（不是 Android 实际内存）。素材生成脚本为 `scripts/generate_native_haptics.py`。

### 连续指标的含义

`continuous_regression.json` 同时保存硬检查统计与只报告的 `fidelity` 指标：

- 幅度：源 PCM 的 20 ms 尾随窗 RMS × √2，取左右最大值，与送入后端的连续 gain
  比较 NRMSE 和 RMS 比值；不拟合增益、不归一化输入。误差包含滤波、频带组合及时间差异。
- 频率：仅对 `bass`、`draw_clean` 使用 Hilbert 相位参考，排除低包络的不可靠相位。
  分别报告源提示的 Hz 误差、RichTap 目标的 HE 单位误差、SoundPool 所选载频的 Hz
  误差；后两者附有效输出覆盖率，停止/静音不会被当作零误差。

主语料干净抽刀的 gain RMS 比值约 0.995、NRMSE 约 0.167，源频率提示平均绝对误差约
15.3 Hz。总量相近不等于包络及时序一致；这些是诊断基线，不是统一的保真验收阈值。
参考思路见 [Android PCM→PWLE 算法说明](https://github.com/android/haptics-tools/blob/main/docs/pcm_to_pwle_conversion_algorithm.md)；
本工具用 NumPy 独立实现轻量参考，不是完整算法移植。Hilbert 使用整段数据，仅用于离线评价。

## 局限

- 合成标签表达工程意图，不是影之刃零录制、真实 DS5 波形或人类感知标注。噪声中的
  偶然匹配不等于可靠召回；当前带噪误报和弱触击漏检仍存在。
- 探针每 5 ms 提交一次，未模拟网络、串流协议、设备工作队列、厂商时钟或马达响应。
  `client.csv` 的旧/新短点增益是数值对照，不是两个真实历史客户端的实机比较。
- 过零率是粗略频率提示；多频和噪声没有单一正确频率。HE 单位不是物理 Hz，已提交
  目标不等于马达当前输出。SoundPool 的量化与素材替换也不保证相位连续。
- 命令次数、幅度平方和与总变化量不能直接换算成物理能量、摇摆或体感改善。
  新增连续指标只报告，原有事件/块尺寸/素材约束仍是硬检查。
- 未执行 E2E 或实机手感验收；更新客户端本身不能获得主机 SDK 的识别改善。
