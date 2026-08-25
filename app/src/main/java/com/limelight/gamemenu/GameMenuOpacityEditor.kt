package com.limelight.gamemenu

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.AppActionSheet
import kotlin.math.roundToInt

internal object GameMenuOpacityEditor {
    private const val OPACITY_STEP = 5
    private const val SLIDER_STEPS =
        (PreferenceConfiguration.MAX_GAME_MENU_OPACITY -
            PreferenceConfiguration.MIN_GAME_MENU_OPACITY) / OPACITY_STEP - 1

    fun show(
        context: Context,
        initialOpacity: Int,
        onOpacityChange: (Int) -> Unit,
        onOpacityChangeFinished: (Int) -> Unit
    ): ComponentDialog {
        val dialog = ComponentDialog(context, R.style.AppActionSheetStyle)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AppActionSheet.AppActionSheetTheme {
                    var opacity by remember {
                        mutableIntStateOf(initialOpacity.coerceIn(
                            PreferenceConfiguration.MIN_GAME_MENU_OPACITY,
                            PreferenceConfiguration.MAX_GAME_MENU_OPACITY
                        ))
                    }
                    OpacityEditorContent(
                        opacity = opacity,
                        onOpacityChange = { updatedOpacity ->
                            if (updatedOpacity != opacity) {
                                opacity = updatedOpacity
                                onOpacityChange(updatedOpacity)
                            }
                        },
                        onOpacityChangeFinished = {
                            onOpacityChangeFinished(opacity)
                        },
                        onDone = {
                            onOpacityChangeFinished(opacity)
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        AppActionSheet.prepareDialog(dialog, composeView)
        return dialog
    }

    @Composable
    private fun OpacityEditorContent(
        opacity: Int,
        onOpacityChange: (Int) -> Unit,
        onOpacityChangeFinished: () -> Unit,
        onDone: () -> Unit
    ) {
        val sliderFocusRequester = remember { FocusRequester() }
        val doneFocusRequester = remember { FocusRequester() }
        val inputModeManager = LocalInputModeManager.current
        var sliderLaidOut by remember { mutableStateOf(false) }

        LaunchedEffect(sliderLaidOut) {
            if (sliderLaidOut) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                sliderFocusRequester.requestFocus()
            }
        }

        AppActionSheet.ActionSheetContainer {
            AppActionSheet.ActionSheetHeader(
                title = stringResource(R.string.title_game_menu_opacity),
                subtitle = stringResource(R.string.summary_game_menu_opacity),
                activeStatus = false
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.game_menu_opacity_value, opacity),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Slider(
                    value = opacity.toFloat(),
                    onValueChange = { rawOpacity ->
                        onOpacityChange(
                            ((rawOpacity / OPACITY_STEP).roundToInt() * OPACITY_STEP)
                                .coerceIn(
                                    PreferenceConfiguration.MIN_GAME_MENU_OPACITY,
                                    PreferenceConfiguration.MAX_GAME_MENU_OPACITY
                                )
                        )
                    },
                    onValueChangeFinished = onOpacityChangeFinished,
                    valueRange = PreferenceConfiguration.MIN_GAME_MENU_OPACITY.toFloat()..
                        PreferenceConfiguration.MAX_GAME_MENU_OPACITY.toFloat(),
                    steps = SLIDER_STEPS,
                    modifier = Modifier
                        .testTag("gameMenuOpacitySlider")
                        .fillMaxWidth()
                        .focusRequester(sliderFocusRequester)
                        .focusProperties { down = doneFocusRequester }
                        .onGloballyPositioned { sliderLaidOut = true }
                        .gamepadFocusOutline(GameMenuControlShape)
                        .handleOpacityDpad(
                            opacity = opacity,
                            onOpacityChange = onOpacityChange,
                            onOpacityChangeFinished = onOpacityChangeFinished
                        )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.game_menu_opacity_value,
                            PreferenceConfiguration.MIN_GAME_MENU_OPACITY
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            R.string.game_menu_opacity_value,
                            PreferenceConfiguration.MAX_GAME_MENU_OPACITY
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AppActionSheet.ActionSheetFooterAction(
                        label = stringResource(R.string.game_menu_ok).trim(),
                        onClick = onDone,
                        modifier = Modifier
                            .testTag("gameMenuOpacityDone")
                            .focusRequester(doneFocusRequester)
                            .focusProperties { up = sliderFocusRequester },
                        primary = true
                    )
                }
            }
        }
    }

    private fun Modifier.handleOpacityDpad(
        opacity: Int,
        onOpacityChange: (Int) -> Unit,
        onOpacityChangeFinished: () -> Unit
    ): Modifier = onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionLeft -> -OPACITY_STEP
            Key.DirectionRight -> OPACITY_STEP
            else -> return@onPreviewKeyEvent false
        }
        when (event.type) {
            KeyEventType.KeyDown -> {
                onOpacityChange(
                    (opacity + direction).coerceIn(
                        PreferenceConfiguration.MIN_GAME_MENU_OPACITY,
                        PreferenceConfiguration.MAX_GAME_MENU_OPACITY
                    )
                )
                true
            }
            KeyEventType.KeyUp -> {
                onOpacityChangeFinished()
                true
            }
            else -> false
        }
    }
}
