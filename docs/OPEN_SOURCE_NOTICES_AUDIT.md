# Android 开源许可清单核查（2026-09-24）

核查对象：`nonRootDebug` APK、`app/src/main/assets/open_source_notices/notices.json`、
原生模块构建脚本、Git 子模块及 `gradle/libs.versions.toml`。
用户界面目前列出 22 项。此清单尚不能作为完整的发布许可审查结论。

## 已核实并加入页面

- USB/IP：usbipdcpp 固定提交、libusb 1.0.29、Asio 1.30.2、spdlog 1.15.3、其内置 fmt。`usbipdcpp` 的 LGPLv3 附加条款与其引用的 GPLv3 正文一并提供。
- Moonlight 核心：`moonlight-common-c` GPLv3、内含 ENet 与 nanors；静态链接的 OpenSSL 1.1.1q、Opus 1.5.2。OpenSSL 使用 1.1.1q 的 OpenSSL/SSLeay 双许可文本，不能标为 Apache-2.0。
- 帧生成：`lsfg-vk-android`、`lsfg-vk-framegen`、volk、dxbc、pe-parse、toml11、AMD FSR shader 头文件。提交号来自已签出的子模块；许可文本来自各源码目录。
- 音频触觉：CI 固定的 SDK 0.5.14 (`b3f97c3`)、内含的 libfvad/WebRTC VAD，以及 libfvad 许可和 WebRTC PATENTS。许可正文已与该固定提交核对。
- APK 资源/预编译库：Inter 4.1 字体子集、iperf3 3.1.3、EasyTier 2.6.4。后三者版本分别来自字体构建记录或二进制内嵌版本字符串；EasyTier 与 iperf3 的许可文本取自对应上游版本。

## 发布前仍需完成

1. **Gradle 运行时依赖。** `app/build.gradle` 的直接依赖及其传递依赖尚未逐件核对并加入页面，例如 Bouncy Castle、OkHttp/Okio、Glide、JCodec、JmDNS、ZXing、Gson、Kotlin、AndroidX/Compose、Google/Firebase 等。不能把 BOM、测试依赖和仅用于构建的插件当作 APK 内容；应以发布变体解析出的运行时构件及最终 APK 核对。
2. **预编译库来源。** `app/src/main/jniLibs` 被 Git 忽略。当前二进制可识别 EasyTier 2.6.4、iperf3 3.1.3，但仍需要记录其构建来源、补丁和可重建方式；Opus 静态库也需要这份记录。二进制字符串只证明内嵌版本声明，不能证明未修改的上游源码。
3. **LGPL/GPL 分发材料。** usbipdcpp、libusb、EasyTier 与 `moonlight-common-c` 需要结合实际静态链接方式、修改和发布渠道复核对应源码、构建说明及适用时的重新链接材料。界面展示许可文本只是其中一部分。
4. **发布变体复核。** 在最终发布 APK/AAB 上重复原生库、资源和运行时依赖盘点。当前检查使用的是 `nonRootDebug`，不能代替最终发布构件。
5. **本地构建差异。** 清单版本与 Android CI 固定的 `b3f97c3bb7500ea7b1985aea568e5c7b40308d3b`（`0.5.14`）一致；本地 SDK 检出目前是 `0.6.0`。使用不同 SDK 检出构建 APK 时，应同步更新清单或在构建时生成对应版本，避免显示错误的版本。

## 本次验证

- `:app:assembleNonRootDebug --offline` 成功。
- `notices.json` 22 个稳定 ID 无重复，每项引用的许可文件均存在；APK 内含清单和新增文本。
- APK 已安装到连接的 Meizu 17。
