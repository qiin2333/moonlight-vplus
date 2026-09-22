package com.limelight.utils

/** Maps placeholders from released legacy Crown templates, primarily Chinese, to stable tokens. */
object PerformanceTemplateTokens {
    private val legacyAliases = mapOf(
        "Drop Rate" to "Frame Loss",
        "HDR 格式" to "HDR Format",
        "Net Latency" to "Network RTT",
        "丟幀率" to "Frame Loss",
        "丢帧率" to "Frame Loss",
        "主机延时" to "Host Latency",
        "主機延時" to "Host Latency",
        "分辨率" to "Resolution",
        "带宽" to "Bandwidth",
        "帧率" to "FPS",
        "幀率" to "FPS",
        "渲染延迟" to "Render Latency",
        "渲染延遲" to "Render Latency",
        "網路延時" to "Network RTT",
        "网络延时" to "Network RTT",
        "解析度" to "Resolution",
        "解码器" to "Decoder",
        "解码时间" to "Decode Time",
        "解碼器" to "Decoder",
        "解碼時間" to "Decode Time",
        "頻寬" to "Bandwidth",
    )

    @JvmStatic
    fun canonicalize(token: String): String = legacyAliases[token] ?: token

    fun addCanonicalAliases(values: MutableMap<String, String>) {
        for ((key, value) in values.toMap()) {
            values.putIfAbsent(canonicalize(key), value)
        }
    }
}
