/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.OneShotPreDrawListener
import androidx.preference.PreferenceManager
import com.limelight.utils.AppActionSheet
import com.limelight.R
import com.limelight.binding.input.ControllerHandler

class VirtualController(
    private var controllerHandler: ControllerHandler?,
    layout: FrameLayout?,
    private val context: Context,
) {
    class ControllerInputContext {
        var inputMap: Short = 0
        var leftTrigger: Byte = 0
        var rightTrigger: Byte = 0
        var rightStickX: Short = 0
        var rightStickY: Short = 0
        var leftStickX: Short = 0
        var leftStickY: Short = 0
    }

    enum class ControllerMode { Active, MoveButtons, ResizeButtons }

    private val frameLayout = requireNotNull(layout)
    val handler = Handler(Looper.getMainLooper())
    var controllerMode = ControllerMode.Active
        private set
    var controllerInputContext = ControllerInputContext()
        private set
    var layoutStyle = VirtualControllerLayout.XBOX
        internal set
    internal var profileScale = 1f
    internal var profileOffsetY = 0
    internal var profileWidth = 0
    internal var profileHeight = 0
    internal var onlyL3R3 = false
    val elements = mutableListOf<VirtualControllerElement>()
    private val buttonSources = mutableMapOf<Any, Int>()

    internal fun setButtonState(source: Any, flags: Int, pressed: Boolean) {
        if (pressed) buttonSources[source] = flags else buttonSources.remove(source)
        controllerInputContext.inputMap = buttonSources.values.fold(0) { bits, value -> bits or value }.toShort()
        sendControllerInputContext()
    }

    private val deviceVibrator by lazy { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }

    @Suppress("DEPRECATION")
    internal fun performClickHaptic() {
        if (controllerMode != ControllerMode.Active ||
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val vibrator = deviceVibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(10)
        }
    }

    private var hidden = false
    private var pendingLayoutRefresh: OneShotPreDrawListener? = null
    private val delayedRetransmit = Runnable { sendControllerInputContextInternal() }
    private var optionsDialog: Dialog? = null
    private val buttonConfigure = ImageButton(context).apply {
        isFocusable = false
        contentDescription = context.getString(R.string.osc_quick_menu)
        val density = resources.displayMetrics.density
        fun surface(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 10 * density
            setStroke(density.toInt().coerceAtLeast(1), 0xCCE1E6ED.toInt())
        }
        background = InsetDrawable(StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), surface(0xF03F5C80.toInt()))
            addState(intArrayOf(), surface(0xD91A2330.toInt()))
        }, (6 * density).toInt())
        setImageResource(R.drawable.ic_settings)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        // Contrast comes from a 36dp backplate, without enlarging the 48dp hit target.
        val padding = (12 * density).toInt()
        setPadding(padding, padding, padding, padding)
        setOnClickListener { showOptions() }
    }

    private fun showOptions() {
        if (optionsDialog?.isShowing == true) return
        releaseInputs()
        val names = context.resources.getStringArray(R.array.osc_layout_names)
        val actions = VirtualControllerLayout.entries.mapIndexed { index, preset ->
            AppActionSheet.Action(index, names[index], checked = preset == layoutStyle)
        }.toMutableList()
        actions += AppActionSheet.Action(100, context.getString(R.string.osc_action_move), sectionStart = true)
        actions += AppActionSheet.Action(101, context.getString(R.string.osc_action_resize))
        if (controllerMode != ControllerMode.Active) {
            actions += AppActionSheet.Action(102, context.getString(R.string.osc_action_done))
        }
        optionsDialog = AppActionSheet.show(
            context = context,
            title = context.getString(R.string.osc_quick_menu),
            actions = actions,
            onAction = { action ->
                when (action.id) {
                    in VirtualControllerLayout.entries.indices -> switchLayout(VirtualControllerLayout.entries[action.id])
                    100 -> startEditing(ControllerMode.MoveButtons)
                    101 -> startEditing(ControllerMode.ResizeButtons)
                    102 -> finishEditing()
                }
            },
            onDismiss = { optionsDialog = null },
        )
    }

    internal fun switchLayout(preset: VirtualControllerLayout) {
        if (controllerMode != ControllerMode.Active) finishEditing()
        releaseInputs()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("list_osc_layout", preset.preferenceValue).apply()
        refreshLayout()
    }

    internal fun startEditing(mode: ControllerMode) {
        require(mode != ControllerMode.Active)
        releaseInputs()
        controllerMode = mode
        Toast.makeText(context, if (mode == ControllerMode.MoveButtons) R.string.osc_move_buttons
            else R.string.osc_resize_buttons, Toast.LENGTH_SHORT).show()
        elements.forEach { it.invalidate() }
    }

    internal fun finishEditing() {
        VirtualControllerConfigurationLoader.saveProfile(this, context)
        controllerMode = ControllerMode.Active
        elements.forEach { it.invalidate() }
    }

    init {
        frameLayout.isMotionEventSplittingEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) frameLayout.isForceDarkAllowed = false
    }

    fun hide() {
        optionsDialog?.dismiss()
        releaseInputs()
        hidden = true
        elements.forEach { it.visibility = View.INVISIBLE }
        buttonConfigure.visibility = View.INVISIBLE
    }

    fun show() {
        hidden = false
        elements.forEach { it.visibility = View.VISIBLE }
        buttonConfigure.visibility = View.VISIBLE
    }

    /** Replaces the stream-scoped input sink after an automatic reconnect. */
    fun rebindControllerHandler(controllerHandler: ControllerHandler?) {
        this.controllerHandler = controllerHandler
    }

    private fun releaseInputs() {
        elements.forEach { it.releaseTouch() }
        buttonSources.clear()
        controllerInputContext = ControllerInputContext()
        sendControllerInputContext()
    }

    fun removeElements() {
        if (controllerMode != ControllerMode.Active) VirtualControllerConfigurationLoader.saveProfile(this, context)
        releaseInputs()
        elements.forEach { frameLayout.removeView(it) }
        elements.clear()
        frameLayout.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) = elements.forEach { it.setOpacity(opacity) }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        frameLayout.addView(element, FrameLayout.LayoutParams(width, height).apply {
            setMargins(x, y, 0, 0)
        })
        if (hidden) element.visibility = View.INVISIBLE
    }

    fun refreshLayout() {
        pendingLayoutRefresh?.removeListener()
        pendingLayoutRefresh = OneShotPreDrawListener.add(frameLayout) {
            pendingLayoutRefresh = null
            if (frameLayout.width > 0 && frameLayout.height > 0) {
                removeElements()
                VirtualControllerConfigurationLoader.createDefaultLayout(
                    this, context, frameLayout.width, frameLayout.height)
                VirtualControllerConfigurationLoader.loadFromPreferences(this, context)
                val size = (48 * context.resources.displayMetrics.density).toInt()
                    .coerceAtMost(minOf(frameLayout.width, frameLayout.height))
                frameLayout.addView(buttonConfigure, FrameLayout.LayoutParams(size, size).apply {
                    // Upper edge, just inside the left thumb cluster; avoids both sticks and shoulders.
                    leftMargin = (36 * profileScale).toInt().coerceIn(0, frameLayout.width - size)
                    topMargin = 0
                })
                buttonConfigure.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    private fun sendControllerInputContextInternal() {
        with(controllerInputContext) {
            controllerHandler?.reportOscState(inputMap.toInt(), leftStickX, leftStickY,
                rightStickX, rightStickY, leftTrigger, rightTrigger)
        }
    }

    fun sendControllerInputContext() {
        handler.removeCallbacks(delayedRetransmit)
        sendControllerInputContextInternal()
        // GFE can drop closely spaced packets; repeat neutral states too to avoid stuck input.
        for (delay in longArrayOf(25, 50, 75)) handler.postDelayed(delayedRetransmit, delay)
    }

    fun cleanup() {
        if (controllerMode != ControllerMode.Active) finishEditing()
        optionsDialog?.dismiss()
        pendingLayoutRefresh?.removeListener()
        pendingLayoutRefresh = null
        releaseInputs()
        handler.removeCallbacks(delayedRetransmit)
    }
}
