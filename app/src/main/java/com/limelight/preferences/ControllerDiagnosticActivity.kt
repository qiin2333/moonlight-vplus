package com.limelight.preferences

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.UsbControllerShortcutStateMachine
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.gamemenu.GameMenuCardShape
import com.limelight.gamemenu.GameMenuDimens
import com.limelight.gamemenu.gamepadFocusOutline
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.utils.UiHelper
import java.util.Locale

class ControllerDiagnosticActivity : ComponentActivity() {
    private var snapshot by mutableStateOf(ControllerDiagnostics.Snapshot(emptyList()))
    private val simulatorHandler = Handler(Looper.getMainLooper())
    private val simulatorStateMachine = UsbControllerShortcutStateMachine()
    private var simulatorPressedFlags = 0
    private var simulatorInputSource: String? = null
    private var simulatorUiState by mutableStateOf(ShortcutSimulatorUiState())
    private val simulatorLongPressRunnable = Runnable {
        applySimulatorUpdate(
            simulatorStateMachine.onLongPressTimeout(SystemClock.uptimeMillis(), true)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val systemBarColor = ContextCompat.getColor(this, R.color.game_menu_dialog_background)
        val window: Window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor

        UiHelper.setLocale(this)
        refreshControllers()

        setContent {
            ControllerDiagnosticScreen(
                snapshot = snapshot,
                simulatorState = simulatorUiState,
                onBack = ::finish,
                onRefresh = ::refreshControllers
            )
        }

        UiHelper.notifyNewRootView(this)
    }

    override fun onResume() {
        super.onResume()
        refreshControllers()
    }

    private fun refreshControllers() {
        resetShortcutTest()
        snapshot = ControllerDiagnostics.scan(this)
    }

    private fun resetShortcutTest() {
        simulatorHandler.removeCallbacks(simulatorLongPressRunnable)
        simulatorStateMachine.reset()
        simulatorPressedFlags = 0
        simulatorInputSource = null
        simulatorUiState = ShortcutSimulatorUiState()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleSimulatorKeyEvent(event, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleSimulatorKeyEvent(event, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleSimulatorKeyEvent(event: KeyEvent, pressed: Boolean): Boolean {
        val inputDevice = event.device
        val sources = inputDevice?.sources ?: event.source
        val isControllerEvent = inputDevice != null && !inputDevice.isVirtual && (
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            )
        if (!isControllerEvent) return false

        val buttonFlag = simulatorButtonFlag(event.keyCode) ?: return false
        if (!pressed || event.repeatCount == 0) {
            updateSimulatorButton(event, buttonFlag, pressed)
        }
        return event.keyCode != KeyEvent.KEYCODE_BACK
    }

    override fun onDestroy() {
        resetShortcutTest()
        super.onDestroy()
    }

    private fun updateSimulatorButton(event: KeyEvent, buttonFlag: Int, pressed: Boolean) {
        val source = "android:${event.deviceId}"
        if (simulatorInputSource != source) {
            simulatorHandler.removeCallbacks(simulatorLongPressRunnable)
            simulatorStateMachine.reset()
            simulatorPressedFlags = 0
            simulatorInputSource = source
            simulatorUiState = ShortcutSimulatorUiState(
                controllerName = event.device?.name?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.controller_diag_unknown_device)
            )
        }
        simulatorPressedFlags = if (pressed) {
            simulatorPressedFlags or buttonFlag
        } else {
            simulatorPressedFlags and buttonFlag.inv()
        }
        applySimulatorUpdate(
            simulatorStateMachine.onButtonSnapshot(
                simulatorPressedFlags,
                SystemClock.uptimeMillis(),
                true
            )
        )
    }

    private fun applySimulatorUpdate(update: UsbControllerShortcutStateMachine.Update) {
        var result = simulatorUiState.result
        for (action in update.actions) {
            when (action) {
                UsbControllerShortcutStateMachine.Action.SCHEDULE_LONG_PRESS -> {
                    result = ShortcutSimulatorResult.IDLE
                    simulatorHandler.removeCallbacks(simulatorLongPressRunnable)
                    simulatorHandler.postDelayed(
                        simulatorLongPressRunnable,
                        ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS.toLong()
                    )
                }
                UsbControllerShortcutStateMachine.Action.CANCEL_LONG_PRESS ->
                    simulatorHandler.removeCallbacks(simulatorLongPressRunnable)
                UsbControllerShortcutStateMachine.Action.SHOW_HINT ->
                    result = ShortcutSimulatorResult.HINT
                UsbControllerShortcutStateMachine.Action.HIDE_HINT ->
                    if (result == ShortcutSimulatorResult.HINT) result = ShortcutSimulatorResult.IDLE
                UsbControllerShortcutStateMachine.Action.TOGGLE_MOUSE_EMULATION ->
                    result = ShortcutSimulatorResult.MOUSE_EMULATION
                UsbControllerShortcutStateMachine.Action.OPEN_GAME_MENU -> {
                    result = ShortcutSimulatorResult.GAME_MENU
                    simulatorStateMachine.onGameMenuOpenResult(false)
                }
                UsbControllerShortcutStateMachine.Action.EXIT_STREAM ->
                    result = ShortcutSimulatorResult.EXIT_STREAM
            }
        }
        simulatorUiState = ShortcutSimulatorUiState(
            result = result,
            hintVisible = simulatorStateMachine.isHintVisible(),
            pressedFlags = simulatorPressedFlags,
            controllerName = simulatorUiState.controllerName
        )
    }

    private fun simulatorButtonFlag(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> ControllerPacket.PLAY_FLAG
            KeyEvent.KEYCODE_BUTTON_B -> ControllerPacket.B_FLAG
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> ControllerPacket.BACK_FLAG
            KeyEvent.KEYCODE_BUTTON_L1 -> ControllerPacket.LB_FLAG
            KeyEvent.KEYCODE_BUTTON_R1 -> ControllerPacket.RB_FLAG
            else -> null
        }
    }
}

private enum class ShortcutSimulatorResult {
    IDLE,
    HINT,
    MOUSE_EMULATION,
    GAME_MENU,
    EXIT_STREAM
}

private data class ShortcutSimulatorUiState(
    val result: ShortcutSimulatorResult = ShortcutSimulatorResult.IDLE,
    val hintVisible: Boolean = false,
    val pressedFlags: Int = 0,
    val controllerName: String? = null
)

private object ControllerDiagnostics {
    enum class ConnectionType {
        USB,
        BLUETOOTH_OR_WIRELESS,
        BUILT_IN
    }

    enum class InputPath {
        SYSTEM,
        USB_TAKEOVER,
        UNAVAILABLE
    }

    enum class ShortcutSupport {
        SYSTEM_ALL,
        SYSTEM_EXIT_ONLY,
        USB_ALL,
        USB_EXIT_ONLY,
        UNAVAILABLE
    }

    enum class Note {
        USB_TAKEOVER_READY,
        USB_PERMISSION_REQUIRED,
        SYSTEM_USB,
        SYSTEM_WIRELESS,
        SYSTEM_BUILT_IN,
        USB_DRIVER_DISABLED_OR_UNSUPPORTED
    }

    data class Device(
        val name: String,
        val connectionType: ConnectionType,
        val inputPath: InputPath,
        val shortcutSupport: ShortcutSupport,
        val vendorId: Int,
        val productId: Int,
        val note: Note
    )

    data class Snapshot(val devices: List<Device>)

    fun scan(context: Context): Snapshot {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val prefs = PreferenceConfiguration.readPreferences(context)
        val systemDevices = connectedSystemGamepads().toMutableList()
        val devices = mutableListOf<Device>()

        val usbDevices = usbManager?.deviceList?.values.orEmpty()
            .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceId }))

        for (usbDevice in usbDevices) {
            val matchedIndex = systemDevices.indexOfFirst { inputDevice ->
                samePhysicalIds(inputDevice.vendorId, inputDevice.productId, usbDevice.vendorId, usbDevice.productId)
            }
            val matchedSystemDevice = if (matchedIndex >= 0) systemDevices.removeAt(matchedIndex) else null
            val supportedByUsbDriver = runCatching {
                UsbDriverService.shouldClaimDevice(usbDevice, true)
            }.getOrDefault(false)

            if (matchedSystemDevice == null && !supportedByUsbDriver) {
                continue
            }

            val willUseUsbTakeover = prefs.usbDriver && supportedByUsbDriver && runCatching {
                UsbDriverService.shouldClaimDevice(usbDevice, prefs.bindAllUsb)
            }.getOrDefault(false)

            val inputPath = when {
                willUseUsbTakeover -> InputPath.USB_TAKEOVER
                matchedSystemDevice != null -> InputPath.SYSTEM
                else -> InputPath.UNAVAILABLE
            }
            val note = when (inputPath) {
                InputPath.USB_TAKEOVER -> {
                    if (usbManager?.hasPermission(usbDevice) == true) Note.USB_TAKEOVER_READY
                    else Note.USB_PERMISSION_REQUIRED
                }
                InputPath.SYSTEM -> Note.SYSTEM_USB
                InputPath.UNAVAILABLE -> Note.USB_DRIVER_DISABLED_OR_UNSUPPORTED
            }

            devices += Device(
                name = usbDisplayName(usbDevice, matchedSystemDevice, context),
                connectionType = ConnectionType.USB,
                inputPath = inputPath,
                shortcutSupport = shortcutSupport(inputPath, prefs.enableStartKeyMenu),
                vendorId = usbDevice.vendorId,
                productId = usbDevice.productId,
                note = note
            )
        }

        for (inputDevice in systemDevices) {
            val connectionType = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !inputDevice.isExternal -> ConnectionType.BUILT_IN
                else -> ConnectionType.BLUETOOTH_OR_WIRELESS
            }
            devices += Device(
                name = inputDevice.name.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.controller_diag_unknown_device),
                connectionType = connectionType,
                inputPath = InputPath.SYSTEM,
                shortcutSupport = shortcutSupport(InputPath.SYSTEM, prefs.enableStartKeyMenu),
                vendorId = inputDevice.vendorId,
                productId = inputDevice.productId,
                note = when (connectionType) {
                    ConnectionType.BUILT_IN -> Note.SYSTEM_BUILT_IN
                    else -> Note.SYSTEM_WIRELESS
                }
            )
        }

        return Snapshot(
            devices.sortedWith(
                compareBy<Device>({ it.connectionType.ordinal }, { it.name.lowercase(Locale.ROOT) })
            )
        )
    }

    private fun shortcutSupport(inputPath: InputPath, startKeyActionEnabled: Boolean): ShortcutSupport {
        return when (inputPath) {
            InputPath.SYSTEM -> {
                if (startKeyActionEnabled) ShortcutSupport.SYSTEM_ALL
                else ShortcutSupport.SYSTEM_EXIT_ONLY
            }
            InputPath.USB_TAKEOVER -> {
                if (startKeyActionEnabled) ShortcutSupport.USB_ALL
                else ShortcutSupport.USB_EXIT_ONLY
            }
            InputPath.UNAVAILABLE -> ShortcutSupport.UNAVAILABLE
        }
    }

    private fun connectedSystemGamepads(): List<InputDevice> {
        return InputDevice.getDeviceIds()
            .map { deviceId -> InputDevice.getDevice(deviceId) }
            .filterNotNull()
            .filter(::isGamepad)
            .distinctBy { inputDevice ->
                inputDevice.descriptor.takeIf { it.isNotBlank() }
                    ?: "${inputDevice.vendorId}:${inputDevice.productId}:${inputDevice.name}"
            }
    }

    private fun isGamepad(inputDevice: InputDevice): Boolean {
        if (inputDevice.isVirtual) return false
        val sources = inputDevice.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun samePhysicalIds(firstVendorId: Int, firstProductId: Int, secondVendorId: Int, secondProductId: Int): Boolean {
        if (firstVendorId == 0 && firstProductId == 0) return false
        if (secondVendorId == 0 && secondProductId == 0) return false
        return firstVendorId == secondVendorId && firstProductId == secondProductId
    }

    private fun usbDisplayName(usbDevice: UsbDevice, systemDevice: InputDevice?, context: Context): String {
        val usbName = runCatching { usbDevice.productName }.getOrNull()
        return usbName?.takeIf { it.isNotBlank() }
            ?: systemDevice?.name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.controller_diag_unknown_device)
    }
}

@Composable
private fun ControllerDiagnosticScreen(
    snapshot: ControllerDiagnostics.Snapshot,
    simulatorState: ShortcutSimulatorUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val background = colorResource(R.color.game_menu_dialog_background)
    val primary = colorResource(R.color.game_menu_text_primary)
    val secondary = colorResource(R.color.game_menu_text_secondary)
    val accent = colorResource(R.color.game_menu_accent)
    val baseColorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(
        colorScheme = baseColorScheme.copy(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.12f),
            surface = colorResource(R.color.game_menu_card_background),
            onSurface = primary,
            surfaceVariant = colorResource(R.color.game_menu_card_background),
            onSurfaceVariant = secondary,
            outline = colorResource(R.color.game_menu_dialog_border)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ControllerDiagnosticTopBar(
                    primary = primary,
                    secondary = secondary,
                    accent = accent,
                    onBack = onBack,
                    onRefresh = onRefresh
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        ShortcutSimulatorCard(state = simulatorState)
                    }

                    if (snapshot.devices.isEmpty()) {
                        item {
                            EmptyControllerCard()
                        }
                    } else {
                        items(snapshot.devices) { device ->
                            ControllerCard(device)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutSimulatorCard(
    state: ShortcutSimulatorUiState
) {
    val accent = colorResource(R.color.game_menu_accent)
    DiagnosticCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.compact)
        ) {
            Text(
                text = stringResource(R.string.controller_diag_simulator_title),
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.controller_diag_simulator_summary),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Surface(
                color = if (state.hintVisible) {
                    accent.copy(alpha = 0.14f)
                } else {
                    colorResource(R.color.game_menu_dialog_background)
                },
                shape = GameMenuCardShape,
                border = BorderStroke(
                    GameMenuDimens.surfaceStroke,
                    if (state.hintVisible) accent.copy(alpha = 0.42f)
                    else colorResource(R.color.game_menu_button_border)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(GameMenuDimens.section),
                    verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
                ) {
                    Text(
                        text = stringResource(R.string.controller_diag_simulator_status),
                        color = colorResource(R.color.game_menu_text_secondary),
                        fontSize = 11.sp
                    )
                    Text(
                        text = simulatorResultLabel(state.result),
                        color = if (state.hintVisible) accent
                        else colorResource(R.color.game_menu_text_primary),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 19.sp
                    )
                    Text(
                        text = state.controllerName?.let {
                            stringResource(R.string.controller_diag_simulator_controller, it)
                        } ?: stringResource(R.string.controller_diag_simulator_waiting_input),
                        color = colorResource(R.color.game_menu_text_secondary),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = stringResource(R.string.controller_diag_simulator_current_buttons),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 11.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControllerKeyIndicator("Start", state.pressedFlags and ControllerPacket.PLAY_FLAG != 0)
                ControllerKeyIndicator("B", state.pressedFlags and ControllerPacket.B_FLAG != 0)
                ControllerKeyIndicator("Select", state.pressedFlags and ControllerPacket.BACK_FLAG != 0)
                ControllerKeyIndicator("LB", state.pressedFlags and ControllerPacket.LB_FLAG != 0)
                ControllerKeyIndicator("RB", state.pressedFlags and ControllerPacket.RB_FLAG != 0)
            }
        }
    }
}

@Composable
private fun ControllerKeyIndicator(label: String, pressed: Boolean) {
    val accent = colorResource(R.color.game_menu_accent)
    Surface(
        color = accent.copy(alpha = if (pressed) 0.22f else 0.05f),
        shape = GameMenuCardShape,
        border = BorderStroke(
            GameMenuDimens.surfaceStroke,
            accent.copy(alpha = if (pressed) 0.72f else 0.18f)
        )
    ) {
        Text(
            text = label,
            color = if (pressed) accent else colorResource(R.color.game_menu_text_secondary),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun simulatorResultLabel(result: ShortcutSimulatorResult): String {
    return stringResource(
        when (result) {
            ShortcutSimulatorResult.IDLE -> R.string.controller_diag_simulator_idle
            ShortcutSimulatorResult.HINT -> R.string.usb_shortcut_hold_hint
            ShortcutSimulatorResult.MOUSE_EMULATION -> R.string.controller_diag_simulator_mouse_result
            ShortcutSimulatorResult.GAME_MENU -> R.string.controller_diag_simulator_menu_result
            ShortcutSimulatorResult.EXIT_STREAM -> R.string.controller_diag_simulator_exit_result
        }
    )
}

@Composable
private fun ControllerDiagnosticTopBar(
    primary: Color,
    secondary: Color,
    accent: Color,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        color = colorResource(R.color.game_menu_dialog_background),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Surface(
            color = colorResource(R.color.game_menu_card_background),
            shape = GameMenuCardShape,
            border = BorderStroke(
                GameMenuDimens.surfaceStroke,
                colorResource(R.color.game_menu_dialog_border)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = GameMenuDimens.section),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .gamepadFocusOutline(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24),
                        contentDescription = stringResource(R.string.controller_diag_back),
                        tint = primary,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.08f))
                            .border(
                                GameMenuDimens.surfaceStroke,
                                accent.copy(alpha = 0.18f),
                                CircleShape
                            )
                            .padding(8.dp)
                    )
                }

                Spacer(Modifier.width(GameMenuDimens.section))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.controller_diag_title),
                        color = primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.controller_diag_subtitle),
                        color = secondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .gamepadFocusOutline(CircleShape)
                        .clickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phc_action_reset),
                        contentDescription = stringResource(R.string.controller_diag_refresh),
                        tint = accent,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.10f))
                            .border(
                                GameMenuDimens.surfaceStroke,
                                accent.copy(alpha = 0.20f),
                                CircleShape
                            )
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    contentPadding: PaddingValues = PaddingValues(GameMenuDimens.section),
    content: @Composable () -> Unit
) {
    Surface(
        color = colorResource(R.color.game_menu_card_background),
        shape = GameMenuCardShape,
        border = BorderStroke(
            GameMenuDimens.surfaceStroke,
            colorResource(R.color.game_menu_button_border)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

@Composable
private fun EmptyControllerCard() {
    DiagnosticCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.section)
        ) {
            Icon(
                painter = painterResource(R.drawable.phc_gamepad),
                contentDescription = null,
                tint = colorResource(R.color.game_menu_text_secondary),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.controller_diag_empty_title),
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.controller_diag_empty_summary),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControllerCard(device: ControllerDiagnostics.Device) {
    val accent = colorResource(R.color.game_menu_accent)
    DiagnosticCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.compact)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.phc_gamepad),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = device.name,
                    color = colorResource(R.color.game_menu_text_primary),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiagnosticTag(
                    text = connectionLabel(device.connectionType),
                    color = accent
                )
                DiagnosticTag(
                    text = inputPathLabel(device.inputPath),
                    color = inputPathColor(device.inputPath)
                )
            }

            KeyValueRow(
                key = stringResource(R.string.controller_diag_connection),
                value = connectionLabel(device.connectionType)
            )
            KeyValueRow(
                key = stringResource(R.string.controller_diag_stream_path),
                value = inputPathLabel(device.inputPath)
            )
            KeyValueRow(
                key = stringResource(R.string.controller_diag_shortcuts),
                value = shortcutSupportLabel(device.shortcutSupport)
            )
            KeyValueRow(
                key = stringResource(R.string.controller_diag_vid_pid),
                value = formatVidPid(device.vendorId, device.productId)
            )

            Text(
                text = noteLabel(device.note),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticTag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), GameMenuCardShape)
            .border(GameMenuDimens.surfaceStroke, color.copy(alpha = 0.22f), GameMenuCardShape)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = key,
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 13.sp,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            color = colorResource(R.color.game_menu_text_primary),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun connectionLabel(connectionType: ControllerDiagnostics.ConnectionType): String {
    return stringResource(
        when (connectionType) {
            ControllerDiagnostics.ConnectionType.USB -> R.string.controller_diag_connection_usb
            ControllerDiagnostics.ConnectionType.BLUETOOTH_OR_WIRELESS -> R.string.controller_diag_connection_wireless
            ControllerDiagnostics.ConnectionType.BUILT_IN -> R.string.controller_diag_connection_builtin
        }
    )
}

@Composable
private fun inputPathLabel(inputPath: ControllerDiagnostics.InputPath): String {
    return stringResource(
        when (inputPath) {
            ControllerDiagnostics.InputPath.SYSTEM -> R.string.controller_diag_path_system
            ControllerDiagnostics.InputPath.USB_TAKEOVER -> R.string.controller_diag_path_usb_takeover
            ControllerDiagnostics.InputPath.UNAVAILABLE -> R.string.controller_diag_path_unavailable
        }
    )
}

@Composable
private fun inputPathColor(inputPath: ControllerDiagnostics.InputPath): Color {
    return when (inputPath) {
        ControllerDiagnostics.InputPath.SYSTEM -> Color(0xFF7CCBFF)
        ControllerDiagnostics.InputPath.USB_TAKEOVER -> Color(0xFF8CE99A)
        ControllerDiagnostics.InputPath.UNAVAILABLE -> Color(0xFFFFB36B)
    }
}

@Composable
private fun shortcutSupportLabel(shortcutSupport: ControllerDiagnostics.ShortcutSupport): String {
    return stringResource(
        when (shortcutSupport) {
            ControllerDiagnostics.ShortcutSupport.SYSTEM_ALL -> R.string.controller_diag_shortcuts_system_all
            ControllerDiagnostics.ShortcutSupport.SYSTEM_EXIT_ONLY -> R.string.controller_diag_shortcuts_system_exit_only
            ControllerDiagnostics.ShortcutSupport.USB_ALL -> R.string.controller_diag_shortcuts_usb_all
            ControllerDiagnostics.ShortcutSupport.USB_EXIT_ONLY -> R.string.controller_diag_shortcuts_usb_exit_only
            ControllerDiagnostics.ShortcutSupport.UNAVAILABLE -> R.string.controller_diag_shortcuts_unavailable
        }
    )
}

@Composable
private fun noteLabel(note: ControllerDiagnostics.Note): String {
    return stringResource(
        when (note) {
            ControllerDiagnostics.Note.USB_TAKEOVER_READY -> R.string.controller_diag_note_usb_ready
            ControllerDiagnostics.Note.USB_PERMISSION_REQUIRED -> R.string.controller_diag_note_usb_permission
            ControllerDiagnostics.Note.SYSTEM_USB -> R.string.controller_diag_note_system_usb
            ControllerDiagnostics.Note.SYSTEM_WIRELESS -> R.string.controller_diag_note_system_wireless
            ControllerDiagnostics.Note.SYSTEM_BUILT_IN -> R.string.controller_diag_note_system_builtin
            ControllerDiagnostics.Note.USB_DRIVER_DISABLED_OR_UNSUPPORTED -> R.string.controller_diag_note_unavailable
        }
    )
}

private fun formatVidPid(vendorId: Int, productId: Int): String {
    if (vendorId == 0 && productId == 0) return "—"
    return String.format(Locale.US, "%04X:%04X", vendorId, productId)
}
