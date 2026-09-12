package com.limelight.preferences

import android.app.Activity
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.R
import com.limelight.binding.input.StickCenterStore
import com.limelight.utils.UiHelper
import java.util.Locale

/** Captures raw Android joystick axes, before deadzone and host Y inversion. */
class StickCalibrationActivity : Activity() {
    private lateinit var store: StickCenterStore
    private lateinit var coordinates: TextView
    private lateinit var save: Button
    private lateinit var reset: Button
    private var device: InputDevice? = null
    private var values = emptyMap<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        store = StickCenterStore(this)
        title = getString(R.string.stick_calibration_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(padding + insets.systemWindowInsetLeft,
                    padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight,
                    padding + insets.systemWindowInsetBottom)
                insets
            }
        }
        content.addView(TextView(this).apply { setText(R.string.stick_calibration_help) })
        coordinates = TextView(this).apply { setText(R.string.stick_calibration_waiting) }
        content.addView(coordinates)
        save = Button(this).apply {
            setText(R.string.stick_calibration_save)
            isEnabled = false
            setOnClickListener {
                val current = connectedDevice() ?: return@setOnClickListener
                store.save(current, values)
                showCoordinates()
                Toast.makeText(this@StickCalibrationActivity, R.string.stick_calibration_saved, Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(save)
        reset = Button(this).apply {
            setText(R.string.stick_calibration_reset)
            isEnabled = false
            setOnClickListener {
                val current = connectedDevice() ?: return@setOnClickListener
                store.reset(current)
                showCoordinates()
                Toast.makeText(this@StickCalibrationActivity, R.string.stick_calibration_reset_done, Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(reset)
        content.addView(Button(this).apply {
            setText(R.string.stick_calibration_close)
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(content) })
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
        save.isEnabled = captured.values.all { it.isFinite() && it in -0.9f..0.9f }
        reset.isEnabled = true
        showCoordinates()
        return true
    }

    private fun showCoordinates() {
        val current = device ?: return
        coordinates.text = current.name + "\n\n" + getString(R.string.stick_calibration_columns) + "\n" +
            values.entries.joinToString("\n") { (axis, value) ->
                String.format(Locale.US, "%s:  %.4f / %.4f / %.4f", MotionEvent.axisToString(axis),
                    value, store.center(current, axis), store.correct(current, axis, value))
            }
    }
}
