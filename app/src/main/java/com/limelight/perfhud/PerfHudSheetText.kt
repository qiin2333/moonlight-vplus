package com.limelight.perfhud

/** 卡片正文的结构块。说明文案仍是普通字符串资源，由 [PerfHudSheetText.parse] 自动拆成这些块。 */
sealed class SheetBlock {
    data class Paragraph(val text: String) : SheetBlock()
    data class Heading(val text: String, val caption: String? = null) : SheetBlock()
    data class Bullet(val text: String) : SheetBlock()
    data class Row(val key: String, val value: String, val valueColor: Int? = null, val swatch: Int? = null) : SheetBlock()
    data class Tip(val text: String) : SheetBlock()
    object Divider : SheetBlock()
    object Gap : SheetBlock()
}

object PerfHudSheetText {
    private const val MAX_KEY = 18
    private const val MAX_VALUE = 32
    private const val MAX_HEADING = 24
    private val DIVIDER = Regex("^[─━—\\-]{4,}$")

    fun parse(text: String): List<SheetBlock> {
        val out = ArrayList<SheetBlock>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val block = when {
                line.isEmpty() -> SheetBlock.Gap
                DIVIDER.matches(line) -> SheetBlock.Divider
                line.startsWith("•") -> SheetBlock.Bullet(line.removePrefix("•").trim())
                line.startsWith("💡") -> SheetBlock.Tip(line)
                isHeading(line) -> SheetBlock.Heading(line.dropLast(1).trim())
                else -> row(line) ?: SheetBlock.Paragraph(line)
            }
            if (block == SheetBlock.Gap && (out.isEmpty() || out.last() == SheetBlock.Gap)) continue
            out += block
        }
        while (out.lastOrNull() == SheetBlock.Gap) out.removeAt(out.lastIndex)
        // “Rx FPS：接收帧率” 后面紧跟要点时，它其实是小节标题而不是数据行
        for (i in 0 until out.lastIndex) {
            val r = out[i]
            if (r is SheetBlock.Row && out[i + 1] is SheetBlock.Bullet) out[i] = SheetBlock.Heading(r.key, r.value)
        }
        return out
    }

    /** 把 “键：值” 拆成表格行；不像键值对的长句返回 null。 */
    fun row(line: String, valueColor: Int? = null, swatch: Int? = null): SheetBlock.Row? {
        val idx = listOf(line.indexOf('：'), line.indexOf(": ")).filter { it > 0 }.minOrNull() ?: return null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (key.isEmpty() || value.isEmpty() || key.length > MAX_KEY || value.length > MAX_VALUE) return null
        return SheetBlock.Row(key, value, valueColor, swatch)
    }

    private fun isHeading(line: String): Boolean =
        line.length <= MAX_HEADING && (line.endsWith('：') || line.endsWith(':'))

    /** “📊 帧率信息” → (“📊”, “帧率信息”)；标题不以 emoji 开头时 icon 为 null。 */
    fun splitIcon(title: String): Pair<String?, String> {
        val space = title.indexOf(' ')
        if (space <= 0) return null to title
        val head = title.substring(0, space)
        return if (head.none { it.isLetterOrDigit() }) head to title.substring(space + 1).trim() else null to title
    }
}
