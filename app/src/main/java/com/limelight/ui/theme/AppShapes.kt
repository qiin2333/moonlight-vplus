package com.limelight.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Shared corner-radius scale for Compose surfaces and controls. */
object AppCornerRadii {
    val extraSmall = 12.dp
    val small = 12.dp
    val medium = 12.dp
    val large = 12.dp
    val overlay = 12.dp
    val extraLarge = 12.dp
}

object AppShapes {
    val extraSmall = RoundedCornerShape(AppCornerRadii.extraSmall)
    val small = RoundedCornerShape(AppCornerRadii.small)
    val medium = RoundedCornerShape(AppCornerRadii.medium)
    val large = RoundedCornerShape(AppCornerRadii.large)
    val overlay = RoundedCornerShape(AppCornerRadii.overlay)
    val extraLarge = RoundedCornerShape(AppCornerRadii.extraLarge)
}
