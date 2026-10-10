/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.app.Activity
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
import android.view.Gravity
import android.view.KeyEvent
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
import com.limelight.ui.FloatingButtonCoordinates
import kotlin.math.roundToInt

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
    private var optionsDialog: VirtualControllerOptionsDialog? = null
    private val defaultPreferences = PreferenceManager.getDefaultSharedPreferences(context)
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
        // The backplate keeps the gear readable over both bright and dark game scenes.
        val padding = (12 * density).toInt()
        setPadding(padding, padding, padding, padding)
        setOnClickListener { showOptions() }
    }
    private val settingsButtonStore = OscSettingsButtonStore(context)
    private val settingsButtonPosition = OscSettingsButtonPositionController(
        buttonConfigure,
        frameLayout,
        settingsButtonStore,
        ::defaultSettingsButtonPosition
    )

    private fun showOptions() {
        if (optionsDialog?.isShowing == true) return
        if ((context as? Activity)?.let { it.isFinishing || it.isDestroyed } == true) return
        releaseInputs()
        val names = context.resources.getStringArray(R.array.osc_layout_names)
        val actions = VirtualControllerLayout.entries.mapIndexed { index, preset ->
            AppActionSheet.Action(index, names[index], checked = preset == layoutStyle)
        }.toMutableList()
        actions += AppActionSheet.Action(
            VirtualControllerOptionsDialog.ACTION_ONLY_L3_R3,
            context.getString(R.string.title_only_l3r3),
            checked = defaultPreferences.getBoolean("checkbox_only_show_L3R3", false),
            toggle = true,
            sectionStart = true
        )
        actions += AppActionSheet.Action(
            VirtualControllerOptionsDialog.ACTION_SHOW_GUIDE,
            context.getString(R.string.title_show_guide_button),
            checked = defaultPreferences.getBoolean("checkbox_show_guide_button", true),
            toggle = true
        )
        actions += AppActionSheet.Action(
            VirtualControllerOptionsDialog.ACTION_HALF_HEIGHT,
            context.getString(R.string.title_half_height_osc_portrait),
            checked = defaultPreferences.getBoolean("checkbox_half_height_osc_portrait", true),
            toggle = true
        )
        actions += AppActionSheet.Action(100, context.getString(R.string.osc_action_move), sectionStart = true)
        actions += AppActionSheet.Action(101, context.getString(R.string.osc_action_resize))
        if (controllerMode != ControllerMode.Active) {
            actions += AppActionSheet.Action(102, context.getString(R.string.osc_action_done))
        }
        actions += AppActionSheet.Action(VirtualControllerOptionsDialog.ACTION_DRAG,
            context.getString(R.string.osc_allow_drag_settings_button),
            checked = settingsButtonStore.dragEnabled, sectionStart = true, toggle = true)
        actions += AppActionSheet.Action(VirtualControllerOptionsDialog.ACTION_RESET,
            context.getString(R.string.osc_reset_settings_button_position))
        actions += AppActionSheet.Action(
            VirtualControllerOptionsDialog.ACTION_RESET_CONTROLLER_LAYOUT,
            context.getString(R.string.title_reset_osc),
            sectionStart = true
        )
        val dialog = VirtualControllerOptionsDialog(
            context, actions,
            readAxes = { event ->
                (controllerHandler?.getGameMenuNavigationAxisPairs(event, includeRightStick = false)
                    ?: emptyList()) to (controllerHandler?.getMenuRightStickY(event) ?: 0f)
            },
            initialOpacity = defaultPreferences.getInt("seekbar_osc_opacity", 90),
            initialSizeScale = settingsButtonStore.sizeScale,
            onToggleChanged = { id, enabled ->
                when (id) {
                    VirtualControllerOptionsDialog.ACTION_DRAG -> settingsButtonPosition.setDragEnabled(enabled)
                    VirtualControllerOptionsDialog.ACTION_ONLY_L3_R3 -> {
                        defaultPreferences.edit().putBoolean("checkbox_only_show_L3R3", enabled).apply()
                        refreshLayout()
                    }
                    VirtualControllerOptionsDialog.ACTION_SHOW_GUIDE -> {
                        defaultPreferences.edit().putBoolean("checkbox_show_guide_button", enabled).apply()
                        refreshLayout()
                    }
                    VirtualControllerOptionsDialog.ACTION_HALF_HEIGHT -> {
                        defaultPreferences.edit().putBoolean("checkbox_half_height_osc_portrait", enabled).apply()
                        refreshLayout()
                    }
                }
            },
            onOpacityChanged = { opacity ->
                defaultPreferences.edit().putInt("seekbar_osc_opacity", opacity).apply()
                setOpacity(opacity)
            },
            onSizeScaleChanged = { scale ->
                settingsButtonStore.sizeScale = scale
                refreshSettingsButtonLayout()
            },
            onAction = { id ->
                when (id) {
                    in VirtualControllerLayout.entries.indices -> switchLayout(VirtualControllerLayout.entries[id])
                    100 -> startEditing(ControllerMode.MoveButtons)
                    101 -> startEditing(ControllerMode.ResizeButtons)
                    102 -> finishEditing()
                    VirtualControllerOptionsDialog.ACTION_RESET -> settingsButtonPosition.resetPosition()
                    VirtualControllerOptionsDialog.ACTION_RESET_CONTROLLER_LAYOUT -> resetSavedLayout()
                }
            },
            hostWindow = (context as? Activity)?.window,
        )
        optionsDialog = dialog
        dialog.setOnDismissListener {
            if (optionsDialog === dialog) {
                optionsDialog = null
                controllerHandler?.onExternalGameMenuDismissed()
            }
        }
        try {
            dialog.showMenu()
            controllerHandler?.onExternalGameMenuOpened()
        } catch (_: android.view.WindowManager.BadTokenException) {
            optionsDialog = null
            dialog.dismiss()
        }
    }

    fun dispatchMenuKey(event: KeyEvent): Boolean {
        val dialog = optionsDialog?.takeIf { it.isShowing } ?: return false
        dialog.dispatchKeyEvent(event)
        return true
    }

    fun dispatchMenuAxes(sourceId: Int, leftX: Float, leftY: Float, rightY: Float): Boolean {
        val dialog = optionsDialog?.takeIf { it.isShowing } ?: return false
        dialog.dispatchAxes(sourceId, listOf(leftX to leftY), rightY)
        return true
    }

    fun releaseMenuSource(sourceId: Int) { optionsDialog?.releaseSource(sourceId) }

    internal fun switchLayout(preset: VirtualControllerLayout) {
        if (controllerMode != ControllerMode.Active) finishEditing()
        releaseInputs()
        defaultPreferences.edit()
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

    private fun resetSavedLayout() {
        // Avoid refreshLayout() saving the editing session back over the reset.
        controllerMode = ControllerMode.Active
        VirtualControllerConfigurationLoader.clearSavedProfile(context)
        refreshLayout()
        Toast.makeText(context, R.string.toast_reset_osc_success, Toast.LENGTH_SHORT).show()
    }

    init {
        frameLayout.isMotionEventSplittingEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) frameLayout.isForceDarkAllowed = false
    }

    fun hide() {
        optionsDialog?.dismiss()
        settingsButtonPosition.cancelGesture()
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
        if (this.controllerHandler === controllerHandler) return
        this.controllerHandler = controllerHandler
        if (optionsDialog?.isShowing == true) controllerHandler?.onExternalGameMenuOpened()
    }

    private fun releaseInputs() {
        elements.forEach { it.releaseTouch() }
        buttonSources.clear()
        controllerInputContext = ControllerInputContext()
        sendControllerInputContext()
    }

    fun removeElements() {
        settingsButtonPosition.cancelGesture()
        if (controllerMode != ControllerMode.Active) VirtualControllerConfigurationLoader.saveProfile(this, context)
        releaseInputs()
        elements.forEach { frameLayout.removeView(it) }
        elements.clear()
        frameLayout.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) {
        elements.forEach { it.setOpacity(opacity) }
        buttonConfigure.alpha = opacity.coerceIn(0, 100) / 100f
    }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        frameLayout.addView(element, FrameLayout.LayoutParams(width, height).apply {
            setMargins(x, y, 0, 0)
        })
        if (hidden) element.visibility = View.INVISIBLE
    }

    fun refreshLayout() {
        settingsButtonPosition.cancelGesture()
        pendingLayoutRefresh?.removeListener()
        pendingLayoutRefresh = OneShotPreDrawListener.add(frameLayout) {
            pendingLayoutRefresh = null
            if (frameLayout.width > 0 && frameLayout.height > 0) {
                removeElements()
                VirtualControllerConfigurationLoader.createDefaultLayout(
                    this, context, frameLayout.width, frameLayout.height)
                VirtualControllerConfigurationLoader.loadFromPreferences(this, context)
                addOrUpdateSettingsButton()
            }
        }
    }

    private fun refreshSettingsButtonLayout() {
        if (frameLayout.width <= 0 || frameLayout.height <= 0) return
        addOrUpdateSettingsButton()
    }

    private fun addOrUpdateSettingsButton() {
        val density = context.resources.displayMetrics.density
        val guide = elements.firstOrNull {
            it.elementId == VirtualControllerElement.EID_GDB
        }
        val guideParams = guide?.layoutParams as? FrameLayout.LayoutParams
        val guideSize = guideParams?.let { minOf(it.width, it.height) }?.takeIf { it > 0 }
        val defaultGuideSize = ((if (layoutStyle == VirtualControllerLayout.CLASSIC) 7 else 10) * profileScale)
            .roundToInt()
            .takeIf { it > 0 }
        val baseSize = guideSize ?: defaultGuideSize ?: minOf(
            (48 * density).toInt(),
            (frameLayout.height * 0.06f).toInt().coerceAtLeast(1)
        )
        val size = (baseSize * settingsButtonStore.sizeScale).toInt()
            .coerceIn(1, minOf(frameLayout.width, frameLayout.height))
        val edgeMargin = minOf((8 * density).toInt(), size / 4)
        val inset = size / 8
        (buttonConfigure.background as? InsetDrawable)?.let { currentBackground ->
            buttonConfigure.background = InsetDrawable(currentBackground.drawable, inset)
        }
        buttonConfigure.setPadding(inset, inset, inset, inset)
        val horizontalGravity = if (frameLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL) Gravity.END else Gravity.START
        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or horizontalGravity).apply {
            leftMargin = edgeMargin.coerceAtMost(frameLayout.width - size)
            topMargin = edgeMargin.coerceAtMost(frameLayout.height - size)
        }
        if (buttonConfigure.parent == frameLayout) frameLayout.updateViewLayout(buttonConfigure, params)
        else frameLayout.addView(buttonConfigure, params)
        buttonConfigure.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        settingsButtonPosition.requestPlacement()
    }

    private fun defaultSettingsButtonPosition(): FloatingButtonCoordinates? {
        val guide = elements.firstOrNull {
            it.elementId == VirtualControllerElement.EID_GDB
        } ?: return null
        val guideParams = guide.layoutParams as? FrameLayout.LayoutParams ?: return null
        val buttonSize = buttonConfigure.layoutParams?.width?.takeIf { it > 0 } ?: return null
        val guideLeft = guide.left.takeIf { guide.width > 0 } ?: guideParams.leftMargin
        val guideTop = guide.top.takeIf { guide.height > 0 } ?: guideParams.topMargin
        val gap = (2 * context.resources.displayMetrics.density).toInt()
        return FloatingButtonCoordinates(
            guideLeft + (guideParams.width - buttonSize) / 2,
            guideTop - buttonSize - gap
        )
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
        settingsButtonPosition.dispose()
        pendingLayoutRefresh?.removeListener()
        pendingLayoutRefresh = null
        releaseInputs()
        handler.removeCallbacks(delayedRetransmit)
    }
}
