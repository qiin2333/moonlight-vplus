package com.limelight

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.utils.AppActionSheet

/** A live device list; sharing and stopping never dismiss the panel. */
@Composable
internal fun UsbDevicePanel(
    devices: List<UsbDevice>,
    selected: UsbDevice?,
    busy: Boolean,
    message: Int,
    hostName: String,
    forwardingEnabled: Boolean,
    canShare: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onShare: (UsbDevice) -> Unit,
    onRelease: () -> Unit
) {
    val visibleDevices = (devices + listOfNotNull(selected)).distinctBy { it.deviceName }
    val initialFocusRequester = remember { FocusRequester() }
    val initialFocusPlaced = remember { mutableStateOf(false) }
    val controllerFocusMode = AppActionSheet.isControllerFocusMode()
    val inputModeManager = LocalInputModeManager.current
    val maxListHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.75f).toDp()
    }
    LaunchedEffect(controllerFocusMode, initialFocusPlaced.value) {
        if (controllerFocusMode && initialFocusPlaced.value) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            initialFocusRequester.requestFocus()
        }
    }

    AppActionSheet.ActionSheetContainer {
        AppActionSheet.ActionSheetHeader(
            title = stringResource(R.string.usb_forward_title),
            subtitle = stringResource(R.string.usb_forward_target, hostName),
            activeStatus = selected != null
        )
        AppActionSheet.ActionSheetRow(
            action = AppActionSheet.Action(
                id = -1,
                title = stringResource(R.string.usb_forward_enable),
                description = stringResource(R.string.usb_forward_enable_description),
                enabled = !busy || forwardingEnabled,
                trailingText = stringResource(if (forwardingEnabled) R.string.usb_forward_on else R.string.usb_forward_off),
            ),
            onAction = { onEnabledChange(!forwardingEnabled) },
            modifier = Modifier.focusRequester(initialFocusRequester)
                .onGloballyPositioned { initialFocusPlaced.value = true },
        )
        if (forwardingEnabled && !canShare && !busy) {
            AppActionSheet.ActionSheetRow(
                action = AppActionSheet.Action(id = -2, title = stringResource(R.string.usb_forward_retry)),
                onAction = { onRetry() },
            )
        }
        Text(
            text = stringResource(R.string.usb_forward_usage),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(message),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = maxListHeight),
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (visibleDevices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.usb_forward_empty),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(visibleDevices, key = { device -> device.deviceName }) { device ->
                val active = device.deviceName == selected?.deviceName
                val enabled = if (active) message != R.string.usb_forward_releasing
                    else canShare && !busy && selected == null
                AppActionSheet.ActionSheetRow(
                    action = AppActionSheet.Action(
                        id = device.deviceId,
                        title = device.productName
                            ?: "USB %04x:%04x".format(device.vendorId, device.productId),
                        description = if (active) stringResource(message)
                            else stringResource(R.string.usb_forward_local),
                        destructive = active,
                        enabled = enabled,
                        trailingText = stringResource(
                            if (active) R.string.usb_forward_release else R.string.usb_forward_share
                        )
                    ),
                    onAction = { if (active) onRelease() else onShare(device) },
                )
            }
        }
    }
}
