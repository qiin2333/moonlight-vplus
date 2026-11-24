package com.limelight.binding.input.advance_setting

import android.view.KeyEvent
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class KeyboardTier {
    POCKET,
    STANDARD,
    STUDIO
}

internal enum class KeyboardPage {
    MAIN,
    NUM,
    NAV
}

internal enum class KeyKind {
    NORMAL,
    MODIFIER,
    SPECIAL,
    ACCENT,
    SPACE
}

internal data class KeySpec(
    val code: Int = 0,
    val label: String,
    val secondary: String? = null,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.NORMAL,
    val page: KeyboardPage? = null,
    val shift: Boolean = false,
    val sendCode: Int = code
) {
    val isPageKey: Boolean get() = page != null
    val isModifier: Boolean get() = kind == KeyKind.MODIFIER
    val popupLabel: String?
        get() {
            if (isPageKey || isModifier) return null
            if (kind == KeyKind.SPACE) return null
            if (label.isEmpty()) return null
            val first = label.substringBefore(' ')
            return if (first.length <= 3) first else null
        }
}

internal data class KeyboardRowSpec(val keys: List<KeySpec>)

internal data class KeyboardMetrics(
    val tier: KeyboardTier,
    val screenWidthDp: Float,
    val screenHeightDp: Float,
    val dockWidthPx: Int,
    val dockHeightPx: Int,
    val contentWidthPx: Int,
    val marginPx: Int,
    val padPx: Int,
    val railHeightPx: Int,
    val railGapPx: Int,
    val gapPx: Float,
    val blockGapPx: Float,
    val keyWidthPx: Float,
    val keyHeightPx: Float,
    val maxKeyHeightDp: Float,
    val fieldWidthPx: Float,
    val fieldHeightPx: Float,
    val gutterPx: Float,
    val alphaColumns: Int,
    val navColumns: Int,
    val rows: Int,
    val tabTextSp: Float
) {
    val totalColumns: Int get() = alphaColumns + navColumns
}

internal object KeyboardGeometry {
    const val MAX_WIDTH_DP = 1280f
    const val MAX_KEY_WIDTH_DP = 56f

    fun tierFor(screenWidthDp: Float): KeyboardTier = when {
        screenWidthDp < 600f -> KeyboardTier.POCKET
        screenWidthDp < 900f -> KeyboardTier.STANDARD
        else -> KeyboardTier.STUDIO
    }

    fun compute(
        screenWidthPx: Float,
        screenHeightPx: Float,
        density: Float,
        mini: Boolean = false,
        keyHeightOverrideDp: Float? = null
    ): KeyboardMetrics {
        val densitySafe = if (density > 0f) density else 1f
        val widthDp = screenWidthPx / densitySafe
        val heightDp = screenHeightPx / densitySafe
        val tier = if (mini) KeyboardTier.POCKET else tierFor(widthDp)
        val marginDp = when {
            mini -> 12f
            tier == KeyboardTier.POCKET -> 6f
            else -> 16f
        }
        val padDp = when (tier) {
            KeyboardTier.POCKET -> 6f
            KeyboardTier.STANDARD -> 10f
            KeyboardTier.STUDIO -> 12f
        }
        val railDp = when (tier) {
            KeyboardTier.POCKET -> 32f
            KeyboardTier.STANDARD -> 36f
            KeyboardTier.STUDIO -> 40f
        }
        val railGapDp = if (tier == KeyboardTier.POCKET) 6f else 8f
        val keyGapDp = when {
            mini -> 2f
            tier == KeyboardTier.POCKET && widthDp < 420f -> 2f
            tier == KeyboardTier.POCKET -> 3f
            else -> 4f
        }
        val blockGapDp = if (tier == KeyboardTier.POCKET) 8f else 10f
        val dockWidthDp = if (mini) {
            min(380f, widthDp - marginDp * 2f).coerceAtLeast(240f)
        } else {
            min(widthDp - marginDp * 2f, MAX_WIDTH_DP).coerceAtLeast(240f)
        }
        val contentWidthDp = dockWidthDp - padDp * 2f
        val rows = if (tier == KeyboardTier.POCKET) 4 else 5
        val alphaColumns = if (tier == KeyboardTier.POCKET) 10 else 15
        val navColumns = if (tier == KeyboardTier.STUDIO) 3 else 0
        val totalColumns = alphaColumns + navColumns
        val blocks = if (navColumns > 0) 2 else 1
        val internalGaps = (totalColumns - blocks) * keyGapDp + (blocks - 1) * blockGapDp
        val rawKeyWidth = (contentWidthDp - internalGaps) / totalColumns
        val keyWidthDp = min(rawKeyWidth, MAX_KEY_WIDTH_DP).coerceAtLeast(1f)
        val fieldWidthDp = totalColumns * keyWidthDp + internalGaps
        val gutterDp = ((contentWidthDp - fieldWidthDp) / 2f).coerceAtLeast(0f)
        val verticalGaps = (rows - 1) * keyGapDp
        val tierMaxKeyHeightDp = if (tier == KeyboardTier.POCKET) 46f else 50f
        val viewportMaxKeyHeightDp = (heightDp - marginDp - railDp - railGapDp - padDp - verticalGaps) / rows
        val maxKeyHeightDp = floor(
            min(tierMaxKeyHeightDp, viewportMaxKeyHeightDp).toDouble()
        ).toFloat().coerceAtLeast(31f)
        val computedKeyHeightDp = floor(
            ((heightDp * 0.64f - railDp - railGapDp - padDp - verticalGaps) / rows).toDouble()
        ).toFloat()
        val keyHeightDp = (keyHeightOverrideDp ?: computedKeyHeightDp).coerceIn(31f, maxKeyHeightDp)
        val tabTextSp = when (tier) {
            KeyboardTier.POCKET -> 9.5f
            KeyboardTier.STANDARD -> 10f
            KeyboardTier.STUDIO -> 10.5f
        }
        val keyHeightPx = keyHeightDp * densitySafe
        val gapPx = keyGapDp * densitySafe
        val padPx = (padDp * densitySafe).toInt()
        val railHeightPx = (railDp * densitySafe).toInt()
        val railGapPx = (railGapDp * densitySafe).toInt()
        val fieldHeightPx = rows * keyHeightPx.roundToInt() + (rows - 1) * gapPx.roundToInt()
        val dockHeightPx = railHeightPx + railGapPx + fieldHeightPx + padPx
        return KeyboardMetrics(
            tier = tier,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            dockWidthPx = (dockWidthDp * densitySafe).toInt(),
            dockHeightPx = dockHeightPx,
            contentWidthPx = (contentWidthDp * densitySafe).toInt(),
            marginPx = (marginDp * densitySafe).toInt(),
            padPx = padPx,
            railHeightPx = railHeightPx,
            railGapPx = railGapPx,
            gapPx = gapPx,
            blockGapPx = blockGapDp * densitySafe,
            keyWidthPx = keyWidthDp * densitySafe,
            keyHeightPx = keyHeightPx,
            maxKeyHeightDp = maxKeyHeightDp,
            fieldWidthPx = fieldWidthDp * densitySafe,
            fieldHeightPx = fieldHeightPx.toFloat(),
            gutterPx = gutterDp * densitySafe,
            alphaColumns = alphaColumns,
            navColumns = navColumns,
            rows = rows,
            tabTextSp = tabTextSp
        )
    }
}

internal object KeyboardRows {
    private const val BACKSPACE = KeyEvent.KEYCODE_DEL
    private const val ENTER = KeyEvent.KEYCODE_ENTER
    private const val SPACE = KeyEvent.KEYCODE_SPACE
    private const val TAB = KeyEvent.KEYCODE_TAB
    private const val CAPS = KeyEvent.KEYCODE_CAPS_LOCK
    private const val SHIFT_LEFT = KeyEvent.KEYCODE_SHIFT_LEFT
    private const val SHIFT_RIGHT = KeyEvent.KEYCODE_SHIFT_RIGHT
    private const val CTRL_LEFT = KeyEvent.KEYCODE_CTRL_LEFT
    private const val CTRL_RIGHT = KeyEvent.KEYCODE_CTRL_RIGHT
    private const val ALT_LEFT = KeyEvent.KEYCODE_ALT_LEFT
    private const val ALT_RIGHT = KeyEvent.KEYCODE_ALT_RIGHT
    private const val META_LEFT = KeyEvent.KEYCODE_META_LEFT
    private const val ARROW_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
    private const val ARROW_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    private const val ARROW_UP = KeyEvent.KEYCODE_DPAD_UP
    private const val ARROW_DOWN = KeyEvent.KEYCODE_DPAD_DOWN

    private fun key(code: Int, label: String, secondary: String? = null, weight: Float = 1f): KeySpec =
        KeySpec(code = code, label = label, secondary = secondary, weight = weight)

    private fun mod(code: Int, label: String, weight: Float): KeySpec =
        KeySpec(code = code, label = label, weight = weight, kind = KeyKind.MODIFIER)

    private fun special(code: Int, label: String, weight: Float = 1f, kind: KeyKind = KeyKind.SPECIAL): KeySpec =
        KeySpec(code = code, label = label, weight = weight, kind = kind)

    private fun page(label: String, page: KeyboardPage, weight: Float): KeySpec =
        KeySpec(label = label, weight = weight, kind = KeyKind.SPECIAL, page = page)

    private val qwerty = listOf(
        key(KeyEvent.KEYCODE_Q, "Q"), key(KeyEvent.KEYCODE_W, "W"), key(KeyEvent.KEYCODE_E, "E"),
        key(KeyEvent.KEYCODE_R, "R"), key(KeyEvent.KEYCODE_T, "T"), key(KeyEvent.KEYCODE_Y, "Y"),
        key(KeyEvent.KEYCODE_U, "U"), key(KeyEvent.KEYCODE_I, "I"), key(KeyEvent.KEYCODE_O, "O"),
        key(KeyEvent.KEYCODE_P, "P")
    )
    private val home = listOf(
        key(KeyEvent.KEYCODE_A, "A"), key(KeyEvent.KEYCODE_S, "S"), key(KeyEvent.KEYCODE_D, "D"),
        key(KeyEvent.KEYCODE_F, "F"), key(KeyEvent.KEYCODE_G, "G"), key(KeyEvent.KEYCODE_H, "H"),
        key(KeyEvent.KEYCODE_J, "J"), key(KeyEvent.KEYCODE_K, "K"), key(KeyEvent.KEYCODE_L, "L")
    )
    private val bottom = listOf(
        key(KeyEvent.KEYCODE_Z, "Z"), key(KeyEvent.KEYCODE_X, "X"), key(KeyEvent.KEYCODE_C, "C"),
        key(KeyEvent.KEYCODE_V, "V"), key(KeyEvent.KEYCODE_B, "B"), key(KeyEvent.KEYCODE_N, "N"),
        key(KeyEvent.KEYCODE_M, "M")
    )
    private val numbers = listOf(
        key(KeyEvent.KEYCODE_1, "1", "!"), key(KeyEvent.KEYCODE_2, "2", "@"),
        key(KeyEvent.KEYCODE_3, "3", "#"), key(KeyEvent.KEYCODE_4, "4", "$"),
        key(KeyEvent.KEYCODE_5, "5", "%"), key(KeyEvent.KEYCODE_6, "6", "^"),
        key(KeyEvent.KEYCODE_7, "7", "&"), key(KeyEvent.KEYCODE_8, "8", "*"),
        key(KeyEvent.KEYCODE_9, "9", "("), key(KeyEvent.KEYCODE_0, "0", ")")
    )

    fun rows(tier: KeyboardTier): List<KeyboardRowSpec> = when (tier) {
        KeyboardTier.POCKET -> pocketRows()
        KeyboardTier.STANDARD -> standardRows()
        KeyboardTier.STUDIO -> studioRows()
    }

    private fun pocketRows(): List<KeyboardRowSpec> = listOf(
        KeyboardRowSpec(qwerty),
        KeyboardRowSpec(home + special(BACKSPACE, "⌫")),
        KeyboardRowSpec(
            listOf(
                mod(SHIFT_LEFT, "⇧", 1.4f)
            ) + bottom + special(ENTER, "↵", 1.6f, KeyKind.ACCENT)
        ),
        KeyboardRowSpec(
            listOf(
                page("?123", KeyboardPage.NUM, 1.2f),
                mod(CTRL_LEFT, "Ctrl", 1.1f),
                KeySpec(code = SPACE, label = "space", weight = 2.9f, kind = KeyKind.SPACE),
                key(KeyEvent.KEYCODE_COMMA, ",", "<"),
                key(KeyEvent.KEYCODE_PERIOD, ".", ">"),
                special(ARROW_LEFT, "←", 1.4f),
                special(ARROW_RIGHT, "→", 1.4f)
            )
        )
    )

    private fun commonTopRows(): List<KeyboardRowSpec> = listOf(
        KeyboardRowSpec(
            listOf(key(KeyEvent.KEYCODE_GRAVE, "\u0060", "~")) + numbers + listOf(
                key(KeyEvent.KEYCODE_MINUS, "-", "_"),
                key(KeyEvent.KEYCODE_EQUALS, "=", "+"),
                special(BACKSPACE, "⌫", 2f)
            )
        ),
        KeyboardRowSpec(
            listOf(special(TAB, "⇥ Tab", 1.5f)) + qwerty + listOf(
                key(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "{"),
                key(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "}"),
                key(KeyEvent.KEYCODE_BACKSLASH, "\\", "|", 1.5f)
            )
        ),
        KeyboardRowSpec(
            listOf(special(CAPS, "⇪ Caps", 1.75f)) + home + listOf(
                key(KeyEvent.KEYCODE_SEMICOLON, ";", ":"),
                key(KeyEvent.KEYCODE_APOSTROPHE, "'", "\""),
                special(ENTER, "↵ Enter", 2.25f, KeyKind.ACCENT)
            )
        )
    )

    private fun standardRows(): List<KeyboardRowSpec> {
        val top = commonTopRows()
        return top + listOf(
            KeyboardRowSpec(
                listOf(mod(SHIFT_LEFT, "⇧ Shift", 2.25f)) + bottom + listOf(
                    key(KeyEvent.KEYCODE_COMMA, ",", "<"),
                    key(KeyEvent.KEYCODE_PERIOD, ".", ">"),
                    key(KeyEvent.KEYCODE_SLASH, "/", "?"),
                    mod(SHIFT_RIGHT, "⇧ Shift", 1.75f),
                    special(ARROW_UP, "↑")
                )
            ),
            KeyboardRowSpec(
                listOf(
                    mod(CTRL_LEFT, "Ctrl", 1.4f),
                    mod(META_LEFT, "Win", 1.1f),
                    mod(ALT_LEFT, "Alt", 1.1f),
                    KeySpec(code = SPACE, label = "space", weight = 5.9f, kind = KeyKind.SPACE),
                    mod(ALT_RIGHT, "Alt", 1.1f),
                    mod(CTRL_RIGHT, "Ctrl", 1.4f),
                    special(ARROW_LEFT, "←"),
                    special(ARROW_DOWN, "↓"),
                    special(ARROW_RIGHT, "→")
                )
            )
        )
    }

    private fun studioRows(): List<KeyboardRowSpec> {
        val top = commonTopRows()
        return top + listOf(
            KeyboardRowSpec(
                listOf(mod(SHIFT_LEFT, "⇧ Shift", 2.25f)) + bottom + listOf(
                    key(KeyEvent.KEYCODE_COMMA, ",", "<"),
                    key(KeyEvent.KEYCODE_PERIOD, ".", ">"),
                    key(KeyEvent.KEYCODE_SLASH, "/", "?"),
                    mod(SHIFT_RIGHT, "⇧ Shift", 2.75f)
                )
            ),
            KeyboardRowSpec(
                listOf(
                    mod(CTRL_LEFT, "Ctrl", 1.4f),
                    mod(META_LEFT, "Win", 1.1f),
                    mod(ALT_LEFT, "Alt", 1.1f),
                    KeySpec(code = SPACE, label = "space", weight = 8.9f, kind = KeyKind.SPACE),
                    mod(ALT_RIGHT, "Alt", 1.1f),
                    mod(CTRL_RIGHT, "Ctrl", 1.4f)
                )
            )
        )
    }

    fun navCells(): List<KeySpec?> = listOf(
        special(KeyEvent.KEYCODE_ESCAPE, "Esc"),
        special(KeyEvent.KEYCODE_SYSRQ, "PrtSc"),
        special(KeyEvent.KEYCODE_BREAK, "Pause"),
        special(KeyEvent.KEYCODE_INSERT, "Ins"),
        special(KeyEvent.KEYCODE_MOVE_HOME, "Home"),
        special(KeyEvent.KEYCODE_PAGE_UP, "PgUp"),
        special(KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
        special(KeyEvent.KEYCODE_MOVE_END, "End"),
        special(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"),
        null,
        special(ARROW_UP, "↑"),
        null,
        special(ARROW_LEFT, "←"),
        special(ARROW_DOWN, "↓"),
        special(ARROW_RIGHT, "→")
    )
}