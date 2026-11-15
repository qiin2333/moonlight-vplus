package com.limelight.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.limelight.utils.appAccentColor

@Composable
internal fun Modifier.gamepadFocusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val hardwareInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focusColor = appAccentColor()
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused && hardwareInput) Modifier.border(2.dp, focusColor, shape) else Modifier)
}
