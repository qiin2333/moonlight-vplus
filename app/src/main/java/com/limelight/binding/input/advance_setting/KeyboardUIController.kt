package com.limelight.binding.input.advance_setting

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import com.limelight.R

class KeyboardUIController(
    private val parentContainer: FrameLayout,
    private val listener: OnKeyboardEventListener,
    context: Context
) : KeyboardGestureDetector.GestureListener {

    interface OnKeyboardEventListener {
        fun sendKeyEvent(down: Boolean, keyCode: Short)
        fun rumbleSingleVibrator(lowFreq: Short, highFreq: Short, duration: Int)
    }
    private val keyboardLayout: FrameLayout
    private val keyboardContent: View
    private val opacitySeekbar: SeekBar
    private val prefs: SharedPreferences

    private val layoutMain: View?
    private val layoutNav: View?
    private val layoutNum: View?
    private val layoutMini: View?
    private val btnMain: TextView
    private val btnNav: TextView
    private val btnNum: TextView
    private val btnMini: TextView
    private val keyPopup: TextView?

    // Sticky Modifiers state: 0=Neutral, 1=Single, 2=Locked
    private val modifierStates: MutableMap<Int?, Int?> = HashMap<Int?, Int?>()

    // 追踪哪些修饰键正被手指物理按住
    private val physicallyHeldModifiers: MutableSet<Int?> = HashSet<Int?>()
    private val panelAlpha: View?
    private val panelNumMini: View?
    private val panelPcMini: View?

    //拖拽条中心区域（负责拖动）和修饰键容器（负责按键变色）
    private val miniDragHandle: View?
    private val miniModifierContainer: View?

    init {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Inflate the keyboard layout into the container if it doesn't already have it
        var view = parentContainer.findViewById<View?>(R.id.layer_6_keyboard)
        if (view == null) {
            view = LayoutInflater.from(context).inflate(
                R.layout.layer_6_keyboard,
                parentContainer, true
            )
            this.keyboardLayout = parentContainer.findViewById<FrameLayout>(R.id.layer_6_keyboard)
        } else {
            this.keyboardLayout = view as FrameLayout
        }

        keyboardContent = keyboardLayout.findViewById<View>(R.id.keyboard_content)
        opacitySeekbar = keyboardLayout.findViewById<SeekBar>(R.id.float_keyboard_seekbar)
        keyPopup = keyboardLayout.findViewById<TextView?>(R.id.keyboard_key_popup)

        layoutMain = keyboardLayout.findViewById<View?>(R.id.layout_main)
        layoutNav = keyboardLayout.findViewById<View?>(R.id.layout_nav)
        layoutNum = keyboardLayout.findViewById<View?>(R.id.layout_num)
        layoutMini = keyboardLayout.findViewById<View?>(R.id.layout_mini)

        btnMain = keyboardLayout.findViewById<TextView>(R.id.btn_key_page_main)
        btnNav = keyboardLayout.findViewById<TextView>(R.id.btn_key_page_nav)
        btnNum = keyboardLayout.findViewById<TextView>(R.id.btn_key_page_num)
        btnMini = keyboardLayout.findViewById<TextView>(R.id.btn_key_page_mini)

        // 初始化子面板
        panelAlpha = keyboardLayout.findViewById<View?>(R.id.panel_alpha)
        panelNumMini = keyboardLayout.findViewById<View?>(R.id.panel_num_mini)
        panelPcMini = keyboardLayout.findViewById<View?>(R.id.panel_pc_mini)

        // 初始化拖动区域和修饰键容器
        miniDragHandle = keyboardLayout.findViewById<View?>(R.id.mini_drag_handle)
        miniModifierContainer = keyboardLayout.findViewById<View?>(R.id.mini_modifier_container)

        initModifiers()
        initSeekbars()
        initTabs()
        updateTabStyle(btnMain, true)
        updateTabStyle(btnNav, false)
        updateTabStyle(btnNum, false)
        updateTabStyle(btnMini, false)

        loadSettings()

        setupTouchListeners(keyboardLayout)
    }

    private fun initModifiers() {
        modifierStates.put(KEY_LCTRL, MOD_NEUTRAL)
        modifierStates.put(KEY_RCTRL, MOD_NEUTRAL)
        modifierStates.put(KEY_LSHIFT, MOD_NEUTRAL)
        modifierStates.put(KEY_RSHIFT, MOD_NEUTRAL)
        modifierStates.put(KEY_LALT, MOD_NEUTRAL)
        modifierStates.put(KEY_RALT, MOD_NEUTRAL)
        modifierStates.put(KEY_LWIN, MOD_NEUTRAL)
    }

    private fun initSeekbars() {
        opacitySeekbar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = (progress * 0.1).toFloat()
                keyboardLayout.setAlpha(alpha)
                if (fromUser) {
                    prefs.edit().putInt(KEY_OPACITY, progress).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun syncResizeHandlePosition() {
        // Now inside keyboardContent LinearLayout, no manual positioning needed.
    }

    private fun loadSettings() {
        val savedHeight = prefs.getInt(KEY_HEIGHT, -1)
        if (savedHeight > 0) {
            val params = keyboardContent.getLayoutParams()
            params.height = savedHeight
            keyboardContent.setLayoutParams(params)
        }

        keyboardContent.setTranslationX(prefs.getFloat(KEY_X, 0f))
        keyboardContent.setTranslationY(prefs.getFloat(KEY_Y, 0f))

        val isMini = prefs.getBoolean(KEY_IS_MINI, false)
        setMiniMode(isMini)

        val savedOpacity = prefs.getInt(KEY_OPACITY, 10)
        opacitySeekbar.setProgress(savedOpacity)
        keyboardLayout.setAlpha((savedOpacity * 0.1).toFloat())
    }

    private fun setMiniMode(mini: Boolean) {
        val leftSidebar = keyboardLayout.findViewById<View>(R.id.keyboard_left_sidebar)
        val rightSidebar = keyboardLayout.findViewById<View>(R.id.keyboard_right_sidebar)
        val density = keyboardLayout.getContext().getResources().getDisplayMetrics().density

        if (mini) {
            leftSidebar.setVisibility(View.GONE)
            rightSidebar.setVisibility(View.GONE)

            layoutMain!!.setVisibility(View.GONE)
            layoutNav!!.setVisibility(View.GONE)
            layoutNum!!.setVisibility(View.GONE)
            layoutMini!!.setVisibility(View.VISIBLE)

            val params = keyboardContent.getLayoutParams() as FrameLayout.LayoutParams
            params.width = (360 * density).toInt()
            val margin = (20 * density).toInt()
            params.setMargins(margin, margin, margin, margin)
            keyboardContent.setLayoutParams(params)
        } else {
            leftSidebar.setVisibility(View.VISIBLE)
            rightSidebar.setVisibility(View.VISIBLE)

            layoutMini!!.setVisibility(View.GONE)
            layoutMain!!.setVisibility(View.VISIBLE)

            val params = keyboardContent.getLayoutParams() as FrameLayout.LayoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.setMargins(0, 0, 0, 0)
            keyboardContent.setLayoutParams(params)

            // Reset position when returning to full keyboard
            keyboardContent.setTranslationX(0f)
            keyboardContent.setTranslationY(0f)

            keyboardLayout.findViewById<View?>(R.id.keyboard_resize_handle).setVisibility(View.GONE)

            updateTabStyle(btnMain, true)
            updateTabStyle(btnNav, false)
            updateTabStyle(btnNum, false)
            updateTabStyle(btnMini, false)
        }
        prefs.edit().putBoolean(KEY_IS_MINI, mini).apply()
    }

    private fun initTabs() {
        val tabListener: View.OnClickListener = object : View.OnClickListener {
            override fun onClick(v: View) {
                val id = v.getId()
                if (id == R.id.btn_key_page_mini) {
                    setMiniMode(true)
                    return
                }

                layoutMain!!.setVisibility(if (id == R.id.btn_key_page_main) View.VISIBLE else View.GONE)
                layoutNav!!.setVisibility(if (id == R.id.btn_key_page_nav) View.VISIBLE else View.GONE)
                layoutNum!!.setVisibility(if (id == R.id.btn_key_page_num) View.VISIBLE else View.GONE)
                layoutMini!!.setVisibility(View.GONE)

                updateTabStyle(btnMain, id == R.id.btn_key_page_main)
                updateTabStyle(btnNav, id == R.id.btn_key_page_nav)
                updateTabStyle(btnNum, id == R.id.btn_key_page_num)
                updateTabStyle(btnMini, false)
            }
        }
        btnMain.setOnClickListener(tabListener)
        btnNav.setOnClickListener(tabListener)
        btnNum.setOnClickListener(tabListener)
        btnMini.setOnClickListener(tabListener)

        val panelAlpha = keyboardLayout.findViewById<View>(R.id.panel_alpha)
        val panelNumMini = keyboardLayout.findViewById<View>(R.id.panel_num_mini)
        val panelPcMini = keyboardLayout.findViewById<View>(R.id.panel_pc_mini)

        keyboardLayout.findViewById<View?>(R.id.btn_switch_num)
            .setOnClickListener(View.OnClickListener { v: View? ->
                panelAlpha.setVisibility(View.GONE)
                panelNumMini.setVisibility(View.VISIBLE)
            })
        keyboardLayout.findViewById<View?>(R.id.btn_keyboard_collapse_alpah)
            .setOnClickListener(View.OnClickListener { v: View? ->
                hide()
            })
        keyboardLayout.findViewById<View?>(R.id.btn_switch_pc)
            .setOnClickListener(View.OnClickListener { v: View? ->
                panelAlpha.setVisibility(View.GONE)
                panelPcMini.setVisibility(View.VISIBLE)
            })
        keyboardLayout.findViewById<View?>(R.id.btn_switch_alpha_from_num_bottom)
            .setOnClickListener(
                View.OnClickListener { v: View? ->
                    panelNumMini.setVisibility(View.GONE)
                    panelAlpha.setVisibility(View.VISIBLE)
                })
        keyboardLayout.findViewById<View?>(R.id.btn_keyboard_collapse_num_bottom)
            .setOnClickListener(
                View.OnClickListener { v: View? ->
                    hide()
                })
        keyboardLayout.findViewById<View?>(R.id.btn_switch_pc_from_num)
            .setOnClickListener(View.OnClickListener { v: View? ->
                panelNumMini.setVisibility(View.GONE)
                panelPcMini.setVisibility(View.VISIBLE)
            })
        keyboardLayout.findViewById<View?>(R.id.btn_switch_alpha_from_pc)
            .setOnClickListener(View.OnClickListener { v: View? ->
                panelPcMini.setVisibility(View.GONE)
                panelAlpha.setVisibility(View.VISIBLE)
            })

        val backToFullListener = View.OnClickListener { v: View? -> setMiniMode(false) }
        keyboardLayout.findViewById<View?>(R.id.btn_switch_full)
            .setOnClickListener(backToFullListener)
        keyboardLayout.findViewById<View?>(R.id.btn_switch_full_from_num)
            .setOnClickListener(backToFullListener)

        val btnCollapse = keyboardLayout.findViewById<TextView?>(R.id.btn_keyboard_collapse)
        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(View.OnClickListener { v: View? -> hide() })
        }

        val btnResize = keyboardLayout.findViewById<TextView?>(R.id.btn_keyboard_resize)
        val resizeHandle = keyboardLayout.findViewById<View?>(R.id.keyboard_resize_handle)

        val dragListener: OnTouchListener = object : OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialX = 0f
            private var initialY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.getAction()) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.getRawX()
                        initialTouchY = event.getRawY()
                        initialX = keyboardContent.getTranslationX()
                        initialY = keyboardContent.getTranslationY()
                        v.setPressed(true)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.getRawX() - initialTouchX
                        val dy = event.getRawY() - initialTouchY
                        keyboardContent.setTranslationX(initialX + dx)
                        keyboardContent.setTranslationY(initialY + dy)

                        if (resizeHandle!!.getVisibility() == View.VISIBLE) {
                            syncResizeHandlePosition()
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        prefs.edit()
                            .putFloat(KEY_X, keyboardContent.getTranslationX())
                            .putFloat(KEY_Y, keyboardContent.getTranslationY())
                            .apply()
                        v.setPressed(false)
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        v.setPressed(false)
                        return true
                    }
                }
                return false
            }
        }

        // 绑定拖动事件给指定的 FrameLayout (而不再是整个修饰键的一行)
        if (miniDragHandle != null) miniDragHandle.setOnTouchListener(dragListener)

        val resizeToggleListener = View.OnClickListener { v: View? ->
            val isVisible = resizeHandle!!.getVisibility() == View.VISIBLE
            resizeHandle.setVisibility(if (isVisible) View.GONE else View.VISIBLE)
            updateTabStyle(btnResize, !isVisible)
            if (!isVisible) syncResizeHandlePosition()
        }
        if (btnResize != null) btnResize.setOnClickListener(resizeToggleListener)

        if (resizeHandle != null) {
            resizeHandle.setOnTouchListener(object : OnTouchListener {
                private var initialTouchY = 0f
                private var initialHeight = 0

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.getAction()) {
                        MotionEvent.ACTION_DOWN -> {
                            initialTouchY = event.getRawY()
                            initialHeight = keyboardContent.getHeight()
                            v.setPressed(true)
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val currentTouchY = event.getRawY()
                            val deltaY = initialTouchY - currentTouchY
                            val newHeight = (initialHeight + deltaY).toInt()
                            val minHeight =
                                v.getContext().getResources().getDisplayMetrics().heightPixels / 5
                            val maxHeight = v.getContext().getResources()
                                .getDisplayMetrics().heightPixels * 4 / 5
                            if (newHeight > minHeight && newHeight < maxHeight) {
                                val params = keyboardContent.getLayoutParams()
                                params.height = newHeight
                                keyboardContent.setLayoutParams(params)
                                syncResizeHandlePosition()
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            prefs.edit().putInt(KEY_HEIGHT, keyboardContent.getHeight()).apply()
                            v.setPressed(false)
                            return true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            v.setPressed(false)
                            return true
                        }
                    }
                    return false
                }
            })
        }
    }

    private fun updateTabStyle(btn: TextView?, active: Boolean) {
        if (btn == null) return
        btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#88FFFFFF"))
        btn.setBackgroundColor(if (active) Color.parseColor("#40FFFFFF") else Color.TRANSPARENT)
    }

    private fun setupTouchListeners(root: ViewGroup) {
        for (i in 0..<root.getChildCount()) {
            val child = root.getChildAt(i)
            val tag = child.getTag()
            if (tag is String) {
                val sTag = tag
                if (sTag.startsWith("k")) {
                    val detector = KeyboardGestureDetector(child, this)
                    child.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent? ->
                        detector.onTouchEvent(
                            v,
                            event
                        )
                    })
                }
            } else if (child is ViewGroup) {
                setupTouchListeners(child)
            }
        }
    }

    override fun onKeyPress(keyCode: Int) {
        var v: View? = null
        val tag = "k" + keyCode

        if (layoutMini != null && layoutMini.getVisibility() == View.VISIBLE) {
            v = layoutMini.findViewWithTag<View?>(tag)
        } else if (layoutNum != null && layoutNum.getVisibility() == View.VISIBLE) {
            v = layoutNum.findViewWithTag<View?>(tag)
        } else if (layoutNav != null && layoutNav.getVisibility() == View.VISIBLE) {
            v = layoutNav.findViewWithTag<View?>(tag)
        } else {
            if (layoutMain != null) {
                v = layoutMain.findViewWithTag<View?>(tag)
            }
        }

        if (v == null) {
            v = keyboardLayout.findViewWithTag<View?>(tag)
        }

        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("toggle")
            val currentState: Int = modifierStates.get(keyCode)!!
            if (currentState == MOD_NEUTRAL) {
                modifierStates.put(keyCode, MOD_SINGLE)
                physicallyHeldModifiers.add(keyCode)
                listener.sendKeyEvent(true, keyCode.toShort())
                updateModifierUI(keyCode, MOD_SINGLE)
            } else {
                handleModifierDown(v, keyCode)
            }
        } else {
            triggerHaptic("normal")
            listener.sendKeyEvent(true, keyCode.toShort())
            if (v != null && v.isShown()) {
                showPopup(v)
            }
        }
    }

    private fun triggerHaptic(type: String) {
        var duration = 10
        if (type == "toggle" || type == "heavy") duration = 20
        listener.rumbleSingleVibrator(
            1000.toShort(),
            1000.toShort(),
            duration
        )
    }

    private fun showPopup(v: View?) {
        if (v is TextView && keyPopup != null) {
            val text = v.getText().toString().trim { it <= ' ' }
            if (text.length == 1 || text.contains(" ")) {
                val display: String? =
                    text.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0]
                keyPopup.setText(display)

                val density = v.getContext().getResources().getDisplayMetrics().density
                val popupWidth = 50 * density
                val popupHeight = 64 * density

                val keyScreenLoc = IntArray(2)
                v.getLocationOnScreen(keyScreenLoc)

                val parentScreenLoc = IntArray(2)
                keyboardLayout.getLocationOnScreen(parentScreenLoc)

                val relativeX = (keyScreenLoc[0] - parentScreenLoc[0]).toFloat()
                val relativeY = (keyScreenLoc[1] - parentScreenLoc[1]).toFloat()

                keyPopup.setX(relativeX + (v.getWidth() / 2f) - (popupWidth / 2f))
                keyPopup.setY(relativeY - popupHeight)

                keyPopup.setVisibility(View.VISIBLE)
            }
        }
    }

    override fun onKeyRelease(keyCode: Int) {
        if (!modifierStates.containsKey(keyCode)) {
            listener.sendKeyEvent(false, keyCode.toShort())
            resetSingleModifiers()
            if (keyPopup != null) keyPopup.setVisibility(View.GONE)
        } else {
            physicallyHeldModifiers.remove(keyCode)
        }
    }

    override fun onModifierHoldRelease(keyCode: Int) {
        if (modifierStates.containsKey(keyCode)) {
            physicallyHeldModifiers.remove(keyCode)
            val currentState = modifierStates.getOrDefault(
                keyCode,
                com.limelight.binding.input.advance_setting.KeyboardUIController.Companion.MOD_NEUTRAL
            )!!
            if (currentState == MOD_SINGLE) {
                modifierStates.put(keyCode, MOD_NEUTRAL)
                listener.sendKeyEvent(false, keyCode.toShort())
                updateModifierUI(keyCode, MOD_NEUTRAL)
            }
        } else {
            listener.sendKeyEvent(false, keyCode.toShort())
            resetSingleModifiers()
            if (keyPopup != null) keyPopup.setVisibility(View.GONE)
        }
    }

    override fun onLongPress(keyCode: Int) {}

    override fun onDoubleTap(keyCode: Int) {
        if (modifierStates.containsKey(keyCode)) {
            triggerHaptic("heavy")
            modifierStates.put(keyCode, MOD_NEUTRAL)
            physicallyHeldModifiers.remove(keyCode)
            updateModifierUI(keyCode, MOD_NEUTRAL)
            listener.sendKeyEvent(false, keyCode.toShort())
        } else {
            onKeyPress(keyCode)
        }
    }

    private fun handleModifierDown(v: View?, keyCode: Int) {
        val currentState: Int = modifierStates.get(keyCode)!!
        val newState = (currentState + 1) % 3
        modifierStates.put(keyCode, newState)
        when (newState) {
            MOD_NEUTRAL -> listener.sendKeyEvent(
                false,
                keyCode.toShort()
            )

            MOD_SINGLE -> listener.sendKeyEvent(true, keyCode.toShort())
            MOD_LOCKED -> {}
        }
        updateModifierUI(keyCode, newState)
    }

    private fun resetSingleModifiers() {
        for (entry in modifierStates.entries) {
            if (entry.value == MOD_SINGLE) {
                val keyCode: Int = entry.key!!
                if (physicallyHeldModifiers.contains(keyCode)) continue
                modifierStates.put(keyCode, MOD_NEUTRAL)
                listener.sendKeyEvent(false, keyCode.toShort())
                updateModifierUI(keyCode, MOD_NEUTRAL)
            }
        }
    }

    private fun updateModifierUI(keyCode: Int, state: Int) {
        val backgroundResId: Int
        when (state) {
            MOD_SINGLE -> backgroundResId = R.drawable.keyboard_modifier_single_selector
            MOD_LOCKED -> backgroundResId = R.drawable.keyboard_modifier_locked_selector
            MOD_NEUTRAL -> backgroundResId = R.drawable.keyboard_modifier_refined_selector
            else -> backgroundResId = R.drawable.keyboard_modifier_refined_selector
        }

        val tag = "k" + keyCode

        updateKeyInContainer(layoutMain, tag, backgroundResId)

        updateKeyInContainer(panelAlpha, tag, backgroundResId)
        updateKeyInContainer(panelNumMini, tag, backgroundResId)
        updateKeyInContainer(panelPcMini, tag, backgroundResId)

        // 使用新加的容器去寻找并更新顶部那四个修饰键的状态
        updateKeyInContainer(miniModifierContainer, tag, backgroundResId)

        updateKeyInContainer(layoutNav, tag, backgroundResId)
        updateKeyInContainer(layoutNum, tag, backgroundResId)
    }

    private fun updateKeyInContainer(container: View?, tag: String?, resId: Int) {
        if (container != null) {
            val v = container.findViewWithTag<View?>(tag)
            if (v != null) {
                v.setBackgroundResource(resId)
            }
        }
    }

    fun toggle() {
        if (this.isVisible) hide()
        else show()
    }

    fun show() {
        keyboardLayout.setVisibility(View.VISIBLE)
        parentContainer.setVisibility(View.VISIBLE)
    }

    fun hide() {
        keyboardLayout.setVisibility(View.GONE)
        parentContainer.setVisibility(View.GONE)
    }

    val isVisible: Boolean
        get() = keyboardLayout.getVisibility() == View.VISIBLE

    companion object {
        private const val PREF_NAME = "keyboard_settings"
        private const val KEY_HEIGHT = "keyboard_height"
        private const val KEY_OPACITY = "keyboard_opacity"
        private const val KEY_X = "keyboard_x"
        private const val KEY_Y = "keyboard_y"
        private const val KEY_IS_MINI = "keyboard_is_mini"

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
        private const val KEY_SPACE = 62
    }
}