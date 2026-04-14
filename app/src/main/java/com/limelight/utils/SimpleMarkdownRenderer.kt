package com.limelight.utils

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * 将 GitHub release body 的 Markdown 渲染为少女清新手账风 SpannableString。
 * 标题用 ┃ 竖线装饰如手账 washi tape，列表用 ◦ 空心圆，分段用花朵点缀。
 */
object SimpleMarkdownRenderer {

    private const val BULLET_SYMBOL = "◦ "
    private const val SECTION_DIVIDER = "· · · ✿ · · ·"

    fun render(markdown: String?, accentColor: Int): CharSequence {
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
                    appendHeader(builder, line.replaceFirst("^#{1,6}\\s*".toRegex(), ""), 1.0f, accentColor)
                }
                line.startsWith("##") -> {
                    if (hadContent) appendDivider(builder, accentColor)
                    appendHeader(builder, line.replaceFirst("^#{1,6}\\s*".toRegex(), ""), 1.1f, accentColor)
                }
                line.startsWith("#") -> {
                    if (hadContent) appendDivider(builder, accentColor)
                    appendHeader(builder, line.replaceFirst("^#{1,6}\\s*".toRegex(), ""), 1.2f, accentColor)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    appendBullet(builder, processInlineStyles(line.substring(2).trim()), accentColor)
                }
                else -> {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(processInlineStyles(line))
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

    private fun appendHeader(builder: SpannableStringBuilder, text: String, sizeMultiplier: Float, color: Int) {
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

    private fun processInlineStyles(text: String): CharSequence {
        val result = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val boldStart = text.indexOf("**", i)
            if (boldStart == -1) {
                result.append(text, i, text.length)
                break
            }
            val boldEnd = text.indexOf("**", boldStart + 2)
            if (boldEnd == -1) {
                result.append(text, i, text.length)
                break
            }
            result.append(text, i, boldStart)
            val spanStart = result.length
            result.append(text, boldStart + 2, boldEnd)
            result.setSpan(StyleSpan(Typeface.BOLD), spanStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = boldEnd + 2
        }
        return result
    }
}
