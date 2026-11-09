package com.limelight.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppSwitch
import com.limelight.utils.LastSettingsSummary
import com.limelight.utils.appAccentColor
import com.limelight.utils.appAccentSoftColor

@Composable
internal fun LastSettingsPanel(
    summary: LastSettingsSummary,
    expanded: Boolean,
    reuse: Boolean,
    onToggleDetails: () -> Unit,
    onReuseChanged: (Boolean) -> Unit,
    onReturnToApp: () -> Unit
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val surface = colorResource(R.color.appview_panel_surface)
    val primary = colorResource(R.color.ui_shell_text_primary)
    val secondary = colorResource(R.color.ui_shell_text_secondary)
    val outline = colorResource(R.color.ui_shell_outline)
    val accent = appAccentColor()
    val accentSoft = appAccentSoftColor()
    val title = stringResource(R.string.app_last_settings_title)
    val description = stringResource(
        R.string.app_last_settings_pill_description, summary.metricsLine, summary.timeLabel
    )
    val detailsAction = stringResource(
        if (expanded) R.string.app_last_settings_collapse else R.string.app_last_settings_expand
    )
    val switchDescription = stringResource(R.string.app_last_settings_reuse_description)
    var pillFocused by remember { mutableStateOf(false) }
    var switchFocused by remember { mutableStateOf(false) }
    val focused = pillFocused || switchFocused
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f, tween(160), label = "lastSettingsChevron"
    )

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .then(if (landscape) Modifier.widthIn(max = 420.dp) else Modifier)
                .fillMaxWidth()
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(160), expandFrom = Alignment.Bottom) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(130), shrinkTowards = Alignment.Bottom) + fadeOut(tween(130))
            ) {
                Column(
                    Modifier
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                        .background(surface, AppShapes.large)
                        .border(1.dp, outline, AppShapes.large)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title, Modifier.weight(1f), color = primary, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1
                        )
                        Text(summary.timeLabel, color = secondary, fontSize = 11.sp, maxLines = 1)
                    }
                    Spacer(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(outline))
                    summary.rows.forEach { (label, value) ->
                        Row(Modifier.padding(top = 7.dp).fillMaxWidth()) {
                            Text(label, Modifier.width(60.dp), color = secondary, fontSize = 11.sp)
                            Text(
                                value, Modifier.weight(1f), color = secondary, fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(surface)
                    .border(if (focused) 2.dp else 1.dp, if (focused) accent else outline, CircleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { pillFocused = it.isFocused }
                        .lastSettingsController(onToggleDetails, onReturnToApp)
                        .clickable(role = Role.Button, onClick = onToggleDetails)
                        .clearAndSetSemantics {
                            contentDescription = description
                            stateDescription = detailsAction
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppViewPanelIcon(R.drawable.ic_appview_preset_history, secondary, Modifier.size(15.dp))
                        Text(
                            title, Modifier.padding(start = 6.dp), color = primary, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1
                        )
                        Spacer(Modifier.padding(horizontal = 9.dp).width(1.dp).height(16.dp).background(outline))
                        Text(
                            summary.metricsLine, Modifier.weight(1f), color = secondary, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val chevronColor = if (expanded) accent else secondary
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size(20.dp)
                                .background(
                                    if (expanded) accentSoft else colorResource(R.color.ui_shell_surface_pressed),
                                    CircleShape
                                )
                                .border(1.dp, if (expanded) accent else outline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            AppViewPanelIcon(
                                R.drawable.ic_appview_preset_chevron, chevronColor,
                                Modifier.size(12.dp).rotate(rotation)
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.app_last_settings_reuse),
                    Modifier.padding(start = 8.dp, end = 5.dp).clearAndSetSemantics {},
                    color = secondary, fontSize = 11.sp, maxLines = 1
                )
                Box(
                    Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .onFocusChanged { switchFocused = it.isFocused }
                        .background(if (switchFocused) accentSoft else Color.Transparent, AppShapes.small)
                        .lastSettingsController({ onReuseChanged(!reuse) }, onReturnToApp)
                        .semantics { contentDescription = switchDescription }
                        .toggleable(reuse, role = Role.Switch, onValueChange = onReuseChanged),
                    contentAlignment = Alignment.Center
                ) {
                    AppSwitch(checked = reuse, onCheckedChange = null)
                }
            }
        }
    }
}

private fun Modifier.lastSettingsController(onClick: () -> Unit, onReturnToApp: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        when (key.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) onClick()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (key.action == KeyEvent.ACTION_DOWN) onReturnToApp()
                true
            }
            else -> false
        }
    }
