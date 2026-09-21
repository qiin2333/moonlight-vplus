package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTemplateTokensTest {
    @Test fun savedTemplatesResolveAfterChangingLanguage() {
        val values = mutableMapOf("RTT сети" to "12", "Скорость" to "8 MB/s")
        PerformanceTemplateTokens.addCanonicalAliases(values)
        for (token in listOf("Net Latency", "Network RTT", "网络延时", "網路延時", "RTT сети")) {
            assertEquals("12", values[PerformanceTemplateTokens.canonicalize(token)])
        }
        for (token in listOf("Bandwidth", "带宽", "頻寬", "Скорость")) {
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
        val values = mutableMapOf("Decoder" to "hardware", "Декодер" to "other")
        PerformanceTemplateTokens.addCanonicalAliases(values)
        assertEquals("hardware", values["Decoder"])
        assertEquals("other", values["Декодер"])
    }
}
