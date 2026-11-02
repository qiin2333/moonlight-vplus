package com.limelight.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.limelight.R

private val SwitchWidth = 46.dp
private val SwitchHeight = 24.dp
private val TrackWidth = 40.dp
private val TrackHeight = 14.dp
private val ThumbDiameter = 20.dp

/**
 * Compact flat switch matching the settings pages' tinted SwitchCompat:
 * accent thumb over a translucent accent track, secondary gray when unchecked.
 * Drawn size-agnostic, so callers may override the layout size via [modifier].
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val active = appAccentColor()
    val inactive = colorResource(R.color.ui_shell_text_secondary)
    val travel by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "appSwitchTravel"
    )
    Canvas(
        modifier = modifier
            .size(SwitchWidth, SwitchHeight)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange
                    )
                } else {
                    Modifier
                }
            )
    ) {
        val trackW = TrackWidth.toPx()
        val trackH = TrackHeight.toPx()
        val thumbR = ThumbDiameter.toPx() / 2f
        drawRoundRect(
            color = when {
                !enabled -> inactive.copy(alpha = 0.15f)
                checked -> active.copy(alpha = 0.35f)
                else -> inactive.copy(alpha = 0.20f)
            },
            topLeft = Offset((size.width - trackW) / 2f, (size.height - trackH) / 2f),
            size = Size(trackW, trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )
        val thumbMinX = (size.width - trackW) / 2f + thumbR
        val thumbMaxX = (size.width + trackW) / 2f - thumbR
        drawCircle(
            color = when {
                !enabled -> inactive.copy(alpha = 0.38f)
                checked -> active
                else -> inactive
            },
            radius = thumbR,
            center = Offset(thumbMinX + (thumbMaxX - thumbMinX) * travel, size.height / 2f)
        )
    }
}
