package com.limelight.binding.input.advance_setting

import android.view.KeyEvent

internal sealed class KeyboardBlockSpec {
    data class Rows(val units: Int, val rows: List<KeyboardRowSpec>) : KeyboardBlockSpec()

    data class Grid(
        val units: Int,
        val columns: Int,
        val rows: Int,
        val cells: List<KeyboardGridCell>
    ) : KeyboardBlockSpec()
}

internal data class KeyboardGridCell(
    val spec: KeySpec?,
    val column: Int,
    val row: Int,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1
)

internal data class KeyboardPageSpec(
    val rows: Int,
    val blocks: List<KeyboardBlockSpec>
) {
    val units: Int get() = blocks.sumOf { block ->
        when (block) {
            is KeyboardBlockSpec.Rows -> block.units
            is KeyboardBlockSpec.Grid -> block.units
        }
    }

}

internal object KeyboardPageLayouts {
    private const val SHIFTED_CODE_BASE = 1000
    private const val BACKSPACE = KeyEvent.KEYCODE_DEL
    private const val ENTER = KeyEvent.KEYCODE_ENTER
    private const val SPACE = KeyEvent.KEYCODE_SPACE
    private const val TAB = KeyEvent.KEYCODE_TAB
    private const val ESC = KeyEvent.KEYCODE_ESCAPE
    private const val DEL = KeyEvent.KEYCODE_FORWARD_DEL
    private const val INS = KeyEvent.KEYCODE_INSERT
    private const val HOME = KeyEvent.KEYCODE_MOVE_HOME
    private const val END = KeyEvent.KEYCODE_MOVE_END
    private const val PGUP = KeyEvent.KEYCODE_PAGE_UP
    private const val PGDN = KeyEvent.KEYCODE_PAGE_DOWN
    private const val MENU = KeyEvent.KEYCODE_MENU
    private const val PRTSC = KeyEvent.KEYCODE_SYSRQ
    private const val SCRLK = KeyEvent.KEYCODE_SCROLL_LOCK
    private const val PAUSE = KeyEvent.KEYCODE_BREAK
    private const val CTRL = KeyEvent.KEYCODE_CTRL_LEFT
    private const val ALT = KeyEvent.KEYCODE_ALT_LEFT
    private const val WIN = KeyEvent.KEYCODE_META_LEFT
    private const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
    private const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    private const val UP = KeyEvent.KEYCODE_DPAD_UP
    private const val DOWN = KeyEvent.KEYCODE_DPAD_DOWN

    fun spec(page: KeyboardPage, tier: KeyboardTier): KeyboardPageSpec = when (page) {
        KeyboardPage.MAIN -> mainSpec(tier)
        KeyboardPage.NUM -> if (tier == KeyboardTier.POCKET) compactNum() else fullNum()
        KeyboardPage.NAV -> if (tier == KeyboardTier.POCKET) compactNav() else fullNav()
        KeyboardPage.NUMPAD -> compactNumpad()
    }

    private fun mainSpec(tier: KeyboardTier): KeyboardPageSpec {
        if (tier == KeyboardTier.POCKET) {
            return KeyboardPageSpec(4, listOf(KeyboardBlockSpec.Rows(10, KeyboardRows.rows(tier))))
        }
        if (tier == KeyboardTier.STANDARD) {
            return KeyboardPageSpec(5, listOf(KeyboardBlockSpec.Rows(15, KeyboardRows.rows(tier))))
        }
        return KeyboardPageSpec(
            5,
            listOf(
                KeyboardBlockSpec.Rows(15, KeyboardRows.rows(tier)),
                KeyboardBlockSpec.Grid(3, 3, 5, KeyboardRows.navCells().mapIndexed { index, key ->
                    KeyboardGridCell(key, index % 3 + 1, index / 3 + 1)
                })
            )
        )
    }

    private fun compactNum(): KeyboardPageSpec =
        KeyboardPageSpec(4, listOf(KeyboardBlockSpec.Rows(10, compactNumRows())))

    private fun compactNumpad(): KeyboardPageSpec = KeyboardPageSpec(
        4,
        listOf(KeyboardBlockSpec.Rows(10, listOf(
            row(
                normal(KeyEvent.KEYCODE_NUMPAD_7, "7"), normal(KeyEvent.KEYCODE_NUMPAD_8, "8"),
                normal(KeyEvent.KEYCODE_NUMPAD_9, "9"), special(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "/"),
                special(KeyEvent.KEYCODE_NUM_LOCK, "Num Lock")
            ),
            row(
                normal(KeyEvent.KEYCODE_NUMPAD_4, "4"), normal(KeyEvent.KEYCODE_NUMPAD_5, "5"),
                normal(KeyEvent.KEYCODE_NUMPAD_6, "6"), special(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "*"),
                special(BACKSPACE, "⌫")
            ),
            row(
                normal(KeyEvent.KEYCODE_NUMPAD_1, "1"), normal(KeyEvent.KEYCODE_NUMPAD_2, "2"),
                normal(KeyEvent.KEYCODE_NUMPAD_3, "3"), special(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "-"),
                page("ABC", KeyboardPage.MAIN)
            ),
            row(
                normal(KeyEvent.KEYCODE_NUMPAD_0, "0"), normal(KeyEvent.KEYCODE_NUMPAD_DOT, "."),
                special(KeyEvent.KEYCODE_NUMPAD_ADD, "+"), special(ENTER, "↵", kind = KeyKind.ACCENT),
                page("?123", KeyboardPage.NUM)
            )
        )))
    )

    private fun fullNum(): KeyboardPageSpec = KeyboardPageSpec(
        5,
        listOf(
            KeyboardBlockSpec.Rows(7, symbolRows()),
            KeyboardBlockSpec.Grid(4, 4, 5, numpadCells()),
            KeyboardBlockSpec.Grid(4, 4, 5, editCells())
        )
    )

    private fun compactNav(): KeyboardPageSpec =
        KeyboardPageSpec(4, listOf(KeyboardBlockSpec.Rows(10, compactNavRows())))

    private fun fullNav(): KeyboardPageSpec =
        KeyboardPageSpec(5, listOf(KeyboardBlockSpec.Rows(15, fullNavRows())))

    private fun normal(code: Int, label: String, secondary: String? = null, weight: Float = 1f): KeySpec =
        KeySpec(code = code, label = label, secondary = secondary, weight = weight)

    private fun shifted(code: Int, label: String, weight: Float = 1f): KeySpec =
        KeySpec(
            code = SHIFTED_CODE_BASE + code,
            label = label,
            weight = weight,
            shift = true,
            sendCode = code
        )

    private fun special(code: Int, label: String, weight: Float = 1f, kind: KeyKind = KeyKind.SPECIAL): KeySpec =
        KeySpec(code = code, label = label, weight = weight, kind = kind)

    private fun mod(code: Int, label: String, weight: Float = 1f): KeySpec =
        KeySpec(code = code, label = label, weight = weight, kind = KeyKind.MODIFIER)

    private fun page(label: String, page: KeyboardPage, weight: Float = 1f): KeySpec =
        KeySpec(label = label, weight = weight, kind = KeyKind.SPECIAL, page = page)

    private fun space(weight: Float): KeySpec =
        KeySpec(code = SPACE, label = "space", weight = weight, kind = KeyKind.SPACE)

    private fun cell(spec: KeySpec?, column: Int, row: Int, columnSpan: Int = 1, rowSpan: Int = 1): KeyboardGridCell =
        KeyboardGridCell(spec, column, row, columnSpan, rowSpan)

    private fun row(vararg keys: KeySpec): KeyboardRowSpec = KeyboardRowSpec(keys.toList())

    private fun numpadCells(): List<KeyboardGridCell> = listOf(
        cell(special(KeyEvent.KEYCODE_NUM_LOCK, "Num"), 1, 1),
        cell(special(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "/"), 2, 1),
        cell(special(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "*"), 3, 1),
        cell(special(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "-"), 4, 1),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_7, "7"), 1, 2),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_8, "8"), 2, 2),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_9, "9"), 3, 2),
        cell(special(KeyEvent.KEYCODE_NUMPAD_ADD, "+"), 4, 2, rowSpan = 2),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_4, "4"), 1, 3),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_5, "5"), 2, 3),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_6, "6"), 3, 3),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_1, "1"), 1, 4),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_2, "2"), 2, 4),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_3, "3"), 3, 4),
        cell(special(ENTER, "↵", kind = KeyKind.ACCENT), 4, 4, rowSpan = 2),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_0, "0"), 1, 5, columnSpan = 2),
        cell(normal(KeyEvent.KEYCODE_NUMPAD_DOT, "."), 3, 5)
    )

    private fun editCells(): List<KeyboardGridCell> = listOf(
        cell(special(ESC, "Esc"), 1, 1),
        cell(special(TAB, "⇥ Tab"), 2, 1),
        cell(special(BACKSPACE, "⌫"), 3, 1),
        cell(special(DEL, "Del"), 4, 1),
        cell(special(HOME, "Home"), 1, 2),
        cell(special(END, "End"), 2, 2),
        cell(special(PGUP, "PgUp"), 3, 2),
        cell(special(PGDN, "PgDn"), 4, 2),
        cell(null, 1, 3),
        cell(special(UP, "↑"), 2, 3),
        cell(null, 3, 3),
        cell(special(INS, "Ins"), 4, 3),
        cell(special(LEFT, "←"), 1, 4),
        cell(special(DOWN, "↓"), 2, 4),
        cell(special(RIGHT, "→"), 3, 4),
        cell(special(MENU, "Menu"), 4, 4),
        cell(page("ABC", KeyboardPage.MAIN), 1, 5),
        cell(mod(CTRL, "Ctrl"), 2, 5),
        cell(mod(ALT, "Alt"), 3, 5),
        cell(mod(WIN, "Win"), 4, 5)
    )

    private fun symbolRows(): List<KeyboardRowSpec> = listOf(
        row(
            shifted(KeyEvent.KEYCODE_GRAVE, "~"), shifted(KeyEvent.KEYCODE_1, "!"),
            shifted(KeyEvent.KEYCODE_2, "@"), shifted(KeyEvent.KEYCODE_3, "#"),
            shifted(KeyEvent.KEYCODE_4, "$"), shifted(KeyEvent.KEYCODE_5, "%"),
            shifted(KeyEvent.KEYCODE_6, "^")
        ),
        row(
            shifted(KeyEvent.KEYCODE_7, "&"), shifted(KeyEvent.KEYCODE_8, "*"),
            shifted(KeyEvent.KEYCODE_9, "("), shifted(KeyEvent.KEYCODE_0, ")"),
            normal(KeyEvent.KEYCODE_MINUS, "-"), shifted(KeyEvent.KEYCODE_MINUS, "_"),
            normal(KeyEvent.KEYCODE_EQUALS, "=")
        ),
        row(
            normal(KeyEvent.KEYCODE_LEFT_BRACKET, "["), normal(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"),
            shifted(KeyEvent.KEYCODE_LEFT_BRACKET, "{"), shifted(KeyEvent.KEYCODE_RIGHT_BRACKET, "}"),
            normal(KeyEvent.KEYCODE_BACKSLASH, "\\"), shifted(KeyEvent.KEYCODE_BACKSLASH, "|"),
            normal(KeyEvent.KEYCODE_SLASH, "/")
        ),
        row(
            normal(KeyEvent.KEYCODE_SEMICOLON, ";"), shifted(KeyEvent.KEYCODE_SEMICOLON, ":"),
            normal(KeyEvent.KEYCODE_APOSTROPHE, "'"), shifted(KeyEvent.KEYCODE_APOSTROPHE, "\""),
            normal(KeyEvent.KEYCODE_COMMA, ","), shifted(KeyEvent.KEYCODE_COMMA, "<"),
            normal(KeyEvent.KEYCODE_PERIOD, ".")
        ),
        row(
            shifted(KeyEvent.KEYCODE_PERIOD, ">"), shifted(KeyEvent.KEYCODE_SLASH, "?"),
            shifted(KeyEvent.KEYCODE_EQUALS, "+"), space(4f)
        )
    )

    private fun compactNumRows(): List<KeyboardRowSpec> = listOf(
        row(
            normal(KeyEvent.KEYCODE_1, "1"), normal(KeyEvent.KEYCODE_2, "2"),
            normal(KeyEvent.KEYCODE_3, "3"), normal(KeyEvent.KEYCODE_4, "4"),
            normal(KeyEvent.KEYCODE_5, "5"), normal(KeyEvent.KEYCODE_6, "6"),
            normal(KeyEvent.KEYCODE_7, "7"), normal(KeyEvent.KEYCODE_8, "8"),
            normal(KeyEvent.KEYCODE_9, "9"), normal(KeyEvent.KEYCODE_0, "0")
        ),
        row(
            normal(KeyEvent.KEYCODE_MINUS, "-"), normal(KeyEvent.KEYCODE_SLASH, "/"),
            shifted(KeyEvent.KEYCODE_SEMICOLON, ":"), normal(KeyEvent.KEYCODE_SEMICOLON, ";"),
            shifted(KeyEvent.KEYCODE_9, "("), shifted(KeyEvent.KEYCODE_0, ")"),
            shifted(KeyEvent.KEYCODE_4, "$"), shifted(KeyEvent.KEYCODE_7, "&"),
            shifted(KeyEvent.KEYCODE_2, "@"), shifted(KeyEvent.KEYCODE_APOSTROPHE, "\"")
        ),
        row(
            normal(KeyEvent.KEYCODE_LEFT_BRACKET, "["), normal(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"),
            shifted(KeyEvent.KEYCODE_LEFT_BRACKET, "{"), shifted(KeyEvent.KEYCODE_RIGHT_BRACKET, "}"),
            shifted(KeyEvent.KEYCODE_3, "#"), shifted(KeyEvent.KEYCODE_5, "%"),
            shifted(KeyEvent.KEYCODE_6, "^"), shifted(KeyEvent.KEYCODE_8, "*"),
            shifted(KeyEvent.KEYCODE_EQUALS, "+"), normal(KeyEvent.KEYCODE_EQUALS, "="),
            normal(KeyEvent.KEYCODE_BACKSLASH, "\\")
        ),
        row(
            page("ABC", KeyboardPage.MAIN, 1.2f), space(2.2f),
            normal(KeyEvent.KEYCODE_APOSTROPHE, "'"), normal(KeyEvent.KEYCODE_GRAVE, "`"),
            shifted(KeyEvent.KEYCODE_GRAVE, "~"), page("NumPad", KeyboardPage.NUMPAD, 1.3f),
            shifted(KeyEvent.KEYCODE_1, "!"), shifted(KeyEvent.KEYCODE_SLASH, "?"),
            special(BACKSPACE, "⌫", 1.2f),
            special(ENTER, "↵", 1.4f, KeyKind.ACCENT)
        )
    )

    private fun compactNavRows(): List<KeyboardRowSpec> = listOf(
        row(
            special(ESC, "Esc"), special(TAB, "⇥"),
            mod(WIN, "Win"), mod(CTRL, "Ctrl"), mod(ALT, "Alt"),
            special(DEL, "Del"), special(HOME, "Home"), special(END, "End"),
            special(PGUP, "PgUp"), special(PGDN, "PgDn")
        ),
        row(
            special(KeyEvent.KEYCODE_F1, "F1"), special(KeyEvent.KEYCODE_F2, "F2"),
            special(KeyEvent.KEYCODE_F3, "F3"), special(KeyEvent.KEYCODE_F4, "F4"),
            special(KeyEvent.KEYCODE_F5, "F5"), special(KeyEvent.KEYCODE_F6, "F6"),
            special(KeyEvent.KEYCODE_F7, "F7"), special(KeyEvent.KEYCODE_F8, "F8"),
            special(PRTSC, "PrtSc"), special(SCRLK, "ScrLk")
        ),
        row(
            special(KeyEvent.KEYCODE_F9, "F9"), special(KeyEvent.KEYCODE_F10, "F10"),
            special(KeyEvent.KEYCODE_F11, "F11"), special(KeyEvent.KEYCODE_F12, "F12"),
            special(INS, "Ins"), special(MENU, "Menu"), special(PAUSE, "Pause"),
            special(LEFT, "←"), special(UP, "↑"), special(RIGHT, "→")
        ),
        row(
            page("ABC", KeyboardPage.MAIN, 1.4f), space(4f),
            special(LEFT, "←", 1.1f), special(DOWN, "↓", 1.1f), special(RIGHT, "→", 1.1f),
            special(ENTER, "↵", 1.3f, KeyKind.ACCENT)
        )
    )

    private fun fullNavRows(): List<KeyboardRowSpec> {
        val functionRow = ArrayList<KeySpec>()
        functionRow.add(special(ESC, "Esc"))
        for (index in 0 until 12) {
            functionRow.add(special(KeyEvent.KEYCODE_F1 + index, "F" + (index + 1)))
        }
        functionRow.add(special(DEL, "Del", 2f))
        return listOf(
            row(*functionRow.toTypedArray()),
        row(
            special(PRTSC, "PrtSc", 1.5f), special(SCRLK, "ScrLk", 1.5f),
            special(PAUSE, "Pause", 1.5f), special(INS, "Ins", 1.5f),
            special(HOME, "Home", 1.5f), special(END, "End", 1.5f),
            special(PGUP, "PgUp", 1.5f), special(PGDN, "PgDn", 1.5f),
            special(MENU, "Menu", 1.5f), mod(WIN, "Win", 1.5f)
        ),
        row(
            special(LEFT, "←", 3f), special(UP, "↑", 3f),
            special(DOWN, "↓", 3f), special(RIGHT, "→", 3f),
            special(INS, "Ins", 1.5f), special(DEL, "Del", 1.5f)
        ),
        row(
            special(TAB, "⇥ Tab", 1.5f), mod(CTRL, "Ctrl", 1.5f), mod(ALT, "Alt", 1.5f),
            space(6f), mod(ALT, "Alt", 1.5f), mod(CTRL, "Ctrl", 1.5f),
            special(ENTER, "↵ Enter", 1.5f, KeyKind.ACCENT)
        ),
        row(
            page("ABC", KeyboardPage.MAIN, 1.5f),
            normal(KeyEvent.KEYCODE_1, "1", weight = 1.2f), normal(KeyEvent.KEYCODE_2, "2", weight = 1.2f),
            normal(KeyEvent.KEYCODE_3, "3", weight = 1.2f), normal(KeyEvent.KEYCODE_4, "4", weight = 1.2f),
            normal(KeyEvent.KEYCODE_5, "5", weight = 1.2f), normal(KeyEvent.KEYCODE_6, "6", weight = 1.2f),
            normal(KeyEvent.KEYCODE_7, "7", weight = 1.2f), normal(KeyEvent.KEYCODE_8, "8", weight = 1.2f),
            normal(KeyEvent.KEYCODE_9, "9", weight = 1.2f), normal(KeyEvent.KEYCODE_0, "0", weight = 1.2f),
            space(1.5f)
        )
        )
    }
}
