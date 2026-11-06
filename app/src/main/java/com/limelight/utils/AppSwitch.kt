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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.limelight.R

private val SwitchWidth = 36.dp
private val SwitchHeight = 26.dp
private val TrackWidth = 36.dp
private val TrackHeight = 18.dp
private val ThumbDiameter = 20.dp

/** Shared switch: a white thumb over a slightly slimmer accent track. */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val active = appAccentColor()
    val soft = appAccentSoftColor()
    val outline = colorResource(R.color.game_menu_list_item_border)
    val thumbOutline = colorResource(R.color.ui_control_thumb_outline)
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
                !enabled -> soft.copy(alpha = 0.10f)
                checked -> active.copy(alpha = 0.82f)
                else -> soft
            },
            topLeft = Offset((size.width - trackW) / 2f, (size.height - trackH) / 2f),
            size = Size(trackW, trackH),
            cornerRadius = CornerRadius(trackH / 2f)
        )
        drawRoundRect(
            color = if (checked && enabled) active.copy(alpha = 0.46f) else outline,
            topLeft = Offset((size.width - trackW) / 2f, (size.height - trackH) / 2f),
            size = Size(trackW, trackH),
            cornerRadius = CornerRadius(trackH / 2f),
            style = Stroke(width = 0.75.dp.toPx())
        )
        val thumbMinX = (size.width - trackW) / 2f + thumbR
        val thumbMaxX = (size.width + trackW) / 2f - thumbR
        drawCircle(
            color = when {
                !enabled -> Color.White.copy(alpha = 0.38f)
                else -> Color.White
            },
            radius = thumbR,
            center = Offset(thumbMinX + (thumbMaxX - thumbMinX) * travel, size.height / 2f)
        )
        drawCircle(
            color = if (enabled) thumbOutline else thumbOutline.copy(alpha = thumbOutline.alpha * 0.38f),
            radius = thumbR - 0.375.dp.toPx(),
            center = Offset(thumbMinX + (thumbMaxX - thumbMinX) * travel, size.height / 2f),
            style = Stroke(width = 0.75.dp.toPx())
        )
    }
}
