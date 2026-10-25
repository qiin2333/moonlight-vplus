package com.limelight.gamemenu

/** In-stream actions change topology; startup policies remain in the full settings page. */
internal fun inStreamScreenModeChoices(
    labels: Map<String, String>,
    selected: String
): List<DisplayChoice> = listOf("2", "4", "3").map { value ->
    DisplayChoice(
        value = value,
        label = labels[value] ?: value,
        selected = value == selected
    )
}
