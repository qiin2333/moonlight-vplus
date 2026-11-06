package com.limelight.utils

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.limelight.R

/** Common slider artwork; callers keep ranges, steps, persistence and input modifiers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val accent = appAccentColor()
    val inactive = colorResource(R.color.ui_shell_outline)
    val border = colorResource(R.color.ui_control_thumb_outline)
    val opacity = if (enabled) 1f else 0.38f
    val keyStep = (valueRange.endInclusive - valueRange.start) / (if (steps > 0) steps + 1 else 100)
    val keyDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val colors = SliderDefaults.colors(
        activeTrackColor = accent, inactiveTrackColor = inactive,
        disabledActiveTrackColor = accent.copy(alpha = 0.38f),
        disabledInactiveTrackColor = inactive.copy(alpha = inactive.alpha * 0.38f)
    )
    Slider(
        value = value, onValueChange = onValueChange,
        modifier = modifier.handleSliderDpad(value, enabled, keyStep * keyDirection,
            valueRange, onValueChange, onValueChangeFinished),
        enabled = enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, colors = colors,
        thumb = {
            Canvas(Modifier.size(20.dp)) {
                drawCircle(Color.White.copy(alpha = opacity))
                drawCircle(border.copy(alpha = border.alpha * opacity),
                    radius = 9.625.dp.toPx(), style = Stroke(0.75.dp.toPx()))
                drawCircle(accent.copy(alpha = opacity), radius = 6.dp.toPx())
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, enabled = enabled, colors = colors,
                modifier = Modifier.height(6.dp), thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 3.dp, drawStopIndicator = null
            )
        }
    )
}

/** Keeps caller-specific steps first and commits held-key edits once on release or focus loss. */
@Composable
internal fun Modifier.handleSliderDpad(
    value: Float,
    enabled: Boolean = true,
    step: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?
): Modifier {
    val heldKeys = remember { mutableSetOf<Key>() }
    return onFocusChanged {
        if (!it.hasFocus && heldKeys.isNotEmpty()) {
            heldKeys.clear()
            onValueChangeFinished?.invoke()
        }
    }.onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionLeft -> -1f
            Key.DirectionRight -> 1f
            else -> return@onPreviewKeyEvent false
        }
        if (!enabled) return@onPreviewKeyEvent true
        when (event.type) {
            KeyEventType.KeyDown -> {
                heldKeys.add(event.key)
                val adjusted = (value + direction * step).coerceIn(valueRange.start, valueRange.endInclusive)
                if (adjusted != value) onValueChange(adjusted)
                true
            }
            KeyEventType.KeyUp -> {
                if (heldKeys.remove(event.key)) onValueChangeFinished?.invoke()
                true
            }
            else -> false
        }
    }
}
