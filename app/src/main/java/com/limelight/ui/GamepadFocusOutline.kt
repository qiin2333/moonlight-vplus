package com.limelight.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.limelight.utils.appAccentColor

@Composable
internal fun Modifier.gamepadFocusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val hardwareInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focusColor = appAccentColor()
    // Draw state changes without rebuilding the focus target; recomposing here
    // clears the focus that was just requested by controller navigation.
    return onFocusChanged { focused = it.isFocused }
        .drawBehind {
            if (!focused || !hardwareInput) return@drawBehind
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = focusColor,
                style = Stroke(width = 2.dp.toPx())
            )
        }
}
