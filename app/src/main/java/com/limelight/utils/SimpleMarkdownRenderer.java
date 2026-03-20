package com.limelight.utils;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

/**
 * 将 GitHub release body 的 Markdown 渲染为少女清新手账风 SpannableString。
 * 标题用 ┃ 竖线装饰如手账 washi tape，列表用 ◦ 空心圆，分段用花朵点缀。
 */
public class SimpleMarkdownRenderer {

    private static final String BULLET_SYMBOL = "◦ ";
    private static final String SECTION_DIVIDER = "· · · ✿ · · ·";

    public static CharSequence render(String markdown, int accentColor) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = markdown.split("\n");
        boolean previousWasEmpty = false;
        boolean hadContent = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                if (builder.length() > 0 && !previousWasEmpty) {
                    builder.append("\n");
                }
                previousWasEmpty = true;
                continue;
            }
            previousWasEmpty = false;

            // 标题
            if (line.startsWith("###")) {
                if (hadContent) appendDivider(builder, accentColor);
                appendHeader(builder, line.replaceFirst("^#{1,6}\\s*", ""), 1.0f, accentColor);
            } else if (line.startsWith("##")) {
                if (hadContent) appendDivider(builder, accentColor);
                appendHeader(builder, line.replaceFirst("^#{1,6}\\s*", ""), 1.1f, accentColor);
            } else if (line.startsWith("#")) {
                if (hadContent) appendDivider(builder, accentColor);
                appendHeader(builder, line.replaceFirst("^#{1,6}\\s*", ""), 1.2f, accentColor);
            }
            // 列表项
            else if (line.startsWith("- ") || line.startsWith("* ")) {
                appendBullet(builder, processInlineStyles(line.substring(2).trim()), accentColor);
            }
            // 普通文本
            else {
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(processInlineStyles(line));
            }
            hadContent = true;
        }

        // 去除尾部空行
        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\n') {
            builder.delete(builder.length() - 1, builder.length());
        }

        return builder;
    }

    private static void appendHeader(SpannableStringBuilder builder, String text, float sizeMultiplier, int color) {
        if (builder.length() > 0) {
            builder.append("\n");
        }
        int start = builder.length();
        // 手账风：竖线 + 标题文字
        builder.append("┃");
        int textStart = builder.length();
        builder.append(text);
        int end = builder.length();

        // 竖线用强调色
        builder.setSpan(new ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 文字粗体 + 大小 + 颜色
        builder.setSpan(new StyleSpan(Typeface.BOLD), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new RelativeSizeSpan(sizeMultiplier), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(color), textStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("\n");
    }

    private static void appendDivider(SpannableStringBuilder builder, int color) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append("\n");
        }
        int start = builder.length();
        builder.append(SECTION_DIVIDER);
        int end = builder.length();
        builder.setSpan(new ForegroundColorSpan(color & 0x55FFFFFF | 0x55000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("\n");
    }

    private static void appendBullet(SpannableStringBuilder builder, CharSequence text, int accentColor) {
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append("\n");
        }
        int start = builder.length();

        // ◦ 空心圆用淡强调色
        int symbolStart = builder.length();
        builder.append(BULLET_SYMBOL);
        int symbolEnd = builder.length();
        builder.setSpan(new ForegroundColorSpan(accentColor), symbolStart, symbolEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        builder.append(text);
        builder.append("\n");
        int end = builder.length();
        builder.setSpan(new LeadingMarginSpan.Standard(16, 32), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static CharSequence processInlineStyles(String text) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            int boldStart = text.indexOf("**", i);
            if (boldStart == -1) {
                result.append(text.substring(i));
                break;
            }
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd == -1) {
                result.append(text.substring(i));
                break;
            }
            result.append(text, i, boldStart);
            int spanStart = result.length();
            result.append(text, boldStart + 2, boldEnd);
            result.setSpan(new StyleSpan(Typeface.BOLD), spanStart, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = boldEnd + 2;
        }
        return result;
    }
}
