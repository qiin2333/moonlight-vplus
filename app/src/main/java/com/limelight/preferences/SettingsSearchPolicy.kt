package com.limelight.preferences

internal object SettingsSearchPolicy {
    fun shouldShowChild(
        categoryEligible: Boolean,
        childEligible: Boolean,
        categoryMatches: Boolean,
        childMatches: Boolean,
    ): Boolean = categoryEligible &&
        childEligible &&
        (categoryMatches || childMatches)
}
