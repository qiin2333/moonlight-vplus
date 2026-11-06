package com.limelight.utils

import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.animation.core.Easing

/** Match SwitchCompat's native thumb animator without replacing its input handling. */
internal object AppSwitchMotion {
    const val DurationMillis = 250
    val interpolator = AccelerateDecelerateInterpolator()
    val easing = Easing { interpolator.getInterpolation(it) }
}
