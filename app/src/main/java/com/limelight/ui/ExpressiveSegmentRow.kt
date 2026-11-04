package com.limelight.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.limelight.ui.theme.AppCornerRadii

/** A single animated highlight per row, sampled during drawing instead of layout. */
@Composable
internal fun ExpressiveSegmentRow(
    columns: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val position = animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = ExpressiveMotion.STIFFNESS),
        label = "segment-position"
    )
    val opacity = animateFloatAsState(
        targetValue = if (selectedIndex >= 0) 1f else 0f,
        animationSpec = tween(100),
        label = "segment-opacity"
    )
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Row(
        modifier = modifier.drawBehind {
            val gap = 1.dp.toPx()
            val width = ((size.width - gap * (columns - 1)) / columns).coerceAtLeast(0f)
            val index = position.value.coerceIn(0f, (columns - 1).toFloat())
            val left = if (layoutDirection == LayoutDirection.Rtl) {
                size.width - width - index * (width + gap)
            } else index * (width + gap)
            drawRoundRect(
                color = color.copy(alpha = color.alpha * opacity.value),
                topLeft = Offset(left, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(AppCornerRadii.small.toPx())
            )
        },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
