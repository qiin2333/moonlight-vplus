package com.limelight.utils

/**
 * Shared geometry for the Compose and View switch implementations.
 *
 * Keep these values in one place so a style change cannot make the game menu,
 * settings pages, Crown config and overlay switches drift apart.
 */
internal object AppSwitchSpec {
    const val WIDTH_DP = 36
    const val HEIGHT_DP = 26
    const val TRACK_WIDTH_DP = 36
    const val TRACK_HEIGHT_DP = 26
    const val THUMB_DIAMETER_DP = 20
    const val THUMB_INSET_DP = 3
    const val CORNER_RADIUS_DP = 13
    const val STROKE_DP = 0.75f
}
