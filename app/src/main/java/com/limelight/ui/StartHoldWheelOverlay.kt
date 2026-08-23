package com.limelight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.StartWheelAction
import kotlin.math.cos
import kotlin.math.sin

private const val WHEEL_START_ANGLE = -135f
private const val WHEEL_SECTOR_SWEEP = 90f
private const val OPTION_RADIUS_FRACTION = 0.36f

@Composable
internal fun StartHoldWheelOverlay(
    visible: Boolean,
    selectedAction: StartWheelAction,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val accent = colorResource(R.color.game_menu_accent)
    val surface = colorResource(R.color.game_menu_dialog_background)
    val textPrimary = colorResource(R.color.game_menu_text_primary)
    val textSecondary = colorResource(R.color.game_menu_text_secondary)
    val border = colorResource(R.color.game_menu_button_border)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center
    ) {
        val wheelSize = minOf(maxWidth, maxHeight).let { minOf(it * 0.74f, 440.dp) }
        val compact = wheelSize < 320.dp
        val optionRadius = wheelSize * if (compact) 0.38f else OPTION_RADIUS_FRACTION

        Box(
            modifier = Modifier.size(wheelSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f - 2.dp.toPx()
                val innerRadius = radius * 0.40f
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRect = Rect(center, radius)
                val innerRect = Rect(center, innerRadius)
                val sectorColors = listOf(
                    StartWheelAction.MENU,
                    StartWheelAction.MOUSE,
                    StartWheelAction.KEYBOARD,
                    StartWheelAction.PERFORMANCE
                )
                sectorColors.forEachIndexed { index, action ->
                    val startAngle = WHEEL_START_ANGLE + index * WHEEL_SECTOR_SWEEP
                    val path = Path().apply {
                        moveTo(
                            center.x + radius * kotlin.math.cos(Math.toRadians(startAngle.toDouble())).toFloat(),
                            center.y + radius * kotlin.math.sin(Math.toRadians(startAngle.toDouble())).toFloat()
                        )
                        arcTo(outerRect, startAngle, WHEEL_SECTOR_SWEEP, false)
                        lineTo(
                            center.x + innerRadius * kotlin.math.cos(Math.toRadians((startAngle + WHEEL_SECTOR_SWEEP).toDouble())).toFloat(),
                            center.y + innerRadius * kotlin.math.sin(Math.toRadians((startAngle + WHEEL_SECTOR_SWEEP).toDouble())).toFloat()
                        )
                        arcTo(innerRect, startAngle + WHEEL_SECTOR_SWEEP, -WHEEL_SECTOR_SWEEP, false)
                        close()
                    }
                    val active = selectedAction == action
                    drawPath(
                        path,
                        if (active) accent.copy(alpha = 0.16f) else surface.copy(alpha = 0.96f)
                    )
                    drawPath(
                        path,
                        color = if (active) accent else border.copy(alpha = 0.82f),
                        style = Stroke(width = if (active) 1.8.dp.toPx() else 1.dp.toPx())
                    )
                }

                drawCircle(
                    color = surface.copy(alpha = 0.98f),
                    radius = innerRadius,
                    center = center
                )
                drawCircle(
                    color = if (selectedAction == StartWheelAction.CONTINUE) accent else border,
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = if (selectedAction == StartWheelAction.CONTINUE) 1.8.dp.toPx() else 1.dp.toPx())
                )
                drawCircle(
                    color = border.copy(alpha = 0.9f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            WheelOption(
                action = StartWheelAction.MENU,
                selectedAction = selectedAction,
                icon = R.drawable.ic_menu,
                label = stringResource(R.string.start_hold_wheel_menu),
                textColor = textPrimary,
                secondaryColor = textSecondary,
                accent = accent,
                surface = surface,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.Center)
                    .wheelOffset(optionRadius, WHEEL_START_ANGLE + WHEEL_SECTOR_SWEEP / 2f)
            )
            WheelOption(
                action = StartWheelAction.MOUSE,
                selectedAction = selectedAction,
                icon = R.drawable.ic_mouse_cute,
                label = stringResource(R.string.start_hold_wheel_mouse),
                textColor = textPrimary,
                secondaryColor = textSecondary,
                accent = accent,
                surface = surface,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.Center)
                    .wheelOffset(optionRadius, WHEEL_START_ANGLE + WHEEL_SECTOR_SWEEP * 1.5f)
            )
            WheelOption(
                action = StartWheelAction.KEYBOARD,
                selectedAction = selectedAction,
                icon = R.drawable.ic_keyboard_cute,
                label = stringResource(R.string.start_hold_wheel_keyboard),
                textColor = textPrimary,
                secondaryColor = textSecondary,
                accent = accent,
                surface = surface,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.Center)
                    .wheelOffset(optionRadius, WHEEL_START_ANGLE + WHEEL_SECTOR_SWEEP * 2.5f)
            )
            WheelOption(
                action = StartWheelAction.PERFORMANCE,
                selectedAction = selectedAction,
                icon = R.drawable.ic_performance_cute,
                label = stringResource(R.string.start_hold_wheel_performance),
                textColor = textPrimary,
                secondaryColor = textSecondary,
                accent = accent,
                surface = surface,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.Center)
                    .wheelOffset(optionRadius, WHEEL_START_ANGLE + WHEEL_SECTOR_SWEEP * 3.5f)
            )
            WheelOption(
                action = StartWheelAction.CONTINUE,
                selectedAction = selectedAction,
                icon = R.drawable.ic_controller_cute,
                label = stringResource(R.string.start_hold_wheel_continue),
                textColor = textPrimary,
                secondaryColor = textSecondary,
                accent = accent,
                surface = surface,
                center = true,
                compact = compact,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun Modifier.wheelOffset(radius: Dp, angle: Float): Modifier {
    val radians = Math.toRadians(angle.toDouble())
    return offset(
        x = radius * cos(radians).toFloat(),
        y = radius * sin(radians).toFloat()
    )
}

@Composable
private fun WheelOption(
    action: StartWheelAction,
    selectedAction: StartWheelAction,
    icon: Int,
    label: String,
    textColor: Color,
    secondaryColor: Color,
    accent: Color,
    surface: Color,
    modifier: Modifier = Modifier,
    center: Boolean = false,
    compact: Boolean = false
) {
    val selected = action == selectedAction
    val shape = if (center) CircleShape else RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(
                width = if (center) {
                    if (compact) 80.dp else 104.dp
                } else {
                    if (compact) 88.dp else 112.dp
                },
                height = if (center) {
                    if (compact) 80.dp else 104.dp
                } else {
                    if (compact) 56.dp else 64.dp
                }
            )
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else surface.copy(alpha = 0.92f))
            .border(
                width = if (selected) 1.5.dp else 0.75.dp,
                color = if (selected) accent.copy(alpha = 0.86f) else secondaryColor.copy(alpha = 0.20f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(
                    if (compact) {
                        if (center) 24.dp else 20.dp
                    } else {
                        if (center) 28.dp else 24.dp
                    }
                )
            )
            Text(
                text = label,
                color = if (selected) accent else textColor,
                fontSize = if (compact) 11.sp else if (center) 13.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}
