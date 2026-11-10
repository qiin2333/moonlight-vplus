package com.limelight.perfhud

/** HUD 与其弹出卡片共用的配色，保证两者是同一套视觉语言。 */
object PerfHudPalette {
    const val BG_RGB = 0x0E0E10
    const val TXT = 0xFFF2F2F5.toInt()
    const val BODY = 0xE0F2F2F5.toInt()
    const val DIM = 0xB3F2F2F5.toInt()
    const val MUTE = 0x73F2F2F5
    const val DIVIDER = 0x26FFFFFF
    const val HAIRLINE = 0x1AFFFFFF
    const val TRACK = 0x14FFFFFF
    const val TILE = 0x12FFFFFF
    const val SHADOW = 0x99000000.toInt()
    const val OK = 0xFF5BE49B.toInt()
    const val WARN = 0xFFFFC857.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    const val NET = 0xFF6FA8FF.toInt()
    const val HOST = 0xFFB48CFF.toInt()
    const val DEC = 0xFF4FD1C5.toInt()
    const val RND = 0xFFF5A3C7.toInt()
    const val MOON = 0xFFEDE6C8.toInt()

    fun severity(s: PerfSeverity): Int = when (s) {
        PerfSeverity.OK -> OK
        PerfSeverity.WARN -> WARN
        PerfSeverity.BAD -> BAD
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
