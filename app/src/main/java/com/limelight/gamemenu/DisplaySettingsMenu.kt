package com.limelight.gamemenu

internal enum class DisplaySettingsPage {
    ROOT,
    RESOLUTION,
    FRAME_RATE,
    SCREEN
}

internal data class DisplaySettingsEntry(
    val label: String,
    val value: String? = null,
    val description: String? = null,
    val choiceValue: String? = null,
    val selected: Boolean = false,
    val page: DisplaySettingsPage? = null,
    val opensCustomResolutions: Boolean = false
)

/** Builds the bitrate-card display pages without depending on menu views. */
internal object DisplaySettingsMenu {
    fun choices(page: DisplaySettingsPage, choices: List<DisplayChoice>): List<DisplaySettingsEntry> {
        return choices.map { choice ->
            DisplaySettingsEntry(
                label = choice.label,
                description = choice.description,
                choiceValue = choice.value,
                selected = choice.selected,
                page = page
            )
        }
    }
}
