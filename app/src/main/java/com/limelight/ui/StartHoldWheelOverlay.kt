package com.limelight.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.StartWheelAction

private val OverlayScrim = Color.Black.copy(alpha = 0.38f)
private val PrimaryText = Color(0xFFFFF7EC)

@Composable
internal fun StartHoldWheelOverlay(
    visible: Boolean,
    selectedAction: StartWheelAction,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayScrim),
        contentAlignment = Alignment.Center
    ) {
        val clusterSize = minOf(maxWidth * 0.64f, maxHeight * 0.82f, 520.dp)
        val satelliteSize = clusterSize * 0.292f
        val centerSize = clusterSize * 0.335f
        val orbit = clusterSize * 0.30f
        val compact = clusterSize < 360.dp

        Box(
            modifier = Modifier.size(clusterSize),
            contentAlignment = Alignment.Center
        ) {
            SatelliteOption(
                action = StartWheelAction.MENU,
                selectedAction = selectedAction,
                icon = R.drawable.ic_start_wheel_menu,
                label = stringResource(R.string.start_hold_wheel_menu),
                size = satelliteSize,
                compact = compact,
                modifier = Modifier.offset(y = -orbit)
            )
            SatelliteOption(
                action = StartWheelAction.MOUSE,
                selectedAction = selectedAction,
                icon = R.drawable.ic_start_wheel_mouse,
                label = stringResource(R.string.start_hold_wheel_mouse),
                size = satelliteSize,
                compact = compact,
                modifier = Modifier.offset(x = orbit)
            )
            SatelliteOption(
                action = StartWheelAction.KEYBOARD,
                selectedAction = selectedAction,
                icon = R.drawable.ic_start_wheel_keyboard,
                label = stringResource(R.string.start_hold_wheel_keyboard),
                size = satelliteSize,
                compact = compact,
                modifier = Modifier.offset(y = orbit)
            )
            SatelliteOption(
                action = StartWheelAction.PERFORMANCE,
                selectedAction = selectedAction,
                icon = R.drawable.ic_start_wheel_performance,
                label = stringResource(R.string.start_hold_wheel_performance),
                size = satelliteSize,
                compact = compact,
                modifier = Modifier.offset(x = -orbit)
            )
            CenterOption(
                selected = selectedAction == StartWheelAction.CONTINUE,
                label = stringResource(R.string.start_hold_wheel_continue),
                size = centerSize,
                compact = compact
            )
        }
    }
}

@Composable
private fun SatelliteOption(
    action: StartWheelAction,
    selectedAction: StartWheelAction,
    icon: Int,
    label: String,
    size: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val selected = action == selectedAction
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "startWheelSatelliteScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(
                if (selected) {
                    R.drawable.start_wheel_satellite_selected
                } else {
                    R.drawable.start_wheel_satellite
                }
            ),
            contentDescription = null,
            alpha = if (selected) 0.92f else 0.38f,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.offset(y = if (compact) (-2).dp else (-4).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = Color.Unspecified,
                modifier = Modifier.size(if (compact) 30.dp else 42.dp)
            )
            Text(
                text = label,
                color = PrimaryText,
                fontSize = if (compact) 10.sp else 13.sp,
                lineHeight = if (compact) 11.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun CenterOption(
    selected: Boolean,
    label: String,
    size: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "startWheelCenterScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.start_wheel_center),
            contentDescription = null,
            alpha = if (selected) 0.96f else 0.82f,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier.offset(y = if (compact) 1.dp else 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_start_wheel_continue),
                contentDescription = label,
                tint = Color.Unspecified,
                modifier = Modifier.size(if (compact) 35.dp else 46.dp)
            )
            Text(
                text = label,
                color = PrimaryText,
                fontSize = if (compact) 11.sp else 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Clip
            )
        }
    }
}
