package com.limelight.nvstream

data class RemoteTextContext(
    val flags: Int,
    val revision: Int,
    val activationId: Long,
    val inputToken: Long,
    val source: Int,
    val cause: Int,
    val anchorX: Int,
    val anchorY: Int,
    val elementLeft: Int,
    val elementTop: Int,
    val elementRight: Int,
    val elementBottom: Int,
    val caretLeft: Int,
    val caretTop: Int,
    val caretRight: Int,
    val caretBottom: Int,
    val captureWidth: Int,
    val captureHeight: Int,
) {
    fun hasFlag(flag: Int) = flags and flag != 0

    companion object {
        const val FLAG_ACTIVE = 0x0001
        const val FLAG_EDITABLE = 0x0002
        const val FLAG_PASSWORD = 0x0004
        const val FLAG_MULTILINE = 0x0008
        const val FLAG_ANCHOR_POINT = 0x0010
        const val FLAG_ELEMENT_RECT = 0x0020
        const val FLAG_CARET_RECT = 0x0040
        const val FLAG_INPUT_MATCHED = 0x0080
        const val FLAG_PANE_VISIBLE = 0x0100
        const val FLAG_AUTO_SHOW = 0x0200

        const val SOURCE_INPUT_PANE = 1
        const val SOURCE_UIA = 2
        const val CAUSE_REMOTE_TOUCH = 1
        const val CAUSE_REMOTE_MOUSE = 2
    }
}
