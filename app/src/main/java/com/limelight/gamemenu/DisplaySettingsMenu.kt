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

/** Manual bitrate changes stay available while adaptive bitrate is enabled. */
internal fun manualBitrateChangeAllowed(@Suppress("UNUSED_PARAMETER") adaptiveBitrate: Boolean): Boolean = true

/** Same display values do not write preferences or rebuild the stream. */
internal fun shouldApplyDisplaySelection(selected: String, current: String): Boolean {
    return selected != current
}

/** Builds the bitrate-card display pages without depending on menu views. */
internal object DisplaySettingsMenu {
    fun root(
        resolutionTitle: String,
        resolutionValue: String,
        frameRateTitle: String,
        frameRateValue: String,
        screenTitle: String,
        screenValue: String,
        customTitle: String
    ): List<DisplaySettingsEntry> {
        return listOf(
            DisplaySettingsEntry(resolutionTitle, resolutionValue, page = DisplaySettingsPage.RESOLUTION),
            DisplaySettingsEntry(frameRateTitle, frameRateValue, page = DisplaySettingsPage.FRAME_RATE),
            DisplaySettingsEntry(screenTitle, screenValue, page = DisplaySettingsPage.SCREEN),
            DisplaySettingsEntry(customTitle, opensCustomResolutions = true)
        )
    }

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
