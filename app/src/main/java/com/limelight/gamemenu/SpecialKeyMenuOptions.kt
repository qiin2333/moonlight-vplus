package com.limelight.gamemenu

import com.limelight.CustomKeyData

internal const val SPECIAL_KEY_ADD_FOCUS_KEY = "special-key-add"

internal fun buildSpecialKeyMenuOptions(
    keys: List<CustomKeyData>,
    addLabel: String,
    deleteLabel: String,
    backLabel: String,
    onSend: (ShortArray) -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
): List<GameMenu.MenuOption> = buildList {
    keys.forEachIndexed { index, key ->
        add(GameMenu.MenuOption(
            label = key.name,
            isWithGameFocus = false,
            runnable = Runnable { onSend(key.keys) },
            iconKey = null,
            isShowIcon = false,
            isKeepDialog = false,
            focusKey = "special-key-$index"
        ))
    }
    add(gameMenuChildDialogOption(addLabel, Runnable(onAdd), focusKey = SPECIAL_KEY_ADD_FOCUS_KEY))
    if (keys.isNotEmpty()) {
        add(gameMenuChildDialogOption(deleteLabel, Runnable(onDelete), focusKey = "special-key-delete"))
    }
    add(createGameMenuBackOption(backLabel, focusKey = "special-key-back", onBack = onBack))
}
