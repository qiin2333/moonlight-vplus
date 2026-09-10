package com.limelight

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.utils.AppActionSheet

internal data class UsbPanelDevice(val path: String, val name: String, val type: UsbDeviceType)

/** Shared dialog shell, with explicit controls and one focus target per action. */
@Composable
internal fun UsbDevicePanel(
    devices: List<UsbPanelDevice>, selected: String?, busy: Boolean, message: Int,
    hostName: String, forwardingEnabled: Boolean, canShare: Boolean,
    onEnabledChange: (Boolean) -> Unit, onRetry: () -> Unit,
    onShare: (String) -> Unit, onRelease: () -> Unit,
    onRefresh: () -> Unit, onDismiss: () -> Unit
) {
    val accent = usbPanelAccent()
    val initialFocus = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    val controllerMode = AppActionSheet.isControllerFocusMode()
    val inputMode = LocalInputModeManager.current
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.92f).toDp()
    }
    LaunchedEffect(controllerMode, placed) {
        if (controllerMode && placed) {
            inputMode.requestInputMode(InputMode.Keyboard)
            initialFocus.requestFocus()
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
            AppActionSheet.ActionSheetContainer {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = maxHeight),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_usb_type_generic), null, Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.usb_forward_title), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.usb_forward_experimental),
                                modifier = Modifier.background(accent.copy(alpha = 0.1f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                                color = accent, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            UsbPanelAction(stringResource(R.string.usb_forward_close), onDismiss,
                                initialFocus, icon = R.drawable.ic_close_stylish, iconOnly = true)
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_usb_host), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.usb_forward_target, hostName),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) }
                    item {
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        val enabled = !busy || forwardingEnabled
                        Row(Modifier.fillMaxWidth().focusRequester(initialFocus)
                            .onGloballyPositioned { placed = true }
                            .border(if (focused) 2.dp else 0.dp,
                                if (focused) accent else Color.Transparent,
                                RoundedCornerShape(12.dp))
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (enabled && key.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                                    if (key.action == KeyEvent.ACTION_UP) onEnabledChange(!forwardingEnabled)
                                    true
                                } else false
                            }
                            .toggleable(forwardingEnabled, interaction, null, enabled, Role.Switch) { onEnabledChange(it) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.usb_forward_toggle_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.usb_forward_toggle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(if (forwardingEnabled) R.string.usb_forward_on else R.string.usb_forward_off),
                                style = MaterialTheme.typography.labelLarge, color = accent)
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = forwardingEnabled, onCheckedChange = null, enabled = enabled,
                                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White))
                        }
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.usb_forward_devices_count, devices.size),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            UsbPanelAction(stringResource(R.string.usb_forward_refresh), onRefresh, initialFocus)
                        }
                    }
                    if (message != R.string.usb_forward_choose || busy) {
                        item {
                            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            if (forwardingEnabled && !canShare && !busy) {
                                UsbPanelAction(stringResource(R.string.usb_forward_retry), onRetry, initialFocus)
                            }
                        }
                    }
                    if (devices.isEmpty()) {
                        item { Text(stringResource(R.string.usb_forward_empty),
                            Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(devices, key = { it.path }) { device ->
                        val active = device.path == selected
                        val deviceType = device.type
                        val enabled = if (active) message != R.string.usb_forward_releasing
                            else canShare && !busy && selected == null
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val narrow = maxWidth < 420.dp
                            val info: @Composable () -> Unit = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Icon(painterResource(deviceType.icon), null, Modifier.size(24.dp), tint = accent)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(deviceType.label) + " · " + stringResource(if (active) R.string.usb_forward_sharing else R.string.usb_forward_local),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            val action: @Composable () -> Unit = {
                                UsbPanelAction(stringResource(if (active) R.string.usb_forward_release else R.string.usb_forward_share),
                                    { if (active) onRelease() else onShare(device.path) }, initialFocus,
                                    enabled = enabled, primary = !active, outlined = active)
                            }
                            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                info()
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
                            } else Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { info() }
                                Spacer(Modifier.width(16.dp))
                                action()
                            }
                        }
                    }
                    item {
                        HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val usage: @Composable (Modifier) -> Unit = { modifier ->
                                Text(stringResource(R.string.usb_forward_usage_short), modifier,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (controllerMode && maxWidth >= 600.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    usage(Modifier.weight(1f))
                                    UsbControllerHints()
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    usage(Modifier.fillMaxWidth())
                                    if (controllerMode) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                        UsbControllerHints()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbPanelAction(
    label: String, onClick: () -> Unit, fallbackFocus: FocusRequester,
    enabled: Boolean = true, primary: Boolean = false, outlined: Boolean = false,
    icon: Int? = null, iconOnly: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(enabled) { if (!enabled && focused) fallbackFocus.requestFocus() }
    val shape = RoundedCornerShape(50)
    val accent = usbPanelAccent()
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .border(if (focused) 2.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
        .padding(4.dp)
        .background(if (primary) accent.copy(alpha = if (enabled) 1f else 0.25f) else Color.Transparent, shape)
        .border(if (outlined) 1.dp else 0.dp, if (outlined) accent else Color.Transparent, shape)
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (enabled && key.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (key.action == KeyEvent.ACTION_UP) onClick()
                true
            } else false
        }
        .clickable(interaction, null, enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (iconOnly) 8.dp else 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        if (iconOnly && icon != null) Icon(painterResource(icon), label, Modifier.size(24.dp))
        else Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
            color = (if (primary) (if (accent.luminance() > 0.45f) Color(0xFF281820) else Color.White) else accent).copy(alpha = if (enabled) 1f else 0.45f))
    }
}

/** Retain the shell pink, with enough contrast for filled controls on the light sheet. */
@Composable
private fun usbPanelAccent(): Color = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f)
    lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.25f) else MaterialTheme.colorScheme.primary

/** Input hints are labels, not additional focus stops. */
@Composable
private fun UsbControllerHints() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("A" to R.string.usb_forward_confirm_hint, "B" to R.string.usb_forward_back_hint)
            .forEachIndexed { index, (key, label) ->
                if (index > 0) VerticalDivider(Modifier.height(18.dp).padding(horizontal = 4.dp),
                    color = color.copy(alpha = 0.18f))
                Box(Modifier.size(24.dp).border(1.dp, color, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center) {
                    Text(key, style = MaterialTheme.typography.labelLarge, color = color)
                }
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = color)
            }
    }
}
