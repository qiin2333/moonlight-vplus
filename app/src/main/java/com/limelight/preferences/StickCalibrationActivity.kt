package com.limelight.preferences

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.R
import com.limelight.binding.input.StickCenterStore
import com.limelight.utils.UiHelper
import java.util.Locale
import com.limelight.utils.appAccentColor

/** Captures raw Android joystick axes, before deadzone and host Y inversion. */
class StickCalibrationActivity : AppCompatActivity() {
    private lateinit var store: StickCenterStore
    private var coordinates by mutableStateOf("")
    private var canSave by mutableStateOf(false)
    private var canReset by mutableStateOf(false)
    private var device: InputDevice? = null
    private var values = emptyMap<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        store = StickCenterStore(this)
        title = getString(R.string.stick_calibration_title)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            // AppCompat applies the app override to this configuration, including on pre-31 devices.
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
                outline = colorResource(R.color.game_menu_dialog_border)
            )
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background,
                    contentColor = colors.onBackground) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(stringResource(R.string.stick_calibration_title),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.stick_calibration_help))
                        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Text(coordinates.ifEmpty { stringResource(R.string.stick_calibration_waiting) },
                                modifier = Modifier.padding(16.dp))
                        }
                        Button(onClick = ::saveCenter, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.stick_calibration_save))
                        }
                        OutlinedButton(onClick = ::resetCenter, enabled = canReset, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.stick_calibration_reset))
                        }
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.stick_calibration_close))
                        }
                    }
                }
            }
        }
        UiHelper.notifyNewRootView(this)
    }

    private fun saveCenter() {
        val current = connectedDevice() ?: return
        store.save(current, values)
        showCoordinates()
        Toast.makeText(this, R.string.stick_calibration_saved, Toast.LENGTH_SHORT).show()
    }

    private fun resetCenter() {
        val current = connectedDevice() ?: return
        store.reset(current)
        showCoordinates()
        Toast.makeText(this, R.string.stick_calibration_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun connectedDevice(): InputDevice? {
        val captured = device ?: return null
        return InputDevice.getDevice(captured.id)?.takeIf { it.descriptor == captured.descriptor }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val current = event.device ?: return super.dispatchGenericMotionEvent(event)
        val axes = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        val captured = axes.filter { axis ->
            val range = current.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            range != null && range.min < 0f && range.max > 0f
        }.associateWith { event.getAxisValue(it) }
        if (captured.isEmpty()) return super.dispatchGenericMotionEvent(event)
        device = current
        values = captured
        canSave = captured.values.all { it.isFinite() && it in -0.9f..0.9f }
        canReset = true
        showCoordinates()
        return true
    }

    private fun showCoordinates() {
        val current = device ?: return
        coordinates = current.name + "\n\n" + getString(R.string.stick_calibration_columns) + "\n" +
            values.entries.joinToString("\n") { (axis, value) ->
                String.format(Locale.US, "%s:  %.4f / %.4f / %.4f", MotionEvent.axisToString(axis),
                    value, store.center(current, axis), store.correct(current, axis, value))
            }
    }
}
