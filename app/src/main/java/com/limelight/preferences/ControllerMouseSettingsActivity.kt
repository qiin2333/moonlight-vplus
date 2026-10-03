package com.limelight.preferences

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.utils.UiHelper
import com.limelight.utils.appAccentColor
import kotlin.math.roundToInt

class ControllerMouseSettingsActivity : ThemedAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val baseColors = if (darkTheme) darkColorScheme() else lightColorScheme()
            val colors = baseColors.copy(
                primary = appAccentColor(),
                onPrimary = Color.White,
                background = colorResource(R.color.game_menu_dialog_background),
                onBackground = colorResource(R.color.game_menu_text_primary),
                surface = colorResource(R.color.game_menu_card_background),
                onSurface = colorResource(R.color.game_menu_text_primary),
                onSurfaceVariant = colorResource(R.color.game_menu_text_secondary),
            )
            var speed by remember {
                mutableIntStateOf(prefs.getInt(PreferenceConfiguration.CONTROLLER_MOUSE_SPEED_PREF_STRING, 100).coerceIn(50, 200))
            }
            var scrollStick by remember {
                mutableStateOf(prefs.getString("analog_scrolling", "right")?.takeIf { it in setOf("right", "left", "none") } ?: "right")
            }
            var dpadMode by remember {
                mutableStateOf(if (prefs.getString(PreferenceConfiguration.CONTROLLER_MOUSE_DPAD_PREF_STRING, "scroll") == "arrows")
                    "arrows" else "scroll")
            }

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = 620.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::finish,
                                    modifier = Modifier.semantics { contentDescription = getString(R.string.controller_diag_back) }) {
                                    Text("‹", fontSize = 30.sp)
                                }
                                Text(stringResource(R.string.title_controller_mouse_settings), style = MaterialTheme.typography.titleLarge)
                            }
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.controller_mouse_pointer_speed), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.controller_mouse_speed_percent, speed), color = colors.primary)
                                    }
                                    Text(
                                        stringResource(R.string.controller_mouse_pointer_speed_summary),
                                        color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Slider(
                                        value = speed.toFloat(),
                                        onValueChange = { speed = ((it / 10).roundToInt() * 10).coerceIn(50, 200) },
                                        onValueChangeFinished = {
                                            prefs.edit().putInt(PreferenceConfiguration.CONTROLLER_MOUSE_SPEED_PREF_STRING, speed).apply()
                                        },
                                        valueRange = 50f..200f,
                                        steps = 14,
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("50%", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        Text("200%", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Column {
                                    Text(stringResource(R.string.controller_mouse_scroll_stick), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.controller_mouse_scroll_stick_summary),
                                        color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    MouseModeChoice(R.string.analogscroll_right, scrollStick == "right") {
                                        scrollStick = "right"
                                        prefs.edit().putString("analog_scrolling", scrollStick).apply()
                                    }
                                    MouseModeChoice(R.string.analogscroll_left, scrollStick == "left") {
                                        scrollStick = "left"
                                        prefs.edit().putString("analog_scrolling", scrollStick).apply()
                                    }
                                    MouseModeChoice(R.string.controller_mouse_scroll_none, scrollStick == "none") {
                                        scrollStick = "none"
                                        prefs.edit().putString("analog_scrolling", scrollStick).apply()
                                    }
                                }
                                Column {
                                    Text(stringResource(R.string.controller_mouse_dpad_behavior), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.controller_mouse_dpad_behavior_summary),
                                        color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    MouseModeChoice(R.string.controller_mouse_dpad_scroll, dpadMode == "scroll") {
                                        dpadMode = "scroll"
                                        prefs.edit().putString(PreferenceConfiguration.CONTROLLER_MOUSE_DPAD_PREF_STRING, dpadMode).apply()
                                    }
                                    MouseModeChoice(R.string.controller_mouse_dpad_arrows, dpadMode == "arrows") {
                                        dpadMode = "arrows"
                                        prefs.edit().putString(PreferenceConfiguration.CONTROLLER_MOUSE_DPAD_PREF_STRING, dpadMode).apply()
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                }
            }
        }
    }
}
}

@Composable
private fun MouseModeChoice(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
            onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}
