package com.limelight

import android.app.Dialog
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.utils.AppActionSheet
import com.limelight.utils.AppTheme

private val appearanceOptions = listOf(
    AppTheme.THEME_MODE_SYSTEM to R.string.pcview_theme_follow_system,
    AppTheme.THEME_MODE_LIGHT to R.string.pcview_theme_light,
    AppTheme.THEME_MODE_DARK to R.string.pcview_theme_dark
)

private val accentOptions = listOf(
    AppTheme.ACCENT_MODE_PINK to R.string.theme_accent_pink,
    AppTheme.ACCENT_MODE_BG to R.string.theme_accent_background
)

internal fun showAppThemeSheet(context: Context, onThemeSaved: () -> Unit) {
    val savedMode = AppTheme.getAppThemeMode(context)
    val savedAccent = AppTheme.getAccentMode(context)
    lateinit var dialog: Dialog
    fun applyTheme(mode: String, accent: String) {
        if (!dialog.isShowing) return
        dialog.dismiss()
        if (mode != savedMode || accent != savedAccent) {
            AppTheme.save(context, mode, accent)
            onThemeSaved()
        }
    }
    dialog = AppActionSheet.showCustom(context) {
        val initialFocus = remember { FocusRequester() }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val inputModeManager = LocalInputModeManager.current
        val controllerFocus = AppActionSheet.isControllerFocusMode()
        LaunchedEffect(windowFocused) {
            if (windowFocused && controllerFocus) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                initialFocus.requestFocus()
            }
        }

        AppActionSheet.ActionSheetContainer {
            AppActionSheet.ActionSheetHeader(
                stringResource(R.string.pcview_theme_dialog_title),
                subtitle = null,
                activeStatus = false
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.54f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)
            ) {
                ThemeChoiceGroup(
                    R.string.theme_appearance, appearanceOptions, savedMode,
                    initialFocus = initialFocus
                ) { applyTheme(it, savedAccent) }
                ThemeChoiceGroup(R.string.theme_accent_source, accentOptions, savedAccent) {
                    applyTheme(savedMode, it)
                }
            }
        }
    }
}

@Composable
private fun ThemeChoiceGroup(
    title: Int,
    options: List<Pair<String, Int>>,
    selected: String,
    initialFocus: FocusRequester? = null,
    onSelect: (String) -> Unit
) {
    Column(Modifier.selectableGroup()) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        options.forEachIndexed { index, (value, label) ->
            AppActionSheet.ActionSheetRow(
                action = AppActionSheet.Action(index, stringResource(label), checked = value == selected),
                modifier = if (value == selected && initialFocus != null) {
                    Modifier.focusRequester(initialFocus)
                } else Modifier,
                singleChoice = true,
                onAction = { onSelect(value) }
            )
        }
    }
}
