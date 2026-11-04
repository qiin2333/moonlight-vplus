package com.limelight.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Render-only feedback shared by segments, dialog actions, and menu rows. */
@Composable
internal fun Modifier.expressivePressFeedback(
    source: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float = ExpressiveMotion.PRESS_SCALE,
    focusedScale: Float = 1.02f,
    confirmPressed: Boolean = false
): Modifier {
    val touchPressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed = touchPressed || confirmPressed
    val scale = animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            pressed -> pressedScale
            focused -> focusedScale
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = if (pressed) ExpressiveMotion.PRESS_DAMPING else ExpressiveMotion.BUTTON_RELEASE_DAMPING,
            stiffness = ExpressiveMotion.STIFFNESS
        ),
        label = "control-press"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
