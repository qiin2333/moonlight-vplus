#!/usr/bin/env bash
set -euo pipefail

DATE=$(date +'%Y-%m-%d')
REF_NAME="${GITHUB_REF:-}"
REF_NAME="${REF_NAME#refs/tags/}"
V="${VERSION_NAME:-$REF_NAME}"
V="${V#v}"
CUR_TAG="${REF_NAME:-v${V}}"
PREV_TAG=$(git tag --sort=-creatordate | sed -n '2p' || true)
APK_NAME="Moonlight.V+.${V}.apk"
APK_SHA256="$(sha256sum "$REL_APK" | awk '{print $1}')"

if [ "$V" = "12.10.1" ]; then
  cat > release_notes.md <<EOF_NOTES
## Moonlight V+ ${V}

这版不是小补丁，是 Moonlight V+ 往“高级玩家工具箱”方向迈的一大步。备份同步、开发者功能解锁、Android 端 LSFG 插帧都来了，之前只能在实验室里折腾的东西，现在终于可以拿出来给你们试了。

### 重点改进
- 新增同设备配置备份与同步：串流设置、应用预设、场景、自定义分辨率、Crown 配置、配对身份都能一起备份。清数据、重装、换目录同步时，不用再从零把每个设置重新拧一遍。
- 新增自动本地快照和外部目录镜像：可以把加密备份写到系统目录或云盘同步目录里，后台自动读、自动恢复、自动写回。配置丢失这件事，以后别来装可怜。
- 新增开发者功能解锁：通过 GitHub Star 验证后解锁实验功能。小黄鸭插帧先放在这里，后续更激进的能力也会走这条通道。
- 新增小黄鸭 / LSFG 插帧入口：支持导入 Lossless.dll，开启后可在合适设备上把 60 FPS 串流补到接近 120 FPS 输出，高刷屏终于不只是摆设。

### 插帧专项
- 优化 1080p、2K、超宽和高分辨率场景的插帧尺寸策略，性能、均衡、清晰档改成按原始分辨率比例缩放，画质和耗时更可控。
- 加入弱网自适应补帧：主机传输帧率追不上目标帧率时，尽量用补帧填住节奏，而不是让画面一路掉下去。
- 换用 FSR1 EASU + RCAS 做补帧放大：比之前的简单放大更清晰，细节边缘更稳，FSR 的代码也拆成可复用模块，后面做独立超分不需要重新开炉。
- 改善 HDR 插帧链路：HDR10/PQ 和 HLG 走更接近直通的输出路径，减少偏灰、偏暗、过饱和这些让人血压上来的问题。
- 优化输出节奏和统计：插帧输出、fallback、真实帧路径分开统计，性能 overlay 更能反映实际情况，不再靠玄学猜补帧有没有工作。
- 修复旋转屏幕、退出串流、大分辨率全屏等场景下的卡住、闪烁和画面冻结问题。

### 其他
- 新增串流菜单里的麦克风操作模式。
- 加固备份恢复流程，恢复配对身份后会提示重启应用，避免服务还拿着旧身份硬装正常。

### 使用提示
- 插帧仍是开发者功能，需要先在帮助分类里解锁开发者功能，再导入 Lossless.dll。
- 建议配合 120Hz 屏幕、60 FPS 串流预设使用；设备性能不足时请从性能档或均衡档开始。
- HDR、HLG、弱网补帧会受设备 GPU、系统显示管线和网络波动影响，看到异常请带日志反馈。

### 下载与校验
- APK 文件：${APK_NAME}
- SHA256: ${APK_SHA256}

如果遇到问题，欢迎在 Issues 反馈。发布于 ${DATE}。
EOF_NOTES
  exit 0
fi

{
  echo "## Moonlight V+ ${V}"
  echo ""
  echo "感谢使用 Moonlight V+。"
  echo ""
  echo "### 更新内容"
  if [ -n "$PREV_TAG" ]; then
    git log --pretty=format:'- %s' "${PREV_TAG}..${CUR_TAG}" --no-merges || true
  else
    echo "- 初始发布"
  fi
  echo ""
  echo "### 下载与校验"
  echo "- APK 文件：${APK_NAME}"
  echo "- SHA256: ${APK_SHA256}"
  echo ""
  echo "---"
  echo "如果遇到问题，请在 Issues 反馈。发布于 ${DATE}。"
} > release_notes.md
