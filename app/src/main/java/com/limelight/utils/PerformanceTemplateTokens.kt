package com.limelight.utils

/** Resolves persisted Crown placeholders independently of the current UI language. */
object PerformanceTemplateTokens {
    private val aliases = mapOf(
        "Bandwidth" to "Bandwidth",
        "Decode Time" to "Decode Time",
        "Decoder" to "Decoder",
        "Drop Rate" to "Frame Loss",
        "FG" to "FG",
        "FPS" to "FPS",
        "Frame Loss" to "Frame Loss",
        "HDR Format" to "HDR Format",
        "HDR 格式" to "HDR Format",
        "Host Latency" to "Host Latency",
        "Net Latency" to "Network RTT",
        "Network RTT" to "Network RTT",
        "RTT сети" to "Network RTT",
        "Rd" to "Rd",
        "Render Latency" to "Render Latency",
        "Resolution" to "Resolution",
        "Rx" to "Rx",
        "Декодер" to "Decoder",
        "Декодирование" to "Decode Time",
        "Задержка ПК" to "Host Latency",
        "Отрисовка" to "Render Latency",
        "Потери кадров" to "Frame Loss",
        "Разрешение" to "Resolution",
        "Скорость" to "Bandwidth",
        "Формат HDR" to "HDR Format",
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
    fun canonicalize(token: String): String = aliases[token] ?: token

    fun addCanonicalAliases(values: MutableMap<String, String>) {
        for ((key, value) in values.toMap()) {
            values.putIfAbsent(canonicalize(key), value)
        }
    }
}
