package com.limelight.preferences

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.R
import com.limelight.binding.audio.DEFAULT_MICROPHONE_INPUT
import com.limelight.binding.audio.MicrophoneInputDevice
import com.limelight.binding.audio.MicrophoneTestSession
import com.limelight.binding.audio.MicrophoneTestSnapshot
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.utils.UiHelper
import com.limelight.utils.appAccentColor
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

class MicrophoneTestActivity : ThemedAppCompatActivity() {
    private lateinit var session: MicrophoneTestSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        session = MicrophoneTestSession(this)
        setContent {
            MicrophoneTestScreen(
                session = session,
                onBack = ::finish
            )
        }
    }
    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}

@Composable
private fun MicrophoneTestScreen(
    session: MicrophoneTestSession,
    onBack: () -> Unit
) {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val baseColors = if (darkTheme) darkColorScheme() else lightColorScheme()
    val colors = baseColors.copy(
        primary = appAccentColor(),
        onPrimary = Color.White,
        background = colorResource(R.color.game_menu_dialog_background),
        onBackground = colorResource(R.color.game_menu_text_primary),
        surface = colorResource(R.color.game_menu_card_background),
        onSurface = colorResource(R.color.game_menu_text_primary),
        onSurfaceVariant = colorResource(R.color.game_menu_text_secondary)
    )
    var choices by remember { mutableStateOf(session.choices()) }
    var names by remember { mutableStateOf(session.displayNames(choices)) }
    var selectedKey by remember { mutableStateOf(DEFAULT_MICROPHONE_INPUT) }
    var pendingPermissionChoice by remember { mutableStateOf<MicrophoneInputDevice?>(null) }
    var snapshot by remember { mutableStateOf(MicrophoneTestSnapshot()) }
    val backDescription = stringResource(R.string.controller_diag_back)
    val context = LocalContext.current
    val initialFocus = remember { FocusRequester() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        choices = session.choices()
        names = session.displayNames(choices)
    }
    LaunchedEffect(Unit) {
        delay(120)
        initialFocus.requestFocus()
    }

    fun selectedDevice(): MicrophoneInputDevice? =
        choices.firstOrNull { it.key == selectedKey }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val choice = pendingPermissionChoice
        pendingPermissionChoice = null
        val bluetoothRequired = choice?.communication == true && android.os.Build.VERSION.SDK_INT >= 31
        fun isGranted(permission: String): Boolean = grants[permission]
            ?: (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
        val granted = isGranted(Manifest.permission.RECORD_AUDIO) &&
            (!bluetoothRequired || isGranted(Manifest.permission.BLUETOOTH_CONNECT))
        if (granted) {
            val refreshedChoices = session.choices()
            choices = refreshedChoices
            names = session.displayNames(refreshedChoices)
            val resolvedChoice = if (choice?.key == "communication:permission") {
                refreshedChoices.firstOrNull { it.communication }
            } else {
                choice
            }
            resolvedChoice?.let { selectedKey = it.key }
            session.start(resolvedChoice) { snapshot = it }
        } else {
            snapshot = MicrophoneTestSnapshot(error = context.getString(R.string.mic_test_error_permission))
        }
    }

    fun startTest() {
        val choice = selectedDevice()
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (choice?.communication == true && android.os.Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (permissions.isNotEmpty()) {
            pendingPermissionChoice = choice
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            session.start(choice) { snapshot = it }
        }
    }

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "‹",
                        fontSize = 30.sp,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .focusable()
                            .focusRequester(initialFocus)
                            .consumeConfirm(onBack)
                            .semantics { contentDescription = backDescription }
                    )
                    Text(
                        text = stringResource(R.string.title_mic_test),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.summary_mic_test),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
                ) {
                    item {
                        WaveformPanel(snapshot, colors)
                    }
                    item {
                        TestMetrics(snapshot, colors)
                    }
                    item {
                        Text(
                            text = stringResource(R.string.title_mic_test_devices),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item {
                        MicrophoneChoice(
                            label = stringResource(R.string.mic_input_default),
                            selected = selectedKey == DEFAULT_MICROPHONE_INPUT,
                            focusRequester = null
                        ) {
                            selectedKey = DEFAULT_MICROPHONE_INPUT
                            session.stop()
                            snapshot = MicrophoneTestSnapshot()
                        }
                    }
                    items(choices, key = { it.key }) { device ->
                        MicrophoneChoice(
                            label = names[device.key] ?: device.name,
                            selected = selectedKey == device.key,
                            focusRequester = null
                        ) {
                            selectedKey = device.key
                            session.stop()
                            snapshot = MicrophoneTestSnapshot()
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (snapshot.running) {
                                        session.stop()
                                        snapshot = MicrophoneTestSnapshot()
                                    } else {
                                        startTest()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .consumeConfirm {
                                        if (snapshot.running) {
                                            session.stop()
                                            snapshot = MicrophoneTestSnapshot()
                                        } else {
                                            startTest()
                                        }
                                    }
                            ) {
                                Text(
                                    stringResource(
                                        if (snapshot.running) R.string.mic_test_stop
                                        else R.string.mic_test_start
                                    )
                                )
                            }
                            Button(
                                onClick = {
                                    session.toggleRecording()
                                },
                                enabled = snapshot.running,
                                modifier = Modifier
                                    .weight(1f)
                                    .consumeConfirm { session.toggleRecording() }
                            ) {
                                Text(
                                    if (snapshot.recording) {
                                        stringResource(R.string.mic_test_stop_recording)
                                    } else {
                                        stringResource(R.string.mic_test_record)
                                    }
                                )
                            }
                            Button(
                                onClick = { session.playRecording() },
                                enabled = snapshot.hasRecording && !snapshot.playing,
                                modifier = Modifier
                                    .weight(1f)
                                    .consumeConfirm { session.playRecording() }
                            ) {
                                Text(
                                    stringResource(
                                        if (snapshot.playing) R.string.mic_test_playing
                                        else R.string.mic_test_play
                                    )
                                )
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MicrophoneChoice(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .fillMaxWidth()
        .selectable(selected, onClick = onClick, role = Role.RadioButton)
        .focusable()
        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
        .consumeConfirm(onClick)
        .padding(horizontal = 8.dp, vertical = 4.dp)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun WaveformPanel(snapshot: MicrophoneTestSnapshot, colors: androidx.compose.material3.ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(stringResource(R.string.mic_test_waveform), style = MaterialTheme.typography.titleMedium)
        WaveformGraph(
            values = snapshot.waveform,
            color = colors.primary,
            modifier = Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)
        )
        snapshot.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (snapshot.running) {
            Text(
                text = stringResource(R.string.mic_test_display_gain, formatGain(snapshot.displayGain)),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (!snapshot.running && snapshot.error == null) {
            Text(
                stringResource(R.string.mic_test_idle),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun TestMetrics(snapshot: MicrophoneTestSnapshot, colors: androidx.compose.material3.ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MetricRow(stringResource(R.string.mic_test_source), snapshot.source.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_selected_input), snapshot.selectedInput.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_bound_input), snapshot.boundInput.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_route), snapshot.route.ifBlank { "—" })
        MetricRow(
            stringResource(R.string.mic_test_communication_route),
            snapshot.communicationRoute.ifBlank { "—" }
        )
        MetricRow(
            stringResource(R.string.mic_test_preferred_route),
            snapshot.preferredRoute.ifBlank { "—" }
        )
        MetricRow(
            stringResource(R.string.mic_test_recording_route),
            snapshot.recordingRoute.ifBlank { "—" }
        )
        MetricRow(stringResource(R.string.mic_test_format), snapshot.format.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_buffer), snapshot.buffer.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_session), snapshot.session.ifBlank { "—" })
        MetricRow(stringResource(R.string.mic_test_rms), formatLevel(snapshot.rms))
        MetricRow(stringResource(R.string.mic_test_peak), formatLevel(snapshot.peak))
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label)
        Text(
            value,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun WaveformGraph(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.12f), RoundedCornerShape(6.dp))) {
        val center = size.height / 2f
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = androidx.compose.ui.geometry.Offset(0f, center),
            end = androidx.compose.ui.geometry.Offset(size.width, center),
            strokeWidth = 1f
        )
        if (values.size < 2) return@Canvas
        val path = Path()
        val peak = values.maxOf { abs(it) }
        val scale = if (peak < 0.04f) 1f else (0.78f / peak).coerceIn(1f, 12f)
        values.forEachIndexed { index, value ->
            val x = index * size.width / (values.size - 1).coerceAtLeast(1)
            val y = center - (value * scale).coerceIn(-1f, 1f) * center * 0.9f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

private fun formatLevel(value: Float): String =
    String.format(Locale.getDefault(), "%.1f%%", value.coerceIn(0f, 1f) * 100f)

private fun formatGain(value: Float): String =
    String.format(Locale.getDefault(), "%.1fx", value.coerceAtLeast(1f))

private fun Modifier.consumeConfirm(onConfirm: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        if (keyCode != AndroidKeyEvent.KEYCODE_BUTTON_A &&
            keyCode != AndroidKeyEvent.KEYCODE_DPAD_CENTER &&
            keyCode != AndroidKeyEvent.KEYCODE_ENTER &&
            keyCode != AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        ) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) onConfirm()
        true
    }
