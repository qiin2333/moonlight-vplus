package com.limelight.perfhud

import java.util.Locale

// HUD 固定用 Locale.US 保证数字对齐；卡片正文可传入系统 Locale。

internal fun fps0(v: Float, locale: Locale = Locale.US): String = String.format(locale, "%.0f", v)

internal fun fpsText(v: Float): String = if (v > 0f) fps0(v) else "--"

internal fun num0(v: Float): String = String.format(Locale.US, "%.0f", v)

internal fun num1(v: Float): String = String.format(Locale.US, "%.1f", v)

internal fun ms0(v: Float): String = num0(v) + " ms"

internal fun ms1(v: Float, locale: Locale = Locale.US): String = String.format(locale, "%.1f ms", v)

internal fun pct(v: Float): String = String.format(Locale.US, if (v >= 10f) "%.0f%%" else "%.1f%%", v)
