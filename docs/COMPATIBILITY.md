# Foundation Sunshine 功能兼容矩阵

Moonlight V+ 首推 [Foundation Sunshine](https://github.com/AlkaidLab/foundation-sunshine)。普通 Sunshine 可以提供标准串流；下表只描述需要主机端配合的 V+ 功能。

**开始使用：**安装近期的 Foundation Sunshine 版本，先按[首次连接教程](GETTING_STARTED.md)完成普通串流，再开启所需功能。某个功能无法使用时，先检查电脑端设置、Android 权限和双方版本。

表中的“待核实”表示我们还没有确认**最早从哪个版本开始支持**，不表示当前版本无法使用。版本号本身也不能保证每台设备都能使用该功能。

| 功能 | 主机端 | 最低版本依据 | 还需满足的条件 |
| --- | --- | --- | --- |
| 标准视频与输入串流 | Foundation Sunshine 或标准 Sunshine | 无 V+ 专属门槛 | 主机与客户端可连接、配对，设备支持选定的编码格式 |
| 麦克风重定向 | Foundation Sunshine | **2025.0720+：项目原有 README 与早期设置文案的声明**；最早发布包仍待回归核实 | 授予 Android 录音权限，主机在连接时提供麦克风通道；见 [麦克风使用步骤](GETTING_STARTED.md#启用麦克风) |
| 主机显示控制 | Foundation Sunshine | 待核实 | 主机端提供对应显示能力；具体操作见应用内选项 |
| 串流中实时码率调整 | Foundation Sunshine | 待核实 | 主机端支持相应控制接口 |
| 超级菜单指令 | Foundation Sunshine | 待核实 | 主机端配置相应指令 |
| 应用桌面美化与主机自动优化 | Foundation Sunshine | 待核实 | 主机端启用对应功能；具体能力以当前主机版本为准 |

## 维护者：如何核定最低版本

1. 找到 Foundation Sunshine 的[发布记录](https://github.com/AlkaidLab/foundation-sunshine/releases)或对应实现，确认功能首次进入哪个发布包。
2. 用该主机版本和一个更早的发布包，搭配同一 Moonlight V+ 版本实测；记录操作系统、功能开关和测试结果。
3. 在本表补充主机版本、客户端版本、发布日期和证据链接，再同步更新 README、应用内提示与商店文案。

遇到“版本够新但功能不可用”时，先确认主机端配置和连接协商结果，再提交包含双方版本、系统、复现步骤与脱敏日志的 [Issue](https://github.com/qiin2333/moonlight-vplus/issues)。
