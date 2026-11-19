package com.limelight.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared corner-radius scale (Thor: restrained radii; mirrors corner_radius_* dimens). */
object AppCornerRadii {
    val extraSmall = 4.dp
    val small = 6.dp
    val medium = 10.dp
    val large = 12.dp
    val overlay = 14.dp
    val extraLarge = 16.dp
}

object AppShapes {
    val extraSmall = RoundedCornerShape(AppCornerRadii.extraSmall)
    val small = RoundedCornerShape(AppCornerRadii.small)
    val medium = RoundedCornerShape(AppCornerRadii.medium)
    val large = RoundedCornerShape(AppCornerRadii.large)
    val overlay = RoundedCornerShape(AppCornerRadii.overlay)
    val extraLarge = RoundedCornerShape(AppCornerRadii.extraLarge)
}
