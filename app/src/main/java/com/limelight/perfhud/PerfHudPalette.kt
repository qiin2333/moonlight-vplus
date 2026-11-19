package com.limelight.perfhud

/**
 * HUD 与其弹出卡片共用的配色（Thor palette, values/thor_tokens.xml）。底色、描边、文字取自应用深色外壳 ui_shell_*（values-night），
 * 无论日夜模式都按深色绘制，保证压在视频上可读；交互强调色在运行时读取 appAccent（UiHelper.accentColor）。
 * 绿 / 黄 / 红状态色和延迟链四段分类色是读数用的，不跟随主题。
 */
object PerfHudPalette {
    /** HUD 本体底色：比卡片更深，压在视频上也清晰。 */
    const val BG_RGB = 0x101418
    /** 弹出卡片底色，对应 ui_shell_surface_elevated（深色）。 */
    const val SHEET_RGB = 0x222B33
    const val TXT = 0xFFF1F4F6.toInt()
    const val BODY = 0xE6F1F4F6.toInt()
    const val DIM = 0xFFA3AFB9.toInt()
    const val MUTE = 0x99A3AFB9.toInt()
    /** 对应 ui_shell_outline（深色）。 */
    const val OUTLINE = 0xFF303B44.toInt()
    const val DIVIDER = 0x26FFFFFF
    const val HAIRLINE = 0x1AFFFFFF
    const val TRACK = 0x14FFFFFF
    const val TILE = 0x0FFFFFFF
    const val SHADOW = 0x99000000.toInt()
    const val OK = 0xFF72C99A.toInt()
    const val WARN = 0xFFE9BB69.toInt()
    const val BAD = 0xFFE47777.toInt()
    const val NET = 0xFF6FA8FF.toInt()
    const val HOST = 0xFFB48CFF.toInt()
    const val DEC = 0xFF4FD1C5.toInt()
    const val RND = 0xFFF5A3C7.toInt()
    const val MOON = 0xFFEDE6C8.toInt()
    /** 读不到主题时的强调色，等于深色 ui_shell_accent。 */
    const val ACCENT_FALLBACK = 0xFF55B8D4.toInt()

    fun severity(s: PerfSeverity): Int = when (s) {
        PerfSeverity.OK -> OK
        PerfSeverity.WARN -> WARN
        PerfSeverity.BAD -> BAD
    }

    /** 次要指标：正常时退成灰色不抢眼，出问题才显示黄 / 红。 */
    fun quiet(s: PerfSeverity): Int = if (s == PerfSeverity.OK) DIM else severity(s)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
