package com.limelight.binding.input.advance_setting

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.limelight.R
import kotlin.math.roundToInt

class KeyboardUIController(
    private val parentContainer: FrameLayout,
    private val listener: OnKeyboardEventListener,
    private val hostContext: Context
) : KeyboardGestureDetector.GestureListener {

    interface OnKeyboardEventListener {
        fun sendKeyEvent(down: Boolean, keyCode: Short)
        fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int)
        fun onKeyboardVisibilityChanged(visible: Boolean) {}
    }

    private val prefs: SharedPreferences
    private val keyboardLayout: FrameLayout
    private val keyboardContent: KeyboardTouchGuardLayout
    private val rail: LinearLayout
    private val pageHost: FrameLayout
    private val layoutMain: KeyboardPageView
    private val layoutNav: KeyboardPageView
    private val layoutNum: KeyboardPageView
    private val btnMain: TextView
    private val btnNum: TextView
    private val btnNav: TextView
    private val btnOpacity: View
    private val btnMini: View
    private val btnResize: View
    private val btnHide: View
    private val grip: KeyboardGripView
    private val modifierChips: LinearLayout
    private val keyPopup: TextView?

    private val modifierStates: MutableMap<Int, Int> = HashMap()
    private val physicallyHeldModifiers: MutableSet<Int> = HashSet()
    private val pressedSpecs: MutableMap<View, KeySpec> = HashMap()
    private var sentLeftShift = false
    private var sentRightShift = false

    private var currentPage = KeyboardPage.MAIN
    private var opacityPercent = 100
    private var isMini = false
    private var opacityPopup: PopupWindow? = null
    private var opacitySeek: SeekBar? = null
    private var opacityValue: TextView? = null
    private var opacityPresets: List<TextView> = emptyList()
    private var resizePopup: PopupWindow? = null
    private var resizeSeek: SeekBar? = null
    private var resizeValue: TextView? = null
    private var resizePresets: List<TextView> = emptyList()
    private var keyHeightOverrideDp: Float? = null
    private var metrics: KeyboardMetrics? = null
    private var mainBuilt = false
    private var lastWidthPx = 0
    private var lastHeightPx = 0
    private var fullTransX = 0f
    private var fullTransY = 0f
    private var miniTransX = 0f
    private var miniTransY = 0f
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTransX = 0f
    private var dragStartTransY = 0f

    private fun activeTransX(): Float = if (isMini) miniTransX else fullTransX
    private fun activeTransY(): Float = if (isMini) miniTransY else fullTransY

    init {
        prefs = hostContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        var root = parentContainer.findViewById<View>(R.id.layer_6_keyboard)
        if (root == null) {
            root = LayoutInflater.from(hostContext).inflate(R.layout.layer_6_keyboard, parentContainer, true)
            keyboardLayout = parentContainer.findViewById(R.id.layer_6_keyboard)
        } else {
            keyboardLayout = root as FrameLayout
        }

        keyboardContent = keyboardLayout.findViewById(R.id.keyboard_content)
        rail = keyboardLayout.findViewById(R.id.keyboard_rail)
        pageHost = keyboardLayout.findViewById(R.id.keyboard_page_host)
        layoutMain = keyboardLayout.findViewById(R.id.layout_main)
        layoutNav = keyboardLayout.findViewById(R.id.layout_nav)
        layoutNum = keyboardLayout.findViewById(R.id.layout_num)
        btnMain = keyboardLayout.findViewById(R.id.btn_key_page_main)
        btnNum = keyboardLayout.findViewById(R.id.btn_key_page_num)
        btnNav = keyboardLayout.findViewById(R.id.btn_key_page_nav)
        btnOpacity = keyboardLayout.findViewById(R.id.btn_keyboard_opacity)
        btnMini = keyboardLayout.findViewById(R.id.btn_keyboard_mini)
        btnResize = keyboardLayout.findViewById(R.id.btn_keyboard_resize)
        btnHide = keyboardLayout.findViewById(R.id.btn_keyboard_hide)
        grip = keyboardLayout.findViewById(R.id.keyboard_drag_grip)
        modifierChips = keyboardLayout.findViewById(R.id.keyboard_modifier_chips)
        keyPopup = keyboardLayout.findViewById(R.id.keyboard_key_popup)

        initModifierStates()
        setupRail()
        loadSettings()
        applyMiniButton()

        keyboardLayout.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 && (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                updateLayout(width, height)
            }
        }
        keyboardLayout.post { updateLayout() }
        applyOpacity()
        pageVisibility()
    }

    private fun initModifierStates() {
        modifierStates[KEY_LCTRL] = MOD_NEUTRAL
        modifierStates[KEY_RCTRL] = MOD_NEUTRAL
        modifierStates[KEY_LSHIFT] = MOD_NEUTRAL
        modifierStates[KEY_RSHIFT] = MOD_NEUTRAL
        modifierStates[KEY_LALT] = MOD_NEUTRAL
        modifierStates[KEY_RALT] = MOD_NEUTRAL
        modifierStates[KEY_LWIN] = MOD_NEUTRAL
        modifierStates[KEY_RWIN] = MOD_NEUTRAL
    }

    private fun loadSettings() {
        val storedPercent = if (prefs.contains(KEY_OPACITY_PERCENT)) {
            prefs.getInt(KEY_OPACITY_PERCENT, 100)
        } else {
            prefs.getInt(KEY_OPACITY, 10) * 10
        }
        opacityPercent = storedPercent.coerceIn(30, 100)
        isMini = prefs.getBoolean(KEY_MINI, prefs.getBoolean(KEY_IS_MINI, false))
        val storedHeight = prefs.getFloat(KEY_HEIGHT_DP, -1f)
        keyHeightOverrideDp = if (storedHeight >= 31f) storedHeight else null

        val pageName = prefs.getString(KEY_PAGE, KeyboardPage.MAIN.name) ?: KeyboardPage.MAIN.name
        currentPage = runCatching { KeyboardPage.valueOf(pageName) }.getOrDefault(KeyboardPage.MAIN)

        fullTransX = prefs.getFloat(KEY_X, 0f)
        fullTransY = prefs.getFloat(KEY_Y, 0f)
        miniTransX = prefs.getFloat(KEY_MINI_X, 0f)
        miniTransY = prefs.getFloat(KEY_MINI_Y, 0f)
    }

    private fun setupRail() {
        btnMain.setOnClickListener { setPage(KeyboardPage.MAIN) }
        btnNum.setOnClickListener { setPage(KeyboardPage.NUM) }
        btnNav.setOnClickListener { setPage(KeyboardPage.NAV) }
        btnOpacity.setOnClickListener { toggleOpacityPopover() }
        btnMini.setOnClickListener { toggleMini() }
        btnResize.setOnClickListener { toggleResizePopover() }
        btnHide.setOnClickListener { hide() }
        grip.setOnTouchListener { _, event -> handleGripTouch(event) }
    }

    private fun updateLayout() {
        val width = if (keyboardLayout.width > 0) keyboardLayout.width else hostContext.resources.displayMetrics.widthPixels
        val height = if (keyboardLayout.height > 0) keyboardLayout.height else hostContext.resources.displayMetrics.heightPixels
        updateLayout(width, height)
    }

    private fun updateLayout(screenWidthPx: Int, screenHeightPx: Int) {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return
        if (mainBuilt && screenWidthPx == lastWidthPx && screenHeightPx == lastHeightPx) {
            applyTranslations()
            applyOpacity()
            return
        }
        lastWidthPx = screenWidthPx
        lastHeightPx = screenHeightPx
        val density = hostContext.resources.displayMetrics.density
        val m = KeyboardGeometry.compute(
            screenWidthPx.toFloat(),
            screenHeightPx.toFloat(),
            density,
            isMini,
            keyHeightOverrideDp
        )
        metrics = m
        if (m.tier != KeyboardTier.POCKET && currentPage == KeyboardPage.NUMPAD) {
            currentPage = KeyboardPage.NUM
            prefs.edit().putString(KEY_PAGE, currentPage.name).apply()
        }

        val contentParams = (keyboardContent.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(m.dockWidthPx, m.dockHeightPx)
        contentParams.width = m.dockWidthPx
        contentParams.height = m.dockHeightPx
        contentParams.gravity = if (isMini) {
            Gravity.BOTTOM or Gravity.RIGHT
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        contentParams.setMargins(0, 0, if (isMini) m.marginPx else 0, m.marginPx)
        keyboardContent.layoutParams = contentParams

        val railParams = (rail.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, m.railHeightPx)
        railParams.width = LinearLayout.LayoutParams.MATCH_PARENT
        railParams.height = m.railHeightPx
        railParams.bottomMargin = m.railGapPx
        rail.layoutParams = railParams
        rail.setPadding(m.padPx + 1, 0, m.padPx + 1, 0)

        pageHost.setPadding(m.padPx, 0, m.padPx, m.padPx)

        btnMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.tabTextSp)
        btnNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.tabTextSp)
        btnNav.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.tabTextSp)
        applyTabLabels(m)

        layoutMain.bind(KeyboardPageLayouts.spec(KeyboardPage.MAIN, m.tier), m) { page -> setPage(page) }
        bindNumPage(m)
        layoutNav.bind(KeyboardPageLayouts.spec(KeyboardPage.NAV, m.tier), m) { page -> setPage(page) }
        attachKeys(layoutMain.keys())
        attachKeys(layoutNav.keys())
        mainBuilt = true

        applyModifierStates()
        pageVisibility()
        applyTranslations()
        applyOpacity()
        keyboardContent.requestLayout()
        keyboardLayout.requestLayout()
    }

    private fun bindNumPage(m: KeyboardMetrics) {
        val page = if (currentPage == KeyboardPage.NUMPAD) KeyboardPage.NUMPAD else KeyboardPage.NUM
        layoutNum.bind(KeyboardPageLayouts.spec(page, m.tier), m) { setPage(it) }
        attachKeys(layoutNum.keys())
    }

    private fun attachKeys(keys: List<KeyboardKeyView>) {
        keys.forEach { key ->
            val spec = key.keySpec ?: return@forEach
            if (spec.isPageKey || spec.code <= 0) return@forEach
            key.tag = "k" + spec.code
            val detector = KeyboardGestureDetector(key, this)
            key.setOnTouchListener { view, event -> detector.onTouchEvent(view, event) }
            key.setOnClickListener {
                if (pressedSpecs.containsKey(key)) return@setOnClickListener
                onKeyPress(key, spec.code)
                onKeyRelease(key, spec.code)
            }
        }
    }

    private fun applyModifierStates() {
        modifierStates.forEach { (keyCode, state) ->
            updateModifierUI(keyCode, state)
        }
    }

    private fun pageVisibility() {
        layoutMain.visibility = if (currentPage == KeyboardPage.MAIN) View.VISIBLE else View.GONE
        layoutNav.visibility = if (currentPage == KeyboardPage.NAV) View.VISIBLE else View.GONE
        layoutNum.visibility = if (currentPage == KeyboardPage.NUM || currentPage == KeyboardPage.NUMPAD) View.VISIBLE else View.GONE
        styleTabs()
        keyPopup?.visibility = View.GONE
    }

    private fun styleTabs() {
        val active = ContextCompat.getColor(hostContext, R.color.crown_keyboard_single_border)
        val idle = ContextCompat.getColor(hostContext, R.color.crown_text_secondary)
        btnMain.isSelected = currentPage == KeyboardPage.MAIN
        val numSelected = currentPage == KeyboardPage.NUM || currentPage == KeyboardPage.NUMPAD
        btnNum.isSelected = numSelected
        btnNav.isSelected = currentPage == KeyboardPage.NAV
        btnMain.setTextColor(if (currentPage == KeyboardPage.MAIN) active else idle)
        btnNum.setTextColor(if (numSelected) active else idle)
        btnNav.setTextColor(if (currentPage == KeyboardPage.NAV) active else idle)
        TextViewCompat.setCompoundDrawableTintList(btnMain, ColorStateList.valueOf(if (currentPage == KeyboardPage.MAIN) active else idle))
        TextViewCompat.setCompoundDrawableTintList(btnNum, ColorStateList.valueOf(if (numSelected) active else idle))
        TextViewCompat.setCompoundDrawableTintList(btnNav, ColorStateList.valueOf(if (currentPage == KeyboardPage.NAV) active else idle))
    }

    private fun setPage(page: KeyboardPage) {
        opacityPopup?.dismiss()
        resizePopup?.dismiss()
        val previousPage = currentPage
        currentPage = page
        if ((previousPage == KeyboardPage.NUMPAD) != (page == KeyboardPage.NUMPAD)) {
            metrics?.let { bindNumPage(it) }
            applyModifierStates()
        }
        pageVisibility()
        prefs.edit().putString(KEY_PAGE, page.name).apply()
    }

    private fun applyMiniButton() {
        val button = btnMini as? ImageButton ?: return
        button.setImageResource(if (isMini) R.drawable.ic_keyboard_expand else R.drawable.ic_keyboard_mini)
        button.contentDescription = hostContext.getString(
            if (isMini) R.string.keyboard_expand else R.string.keyboard_mini
        )
    }

    private fun toggleMini() {
        opacityPopup?.dismiss()
        resizePopup?.dismiss()
        isMini = !isMini
        prefs.edit().putBoolean(KEY_MINI, isMini).apply()
        lastWidthPx = 0
        lastHeightPx = 0
        applyMiniButton()
        keyboardLayout.post { updateLayout() }
    }

    private fun applyTabLabels(metrics: KeyboardMetrics) {
        val dockDp = metrics.dockWidthPx / hostContext.resources.displayMetrics.density
        val iconOnly = dockDp < 340f
        val short = dockDp < 380f
        val mainLabel = hostContext.getString(R.string.keyboard_tab_main)
        val numLabel = hostContext.getString(R.string.keyboard_tab_num)
        val navLabel = hostContext.getString(R.string.keyboard_tab_nav)
        btnMain.text = when {
            iconOnly -> ""
            short -> hostContext.getString(R.string.keyboard_tab_main_short)
            else -> mainLabel
        }
        btnNum.text = when {
            iconOnly -> ""
            short -> hostContext.getString(R.string.keyboard_tab_num_short)
            else -> numLabel
        }
        btnNav.text = when {
            iconOnly -> ""
            short -> hostContext.getString(R.string.keyboard_tab_nav_short)
            else -> navLabel
        }
        btnMain.contentDescription = mainLabel
        btnNum.contentDescription = numLabel
        btnNav.contentDescription = navLabel
        val hideSecondaryTools = dockDp < 300f
        btnMini.visibility = if (hideSecondaryTools && !isMini) View.GONE else View.VISIBLE
        btnResize.visibility = if (hideSecondaryTools) View.GONE else View.VISIBLE
        btnOpacity.visibility = if (dockDp < 260f && isMini) View.GONE else View.VISIBLE
    }

    private fun toggleResizePopover() {
        opacityPopup?.dismiss()
        val wasShowing = resizePopup?.isShowing == true
        resizePopup?.dismiss()
        if (wasShowing) return

        val content = sliderContent(R.string.keyboard_key_height)
        val seek = content.findViewById<SeekBar>(R.id.keyboard_slider_seek)
        val value = content.findViewById<TextView>(R.id.keyboard_slider_value)
        val presets = sliderPresets(content, listOf(31, 38, 44, 50), "", ::setKeyHeightDp)
        resizeSeek = seek
        resizeValue = value
        resizePresets = presets
        val current = currentKeyHeightDp()
        val maxHeight = effectiveMaxKeyHeightDp()
        seek.max = (maxHeight - 31).coerceAtLeast(0)
        seek.progress = (current - 31).coerceIn(0, seek.max)
        presets.forEach { preset ->
            preset.visibility = if ((preset.tag as? Int ?: 0) <= maxHeight) View.VISIBLE else View.GONE
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setKeyHeightDp(progress + 31)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        applyKeyHeightPopover()

        resizePopup = showPopover(content) {
            resizePopup = null
            resizeSeek = null
            resizeValue = null
            resizePresets = emptyList()
        }
    }

    private fun effectiveMaxKeyHeightDp(): Int =
        (metrics?.maxKeyHeightDp ?: 50f).roundToInt().coerceIn(31, 50)

    private fun currentKeyHeightDp(): Int {
        val density = hostContext.resources.displayMetrics.density
        val heightPx = metrics?.keyHeightPx ?: (42f * density)
        return (heightPx / density).roundToInt().coerceIn(31, effectiveMaxKeyHeightDp())
    }

    private fun setKeyHeightDp(heightDp: Int) {
        val applied = heightDp.coerceIn(31, effectiveMaxKeyHeightDp())
        keyHeightOverrideDp = applied.toFloat()
        prefs.edit().putFloat(KEY_HEIGHT_DP, applied.toFloat()).apply()
        lastWidthPx = 0
        lastHeightPx = 0
        updateLayout()
        applyKeyHeightPopover()
    }

    private fun applyKeyHeightPopover() {
        val current = currentKeyHeightDp()
        val maxHeight = effectiveMaxKeyHeightDp()
        resizeSeek?.let { seek ->
            seek.max = (maxHeight - 31).coerceAtLeast(0)
            val target = (current - 31).coerceIn(0, seek.max)
            if (seek.progress != target) seek.progress = target
        }
        resizeValue?.text = current.toString() + "dp"
        resizePresets.forEach { preset ->
            preset.visibility = if ((preset.tag as? Int ?: 0) <= maxHeight) View.VISIBLE else View.GONE
            preset.isSelected = preset.tag == current
        }
        btnResize.contentDescription = hostContext.getString(R.string.keyboard_resize) + " " + current + "dp"
    }

    private fun toggleOpacityPopover() {
        resizePopup?.dismiss()
        val wasShowing = opacityPopup?.isShowing == true
        opacityPopup?.dismiss()
        if (wasShowing) return

        val content = sliderContent(R.string.keyboard_opacity_title)
        val seek = content.findViewById<SeekBar>(R.id.keyboard_slider_seek)
        val value = content.findViewById<TextView>(R.id.keyboard_slider_value)
        val presets = sliderPresets(content, listOf(100, 85, 70, 50), "%", ::setOpacityPercent)
        opacitySeek = seek
        opacityValue = value
        opacityPresets = presets
        seek.max = 70
        seek.progress = (opacityPercent - 30).coerceIn(0, 70)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setOpacityPercent(progress + 30)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        applyOpacity()

        opacityPopup = showPopover(content) {
            opacityPopup = null
            opacitySeek = null
            opacityValue = null
            opacityPresets = emptyList()
        }
    }

    private fun sliderContent(title: Int): View = LayoutInflater.from(hostContext)
        .inflate(R.layout.keyboard_slider_popover, keyboardLayout, false).apply {
            findViewById<TextView>(R.id.keyboard_slider_title).setText(title)
        }

    private fun sliderPresets(content: View, values: List<Int>, suffix: String, onClick: (Int) -> Unit): List<TextView> {
        val ids = listOf(R.id.keyboard_slider_preset_1, R.id.keyboard_slider_preset_2,
            R.id.keyboard_slider_preset_3, R.id.keyboard_slider_preset_4)
        return values.zip(ids).map { (value, id) ->
            content.findViewById<TextView>(id).apply {
                text = value.toString() + suffix
                tag = value
                setOnClickListener { onClick(value) }
            }
        }
    }

    private fun showPopover(content: View, onDismiss: () -> Unit): PopupWindow {
        val density = hostContext.resources.displayMetrics.density
        val screenWidth = hostContext.resources.displayMetrics.widthPixels
        val widthPx = minOf((250 * density).toInt(), screenWidth - (16 * density).toInt())
            .coerceAtLeast((180 * density).toInt())
        content.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // Do not steal the game window's focus: focus changes clear its modifier state.
        val popup = PopupWindow(content, widthPx, content.measuredHeight, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = 18 * density
        popup.setOnDismissListener { onDismiss() }
        val location = IntArray(2)
        keyboardContent.getLocationOnScreen(location)
        var x = location[0] + keyboardContent.width - widthPx - (metrics?.padPx ?: 0)
        var y = location[1] - content.measuredHeight - (6 * density).toInt()
        x = x.coerceIn((8 * density).toInt(), (screenWidth - widthPx - 8 * density).toInt().coerceAtLeast(0))
        if (y < (8 * density).toInt()) {
            y = location[1] + keyboardContent.height + (6 * density).toInt()
        }
        popup.showAtLocation(keyboardLayout, Gravity.NO_GRAVITY, x, y)
        return popup
    }

    private fun setOpacityPercent(percent: Int) {
        opacityPercent = percent.coerceIn(30, 100)
        applyOpacity()
        prefs.edit()
            .putInt(KEY_OPACITY_PERCENT, opacityPercent)
            .putInt(KEY_OPACITY, opacityPercent / 10)
            .apply()
    }

    private fun applyOpacity() {
        keyboardContent.alpha = opacityPercent / 100f
        keyboardLayout.alpha = 1f
        btnOpacity.contentDescription = hostContext.getString(R.string.keyboard_opacity) + " " + opacityPercent + "%"
        opacitySeek?.let { seek ->
            val target = (opacityPercent - 30).coerceIn(0, 70)
            if (seek.progress != target) seek.progress = target
        }
        opacityValue?.text = opacityPercent.toString() + "%"
        opacityPresets.forEach { preset -> preset.isSelected = preset.tag == opacityPercent }
    }

    private fun handleGripTouch(event: MotionEvent): Boolean {
        val m = metrics ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartTransX = activeTransX()
                dragStartTransY = activeTransY()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val width = keyboardLayout.width
                val height = keyboardLayout.height
                if (width <= 0 || height <= 0) return true
                val baseLeft = if (isMini) {
                    (width - m.dockWidthPx - m.marginPx).toFloat()
                } else {
                    (width - m.dockWidthPx) / 2f
                }
                val baseTop = (height - m.marginPx - m.dockHeightPx).toFloat()
                val minX = -baseLeft
                val maxX = (width - m.dockWidthPx - baseLeft).coerceAtLeast(minX)
                val minY = -baseTop
                val maxY = (height - m.dockHeightPx - baseTop).coerceAtLeast(minY)
                keyboardContent.translationX = (dragStartTransX + event.rawX - dragStartRawX).coerceIn(minX, maxX)
                keyboardContent.translationY = (dragStartTransY + event.rawY - dragStartRawY).coerceIn(minY, maxY)
                positionModifierChips()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isMini) {
                    miniTransX = keyboardContent.translationX
                    miniTransY = keyboardContent.translationY
                    prefs.edit().putFloat(KEY_MINI_X, miniTransX).putFloat(KEY_MINI_Y, miniTransY).apply()
                } else {
                    fullTransX = keyboardContent.translationX
                    fullTransY = keyboardContent.translationY
                    prefs.edit().putFloat(KEY_X, fullTransX).putFloat(KEY_Y, fullTransY).apply()
                }
                return true
            }
        }
        return false
    }

    private fun applyTranslations() {
        val m = metrics ?: return
        val width = keyboardLayout.width
        val height = keyboardLayout.height
        if (width <= 0 || height <= 0) return
        val baseLeft = if (isMini) {
            (width - m.dockWidthPx - m.marginPx).toFloat()
        } else {
            (width - m.dockWidthPx) / 2f
        }
        val baseTop = (height - m.marginPx - m.dockHeightPx).toFloat()
        val minX = -baseLeft
        val maxX = (width - m.dockWidthPx - baseLeft).coerceAtLeast(minX)
        val minY = -baseTop
        val maxY = (height - m.dockHeightPx - baseTop).coerceAtLeast(minY)
        if (isMini) {
            miniTransX = miniTransX.coerceIn(minX, maxX)
            miniTransY = miniTransY.coerceIn(minY, maxY)
            keyboardContent.translationX = miniTransX
            keyboardContent.translationY = miniTransY
        } else {
            fullTransX = fullTransX.coerceIn(minX, maxX)
            fullTransY = fullTransY.coerceIn(minY, maxY)
            keyboardContent.translationX = fullTransX
            keyboardContent.translationY = fullTransY
        }
        positionModifierChips()
    }

    override fun onKeyPress(view: View, keyCode: Int) {
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("toggle")
            val currentState = modifierStates[keyCode] ?: MOD_NEUTRAL
            if (currentState == MOD_NEUTRAL) {
                modifierStates[keyCode] = MOD_SINGLE
                physicallyHeldModifiers.add(keyCode)
                sendModifierState(keyCode, true)
                updateModifierUI(keyCode, MOD_SINGLE)
            } else {
                handleModifierDown(keyCode)
            }
        } else {
            triggerHaptic("normal")
            if (pressedSpecs.containsKey(view)) return
            val spec = (view as? KeyboardKeyView)?.keySpec ?: KeySpec(code = keyCode, label = "")
            val alreadyPressed = pressedSpecs.values.any { it.sendCode == spec.sendCode }
            pressedSpecs[view] = spec
            if (spec.shift) {
                syncShiftKeys()
            }
            if (!alreadyPressed) listener.sendKeyEvent(true, spec.sendCode.toShort())
            if (view.isShown) {
                showPopup(view)
            }
        }
    }

    private fun triggerHaptic(type: String) {
        val duration = if (type == "toggle" || type == "heavy") 20 else 10
        listener.rumbleSingleVibrator(1000.toShort(), 1000.toShort(), duration)
    }

    private fun showPopup(view: View) {
        val popup = keyPopup ?: return
        val text: String? = (view as? KeyboardKeyView)?.keySpec?.popupLabel ?: return
        popup.text = text
        val density = hostContext.resources.displayMetrics.density
        val popupWidth = 50f * density
        val popupHeight = 64f * density
        val keyLocation = IntArray(2)
        view.getLocationOnScreen(keyLocation)
        val parentLocation = IntArray(2)
        keyboardLayout.getLocationOnScreen(parentLocation)
        val relativeX = keyLocation[0] - parentLocation[0]
        val relativeY = keyLocation[1] - parentLocation[1]
        val maxX = (keyboardLayout.width - popupWidth).coerceAtLeast(0f)
        popup.x = (relativeX + view.width / 2f - popupWidth / 2f).coerceIn(0f, maxX)
        popup.y = (relativeY - popupHeight).coerceAtLeast(0f)
        popup.visibility = View.VISIBLE
    }

    override fun onKeyRelease(view: View, keyCode: Int) {
        if (!modifierStates.containsKey(keyCode)) {
            if (!releasePressedKey(view)) return
            resetSingleModifiers()
            keyPopup?.visibility = View.GONE
        } else {
            physicallyHeldModifiers.remove(keyCode)
        }
    }

    private fun releasePressedKey(view: View): Boolean {
        val spec = pressedSpecs.remove(view) ?: return false
        if (pressedSpecs.values.none { it.sendCode == spec.sendCode }) {
            listener.sendKeyEvent(false, spec.sendCode.toShort())
        }
        if (spec.shift) {
            syncShiftKeys()
        }
        return true
    }

    private fun isExplicitShiftActive(keyCode: Int): Boolean =
        (modifierStates[keyCode] ?: MOD_NEUTRAL) != MOD_NEUTRAL

    // A shifted key owns Shift until key-up, even if an explicit Shift is cleared.
    private fun syncShiftKeys() {
        val right = isExplicitShiftActive(KEY_RSHIFT)
        val left = isExplicitShiftActive(KEY_LSHIFT) || (pressedSpecs.values.any { it.shift } && !right)
        // Press a replacement before releasing the previous Shift.
        if (left && !sentLeftShift) listener.sendKeyEvent(true, KEY_LSHIFT.toShort())
        if (right && !sentRightShift) listener.sendKeyEvent(true, KEY_RSHIFT.toShort())
        if (!left && sentLeftShift) listener.sendKeyEvent(false, KEY_LSHIFT.toShort())
        if (!right && sentRightShift) listener.sendKeyEvent(false, KEY_RSHIFT.toShort())
        sentLeftShift = left
        sentRightShift = right
    }

    private fun sendModifierState(keyCode: Int, down: Boolean) {
        if (keyCode == KEY_LSHIFT || keyCode == KEY_RSHIFT) {
            syncShiftKeys()
        } else {
            listener.sendKeyEvent(down, keyCode.toShort())
        }
    }

    override fun onModifierHoldRelease(view: View, keyCode: Int) {
        if (modifierStates.containsKey(keyCode)) {
            physicallyHeldModifiers.remove(keyCode)
            val currentState = modifierStates[keyCode] ?: MOD_NEUTRAL
            if (currentState == MOD_SINGLE) {
                modifierStates[keyCode] = MOD_NEUTRAL
                sendModifierState(keyCode, false)
                updateModifierUI(keyCode, MOD_NEUTRAL)
            }
        } else {
            if (!releasePressedKey(view)) return
            resetSingleModifiers()
            keyPopup?.visibility = View.GONE
        }
    }

    override fun onLongPress(view: View, keyCode: Int) = Unit

    override fun onDoubleTap(view: View, keyCode: Int) {
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("heavy")
            modifierStates[keyCode] = MOD_NEUTRAL
            physicallyHeldModifiers.remove(keyCode)
            updateModifierUI(keyCode, MOD_NEUTRAL)
            sendModifierState(keyCode, false)
        } else {
            onKeyPress(view, keyCode)
        }
    }

    private fun handleModifierDown(keyCode: Int) {
        val currentState = modifierStates[keyCode] ?: MOD_NEUTRAL
        val newState = (currentState + 1) % 3
        modifierStates[keyCode] = newState
        when (newState) {
            MOD_NEUTRAL -> sendModifierState(keyCode, false)
            MOD_SINGLE -> sendModifierState(keyCode, true)
            MOD_LOCKED -> Unit
        }
        updateModifierUI(keyCode, newState)
    }

    private fun resetSingleModifiers() {
        val keys = modifierStates.keys.toList()
        keys.forEach { keyCode ->
            if (modifierStates[keyCode] == MOD_SINGLE && !physicallyHeldModifiers.contains(keyCode)) {
                modifierStates[keyCode] = MOD_NEUTRAL
                sendModifierState(keyCode, false)
                updateModifierUI(keyCode, MOD_NEUTRAL)
            }
        }
    }

    private fun updateModifierUI(keyCode: Int, state: Int) {
        if (!mainBuilt) return
        layoutMain.keys().forEach { key ->
            if (key.keySpec?.code == keyCode) key.setModifierState(state)
        }
        layoutNum.keys().forEach { key ->
            if (key.keySpec?.code == keyCode) key.setModifierState(state)
        }
        layoutNav.keys().forEach { key ->
            if (key.keySpec?.code == keyCode) key.setModifierState(state)
        }
        refreshModifierChips()
    }

    private fun refreshModifierChips() {
        modifierChips.removeAllViews()
        val groups = listOf(
            "Shift" to intArrayOf(KEY_LSHIFT, KEY_RSHIFT),
            "Ctrl" to intArrayOf(KEY_LCTRL, KEY_RCTRL),
            "Alt" to intArrayOf(KEY_LALT, KEY_RALT),
            "Win" to intArrayOf(KEY_LWIN, KEY_RWIN)
        )
        var any = false
        groups.forEach { (label, codes) ->
            val hasActive = codes.any { (modifierStates[it] ?: MOD_NEUTRAL) != MOD_NEUTRAL }
            if (!hasActive) return@forEach
            any = true
            val chip = TextView(hostContext).apply {
                text = label
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(hostContext, R.color.crown_keyboard_single_border))
                setBackgroundResource(R.drawable.keyboard_chip_bg)
                setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
                setOnClickListener { clearModifierGroup(codes) }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            params.marginEnd = (4 * hostContext.resources.displayMetrics.density).toInt()
            modifierChips.addView(chip, params)
        }
        modifierChips.visibility = if (any) View.VISIBLE else View.GONE
        if (any) {
            modifierChips.post { positionModifierChips() }
        }
    }

    private fun clearModifierGroup(codes: IntArray) {
        codes.forEach { code ->
            if ((modifierStates[code] ?: MOD_NEUTRAL) != MOD_NEUTRAL) {
                modifierStates[code] = MOD_NEUTRAL
                physicallyHeldModifiers.remove(code)
                sendModifierState(code, false)
            }
        }
        applyModifierStates()
        refreshModifierChips()
    }

    private fun positionModifierChips() {
        if (keyboardLayout.visibility != View.VISIBLE) return
        if (modifierChips.visibility == View.GONE) return
        if (modifierChips.height <= 0) {
            modifierChips.post { positionModifierChips() }
            return
        }
        val density = hostContext.resources.displayMetrics.density
        modifierChips.x = keyboardContent.x + (metrics?.padPx ?: 0)
        val above = keyboardContent.y - modifierChips.height - 4 * density
        val below = keyboardContent.y + keyboardContent.height + 4 * density
        val fallback = when {
            above >= 0f -> above
            keyboardContent.y >= modifierChips.height -> keyboardContent.y - modifierChips.height
            else -> below
        }
        val y = fallback.coerceIn(
            0f,
            (keyboardLayout.height - modifierChips.height).toFloat().coerceAtLeast(0f)
        )
        // In an extremely short viewport there may be no room outside the rail.
        // Keep its controls reachable and restore the chips when space returns.
        val railTop = keyboardContent.y + rail.y
        val overlapsRail = y < railTop + rail.height && y + modifierChips.height > railTop
        modifierChips.visibility = if (overlapsRail) View.INVISIBLE else View.VISIBLE
        modifierChips.y = y
    }

    fun resetInputState() {
        if (pressedSpecs.isEmpty() && physicallyHeldModifiers.isEmpty() &&
            modifierStates.values.all { it == MOD_NEUTRAL } && !sentLeftShift && !sentRightShift
        ) return

        val pressedCodes = pressedSpecs.values.map { it.sendCode }.toSet()
        pressedSpecs.clear()
        pressedCodes.forEach { listener.sendKeyEvent(false, it.toShort()) }
        val activeModifiers = modifierStates.filterValues { it != MOD_NEUTRAL }.keys.toList()
        modifierStates.keys.forEach { modifierStates[it] = MOD_NEUTRAL }
        physicallyHeldModifiers.clear()
        activeModifiers.filter { it != KEY_LSHIFT && it != KEY_RSHIFT }
            .forEach { listener.sendKeyEvent(false, it.toShort()) }
        syncShiftKeys()
        keyPopup?.visibility = View.GONE

        if (mainBuilt) {
            val keys = layoutMain.keys() + layoutNum.keys() + layoutNav.keys()
            keys.forEach { it.isPressed = false }
            attachKeys(keys)
            applyModifierStates()
        }
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun show() {
        keyboardLayout.visibility = View.VISIBLE
        parentContainer.visibility = View.VISIBLE
        listener.onKeyboardVisibilityChanged(true)
        applyOpacity()
        keyboardLayout.post {
            updateLayout()
            pageVisibility()
            positionModifierChips()
        }
    }

    fun hide() {
        opacityPopup?.dismiss()
        resizePopup?.dismiss()
        keyboardLayout.visibility = View.GONE
        parentContainer.visibility = View.GONE
        listener.onKeyboardVisibilityChanged(false)
        keyPopup?.visibility = View.GONE
    }

    val isVisible: Boolean
        get() = keyboardLayout.visibility == View.VISIBLE

    companion object {
        private const val PREF_NAME = "keyboard_settings"
        private const val KEY_OPACITY = "keyboard_opacity"
        private const val KEY_OPACITY_PERCENT = "keyboard_opacity_percent"
        private const val KEY_PAGE = "keyboard_page"
        private const val KEY_MINI = "keyboard_mini"
        private const val KEY_IS_MINI = "keyboard_is_mini"
        private const val KEY_HEIGHT_DP = "keyboard_key_height_dp"
        private const val KEY_X = "keyboard_x"
        private const val KEY_Y = "keyboard_y"
        private const val KEY_MINI_X = "keyboard_mini_x"
        private const val KEY_MINI_Y = "keyboard_mini_y"

        private const val MOD_NEUTRAL = 0
        private const val MOD_SINGLE = 1
        private const val MOD_LOCKED = 2

        private const val KEY_LCTRL = 113
        private const val KEY_RCTRL = 114
        private const val KEY_LSHIFT = 59
        private const val KEY_RSHIFT = 60
        private const val KEY_LALT = 57
        private const val KEY_RALT = 58
        private const val KEY_LWIN = 117
        private const val KEY_RWIN = 118

    }
}
