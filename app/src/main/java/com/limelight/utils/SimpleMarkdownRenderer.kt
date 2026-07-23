package com.limelight.utils

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import com.limelight.handbook.HandbookUrlPolicy

/**
 * 将 GitHub release body 的 Markdown 渲染为少女清新手账风 SpannableString。
 * 标题用 ┃ 竖线装饰如手账 washi tape，列表用 ◦ 空心圆，分段用花朵点缀。
 */
object SimpleMarkdownRenderer {

    private const val BULLET_SYMBOL = "◦ "
    private const val SECTION_DIVIDER = "· · · ✿ · · ·"

    fun render(
        markdown: String?,
        accentColor: Int,
        onDocumentLink: ((String) -> Unit)? = null
    ): CharSequence {
        if (markdown.isNullOrEmpty()) return ""

        val builder = SpannableStringBuilder()
        val lines = markdown.split("\n")
        var previousWasEmpty = false
        var hadContent = false

        for (rawLine in lines) {
            val line = rawLine.trim()

            if (line.isEmpty()) {
                if (builder.isNotEmpty() && !previousWasEmpty) {
                    builder.append("\n")
                }
                previousWasEmpty = true
                continue
            }
            previousWasEmpty = false

            when {
                line.startsWith("###") -> {
                    if (hadContent) appendDivider(builder, accentColor)
                    appendHeader(
                        builder,
                        processInlineStyles(
                            line.replaceFirst("^#{1,6}\\s*".toRegex(), ""),
                            accentColor,
                            onDocumentLink
                        ),
                        1.0f,
                        accentColor
                    )
                }
                line.startsWith("##") -> {
                    if (hadContent) appendDivider(builder, accentColor)
                    appendHeader(
                        builder,
                        processInlineStyles(
                            line.replaceFirst("^#{1,6}\\s*".toRegex(), ""),
                            accentColor,
                            onDocumentLink
                        ),
                        1.1f,
                        accentColor
                    )
                }
                line.startsWith("#") -> {
                    if (hadContent) appendDivider(builder, accentColor)
                    appendHeader(
                        builder,
                        processInlineStyles(
                            line.replaceFirst("^#{1,6}\\s*".toRegex(), ""),
                            accentColor,
                            onDocumentLink
                        ),
                        1.2f,
                        accentColor
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    appendBullet(
                        builder,
                        processInlineStyles(
                            line.substring(2).trim(),
                            accentColor,
                            onDocumentLink
                        ),
                        accentColor
                    )
                }
                else -> {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(processInlineStyles(line, accentColor, onDocumentLink))
                }
            }
            hadContent = true
        }

        // 去除尾部空行
        while (builder.isNotEmpty() && builder[builder.length - 1] == '\n') {
            builder.delete(builder.length - 1, builder.length)
        }

        return builder
    }

    private fun appendHeader(
        builder: SpannableStringBuilder,
        text: CharSequence,
        sizeMultiplier: Float,
        color: Int
    ) {
        if (builder.isNotEmpty()) builder.append("\n")

        val start = builder.length
        builder.append("┃")
        val textStart = builder.length
        builder.append(text)
        val end = builder.length

        builder.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(sizeMultiplier), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(color), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("\n")
    }

    private fun appendDivider(builder: SpannableStringBuilder, color: Int) {
        if (builder.isNotEmpty() && builder[builder.length - 1] != '\n') {
            builder.append("\n")
        }
        val start = builder.length
        builder.append(SECTION_DIVIDER)
        val end = builder.length
        builder.setSpan(ForegroundColorSpan(color and 0x55FFFFFF or 0x55000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("\n")
    }

    private fun appendBullet(builder: SpannableStringBuilder, text: CharSequence, accentColor: Int) {
        if (builder.isNotEmpty() && builder[builder.length - 1] != '\n') {
            builder.append("\n")
        }
        val start = builder.length

        val symbolStart = builder.length
        builder.append(BULLET_SYMBOL)
        val symbolEnd = builder.length
        builder.setSpan(ForegroundColorSpan(accentColor), symbolStart, symbolEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        builder.append(text)
        builder.append("\n")
        val end = builder.length
        builder.setSpan(LeadingMarginSpan.Standard(16, 32), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun processInlineStyles(
        text: String,
        accentColor: Int,
        onDocumentLink: ((String) -> Unit)?
    ): CharSequence {
        val result = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val boldEnd = text.indexOf("**", i + 2)
                if (boldEnd >= 0) {
                    val spanStart = result.length
                    result.append(
                        processInlineStyles(
                            text.substring(i + 2, boldEnd),
                            accentColor,
                            onDocumentLink
                        )
                    )
                    result.setSpan(
                        StyleSpan(Typeface.BOLD),
                        spanStart,
                        result.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    i = boldEnd + 2
                    continue
                }
            }

            val markdownLink = parseMarkdownLink(text, i)
            if (markdownLink != null) {
                val spanStart = result.length
                result.append(
                    processInlineStyles(markdownLink.label, accentColor, onDocumentLink)
                )
                addDocumentLinkSpan(
                    result,
                    spanStart,
                    result.length,
                    markdownLink.url,
                    accentColor,
                    onDocumentLink
                )
                i = markdownLink.endIndex
                continue
            }

            if (text.regionMatches(i, "https://", 0, "https://".length, ignoreCase = true)) {
                val urlEnd = findBareUrlEnd(text, i)
                val url = text.substring(i, urlEnd)
                val spanStart = result.length
                result.append(url)
                addDocumentLinkSpan(
                    result,
                    spanStart,
                    result.length,
                    url,
                    accentColor,
                    onDocumentLink
                )
                i = urlEnd
                continue
            }

            result.append(text[i])
            i++
        }
        return result
    }

    private fun addDocumentLinkSpan(
        builder: SpannableStringBuilder,
        start: Int,
        end: Int,
        url: String,
        accentColor: Int,
        onDocumentLink: ((String) -> Unit)?
    ) {
        if (start == end ||
            onDocumentLink == null ||
            HandbookUrlPolicy.parse(url) == null
        ) {
            return
        }
        builder.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onDocumentLink(url)
                }

                override fun updateDrawState(drawState: TextPaint) {
                    drawState.color = accentColor
                    drawState.isUnderlineText = true
                }
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun parseMarkdownLink(text: String, start: Int): MarkdownLink? {
        if (text[start] != '[') return null
        val labelEnd = text.indexOf(']', start + 1)
        if (labelEnd <= start + 1 ||
            labelEnd + 1 >= text.length ||
            text[labelEnd + 1] != '('
        ) {
            return null
        }
        val urlEnd = text.indexOf(')', labelEnd + 2)
        if (urlEnd < 0) return null
        val url = text.substring(labelEnd + 2, urlEnd).trim()
        if (url.isEmpty()) return null
        return MarkdownLink(
            label = text.substring(start + 1, labelEnd),
            url = url,
            endIndex = urlEnd + 1
        )
    }

    private fun findBareUrlEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length &&
            !text[end].isWhitespace() &&
            text[end] !in BARE_URL_TERMINATORS
        ) {
            end++
        }
        while (end > start && text[end - 1] in BARE_URL_TRAILING_PUNCTUATION) {
            end--
        }
        return end
    }

    private data class MarkdownLink(
        val label: String,
        val url: String,
        val endIndex: Int
    )

    private val BARE_URL_TERMINATORS = setOf('<', '>', '"', '\'', ')', ']', '}')
    private val BARE_URL_TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?')
}
