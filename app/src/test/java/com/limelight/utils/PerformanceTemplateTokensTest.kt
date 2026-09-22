package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTemplateTokensTest {
    @Test fun legacyChineseTemplatesResolveWithCanonicalKeys() {
        val values = mutableMapOf("网络延时" to "12", "带宽" to "8 MB/s")
        PerformanceTemplateTokens.addCanonicalAliases(values)
        for (token in listOf("Net Latency", "Network RTT", "网络延时", "網路延時")) {
            assertEquals("12", values[PerformanceTemplateTokens.canonicalize(token)])
        }
        for (token in listOf("Bandwidth", "带宽", "頻寬")) {
            assertEquals("8 MB/s", values[PerformanceTemplateTokens.canonicalize(token)])
        }
    }

    @Test fun oldDefaultAndEditorTemplateNamesStayCompatible() {
        assertEquals("Frame Loss", PerformanceTemplateTokens.canonicalize("Drop Rate"))
        assertEquals("Frame Loss", PerformanceTemplateTokens.canonicalize("丢帧率"))
        assertEquals("Decode Time", PerformanceTemplateTokens.canonicalize("解码时间"))
        assertEquals("Render Latency", PerformanceTemplateTokens.canonicalize("渲染延迟"))
        assertEquals("custom user token", PerformanceTemplateTokens.canonicalize("custom user token"))
    }

    @Test fun localizedAliasesDoNotOverwriteCanonicalValues() {
        val values = mutableMapOf("Decoder" to "hardware", "解码器" to "other")
        PerformanceTemplateTokens.addCanonicalAliases(values)
        assertEquals("hardware", values["Decoder"])
        assertEquals("other", values["解码器"])
    }
}
