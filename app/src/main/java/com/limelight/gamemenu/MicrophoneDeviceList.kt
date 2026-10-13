package com.limelight.gamemenu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.utils.AppActionSheet

@Composable
internal fun MicrophoneDeviceList(
    state: MicrophoneMenuState,
    onSelected: (String) -> Unit,
    initialFocusRequester: FocusRequester,
    backFocusRequester: FocusRequester
) {
    val initialKey = remember { state.selectedKey.takeIf { selected -> state.choices.any { it.key == selected } }
        ?: state.choices.firstOrNull()?.key }
    val initialTarget = initialKey.takeIf { key -> state.choices.any { it.key == key } }
        ?: state.choices.firstOrNull()?.key
    var placed by remember(initialTarget) { mutableStateOf(false) }
    var initiallyFocused by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val inputMode = LocalInputModeManager.current
    val requesters = state.choices.associate { choice ->
        choice.key to key(choice.key) { remember { FocusRequester() } }
    }
    LaunchedEffect(placed, state.choices.map { it.key }) {
        if (placed && (!initiallyFocused || focusedKey?.let { key -> state.choices.none { it.key == key } } == true)) {
            inputMode.requestInputMode(InputMode.Keyboard)
            initialFocusRequester.requestFocus()
            initiallyFocused = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GameMenuDimens.compact)) {
        Text(state.status, color = colorResource(R.color.game_menu_text_secondary), fontSize = 12.sp)
        Column(Modifier.selectableGroup()) {
            state.choices.forEachIndexed { index, choice ->
                key(choice.key) {
                    AppActionSheet.ActionSheetRow(
                        action = AppActionSheet.Action(index, choice.name, checked = choice.key == state.selectedKey),
                        onAction = { onSelected(choice.key) },
                        modifier = Modifier
                            .testTag("microphoneInput:${choice.key}")
                            .semantics { role = Role.RadioButton; selected = choice.key == state.selectedKey }
                            .then(if (choice.key == initialTarget) Modifier.focusRequester(initialFocusRequester)
                                .onGloballyPositioned { placed = true } else Modifier)
                            .focusRequester(requesters.getValue(choice.key))
                            .onFocusChanged { if (it.isFocused) focusedKey = choice.key }
                            .focusProperties {
                                up = state.choices.getOrNull(index - 1)?.key?.let(requesters::getValue) ?: backFocusRequester
                                down = state.choices.getOrNull(index + 1)?.key?.let(requesters::getValue) ?: FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                    )
                }
            }
        }
    }
}
