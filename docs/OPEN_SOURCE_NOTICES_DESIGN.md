# Android 开源引用与许可页面设计

状态：设计稿（2026-09-24）
背景：[usbipdcpp 作者的反馈 #656](https://github.com/qiin2333/moonlight-vplus/issues/656)

实现后的覆盖范围与待办见 [OPEN_SOURCE_NOTICES_AUDIT.md](OPEN_SOURCE_NOTICES_AUDIT.md)。

## 目标

让用户在 Android App 内找到随 App 使用的开源组件、项目地址和许可信息。首个必须明确展示的条目是 `usbipdcpp`。现有「生态项目」用于介绍同生态产品，继续保持这个用途。

## 入口与信息结构

- 设置 → 帮助 → **开源许可**：固定入口，放在「关于 Moonlight V+」之后。
- 关于 Moonlight V+ → **开源许可**：与「文档」「生态项目」并列的入口。窄屏允许换行或改为纵向排列，不能挤压文字。
- 两个入口打开同一页面。页面标题为「开源许可」，支持返回到原入口。
- 页面先显示简短说明，再按组件列出名称、版本、许可标识。点击条目进入详情，展示项目链接、版权说明、完整许可文本，以及适用时的源码与构建说明链接。
- 列表按组件显示名称不区分大小写地 A–Z 排列；相同名称以稳定 ID 排列。许可类型和加入清单的时间不影响顺序。
- 页面内容随 APK 离线提供。外部项目链接通过现有 `BrowserOnlyLauncher` 打开；离线时仍可阅读许可文本。

## 首个条目

| 字段 | 内容 |
| --- | --- |
| 名称 | usbipdcpp |
| 项目 | https://github.com/yunsmall/usbipdcpp |
| 当前固定版本 | `1355113f030c4c13404030e6e7426bceb6085276` |
| 许可标识 | LGPL-3.0（最终文本以所固定版本的许可文件核对） |
| 简介 | Android USB 设备转发功能使用的 USB/IP 核心 |
| 建议署名 | This product uses usbipdcpp (https://github.com/yunsmall/usbipdcpp), licensed under LGPLv3. |

版本来源：[usbip-backend/src/main/cpp/CMakeLists.txt](../usbip-backend/src/main/cpp/CMakeLists.txt)；现有来源记录：[usbip-backend/README.md](../usbip-backend/README.md)。

## 数据与维护

维护一份仓库内、随版本发布的引用清单；UI 只读取这份清单，不在 Compose 代码里手写依赖条目。每条至少包含稳定 ID、显示名称、版本或固定提交、项目 URL、许可标识、许可文本资源路径；可选版权说明和源码/构建说明链接。构建或发布检查应验证资源存在、链接格式有效，并检查清单与实际打包依赖的差异。

首轮盘点以**实际分发内容**为准：`usbip-backend` 的 usbipdcpp、libusb、Asio、spdlog；`moonlight-common-c` 与其他原生模块；App 的 Gradle 运行时依赖及其传递依赖。`yunsmall/Android-Usbipdcpp` 在现有文档中标为“FD integration reference”；核对是否包含其受版权保护的代码后，再决定如何列入页面，不能把参考项目直接当作已打包库。

完整许可文件、版权声明及必要的源码/构建说明与清单一起核对。此设计稿不以一个简短署名代替对发布材料的核查。

## 交互与验收

- 手机、平板与电视都能用触摸、遥控器方向键或手柄进入列表、打开详情和返回；焦点高亮沿用 About 页现有样式。
- 横竖屏切换后仍留在当前页面或详情，尽量保留滚动位置和焦点；长许可文本可滚动、可选择复制。
- 无网络时可查看全部本地许可文本；外链失败时给出明确提示。
- `usbipdcpp` 在列表和详情里均可见；详情包含名称、可打开的项目地址和 LGPLv3，且版本与构建脚本一致。
- 发布前从最终 APK 检查该页面及随包许可资源，并复核原生库与依赖清单。

## 实施顺序

1. 盘点当前发布变体的依赖和许可文件，确定清单格式及首批条目。
2. 做设置页与 About 的入口、列表和详情页，复用现有颜色、外链与焦点组件。
3. 添加离线文本和多语言 UI 文案；组件名称、URL 和许可标识保持原样。
4. 做一次 APK 级核对，再回复 #656 附上用户可见位置和对应版本。
