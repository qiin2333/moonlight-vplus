//可移动按键
package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.TouchController
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

import java.lang.reflect.Type
import java.util.HashMap

/**
 * This is a digital button on screen element. It can function as a simple button,
 * a joystick-like mouse mover, or a mini-trackpad with tap and drag capabilities.
 */
class DigitalMovableButton(attributesMap: Map<String, Any>,
                           controller: ElementController,
                           touchController: TouchController,
                           pageDeviceController: PageDeviceController,
                           context: Context) : Element(attributesMap, controller, context) {
    companion object {
        // --- 定义用于存储 JSON 属性的新列名 ---
        const val COLUMN_STRING_EXTRA_ATTRIBUTES = "extra_attributes"

        // --- 触控板逻辑所需的常量 ---
        private const val DRAG_TIME_THRESHOLD = 300
        private const val TAP_MOVEMENT_THRESHOLD = 20

        // --- 定义鼠标左键的常量 ---
        private const val LEFT_MOUSE_VALUE = "m1"

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "A")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k29")
            contentValues.put(COLUMN_INT_ELEMENT_MODE, 0) // 默认摇杆模式关闭
            contentValues.put(COLUMN_INT_ELEMENT_SENSE, 100)
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 100)
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100)
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 0)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, 5)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25)

            // --- 为 extra_attributes 设置默认的 JSON 值 ---
            val extraAttrs = HashMap<String, Any>()
            extraAttrs["isTrackpadMode"] = false // 默认触控板模式关闭
            contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, Gson().toJson(extraAttrs))

            return contentValues
        }
    }

    /**
     * Listener interface to update registered observers.
     */
    interface DigitalMovableButtonListener {
        /**
         * onClick event will be fired on button click.
         */
        fun onClick()

        /**
         * onLongClick event will be fired on button long click.
         */
        fun onLongClick()

        /**
         * onRelease event will be fired on button unpress.
         */
        fun onRelease()
    }

    private var touchController: TouchController? = null
    private var pageDeviceController: PageDeviceController? = null
    private var digitalMovableButton: DigitalMovableButton? = null

    private var listener: DigitalMovableButtonListener? = null
    private var valueSendHandler: ElementController.SendEventHandler? = null
    private var text: String?
    private var value: String?
    private var radius: Int
    private var thick: Int
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int
    // New member variables
    private var normalTextColor: Int
    private var pressedTextColor: Int
    private var textSizePercent: Int
    private val game: Game

    private var enableTouch = 0 // 0 = 按钮, 1 = 摇杆。
    private var isTrackpadMode = false

    // --- 用于在触控板模式下临时存储原始键值 ---
    private var valueBeforeTrackpad: String? = null

    private var sense: Int

    private var digitalMovableButtonPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null

    private var lastX = 0f
    private var lastY = 0f

    // --- 用于摇杆模式 (Joystick Mode) ---
    private var isFirstTouch = true
    private var FirstTouchX = 0f
    private var FirstTouchY = 0f

    // --- 用于触控板模式 (Trackpad Mode) ---
    private val handler = Handler(Looper.getMainLooper())
    private var originalTouchX = 0f
    private var originalTouchY = 0f
    private var confirmedMove = false
    private var confirmedDrag = false
    private val dragTimerRunnable: Runnable

    private var timerLongClickTimeout = 3000L
    private val longClickRunnable: Runnable = object : Runnable {
        override fun run() {
            onLongClickCallback()
        }
    }
    private val paintBorder = Paint()
    private val paintBackground = Paint()
    private val paintText = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()

    init {
        this.touchController = touchController
        this.pageDeviceController = pageDeviceController
        this.digitalMovableButton = this
        this.game = context as Game

        val displayMetrics = DisplayMetrics()
        game.windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        centralXMax = displayMetrics.widthPixels
        centralXMin = 0
        centralYMax = displayMetrics.heightPixels
        centralYMin = 0
        widthMax = displayMetrics.widthPixels / 2
        widthMin = 50
        heightMax = displayMetrics.heightPixels / 2
        heightMin = 50

        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        paintText.textAlign = Paint.Align.CENTER
        paintBorder.style = Paint.Style.STROKE
        paintBackground.style = Paint.Style.FILL

        // Standard properties
        text = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT) as String?
        radius = (attributesMap.get(COLUMN_INT_ELEMENT_RADIUS) as Long).toInt()
        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        value = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String?
        sense = (attributesMap.get(COLUMN_INT_ELEMENT_SENSE) as Long).toInt()

        val modeObj = attributesMap.get(COLUMN_INT_ELEMENT_MODE)
        this.enableTouch = if (modeObj != null) (modeObj as Long).toInt() else 0

        // --- 读取并解析新的 extra_attributes JSON 列 ---
        val extraAttrObj = attributesMap.get(COLUMN_STRING_EXTRA_ATTRIBUTES)
        if (extraAttrObj is String) {
            val json = extraAttrObj as String
            // 使用 try-catch 块防止因 JSON 格式错误导致崩溃
            try {
                val gson = Gson()
                // 定义要解析的目标类型
                val type: Type = object : TypeToken<Map<String, Any>>() {}.type
                val extraAttrs: Map<String, Any>? = gson.fromJson(json, type)

                if (extraAttrs != null && extraAttrs.containsKey("isTrackpadMode")) {
                    // Gson 可能会将 JSON 的布尔值解析为 Boolean 对象
                    val trackpadValue = extraAttrs["isTrackpadMode"]
                    if (trackpadValue is Boolean) {
                        this.isTrackpadMode = trackpadValue
                    }
                }
            } catch (e: Exception) {
                // 如果 JSON 格式错误，打印错误日志并使用默认值继续运行
                e.printStackTrace()
            }
        }

        // 读取文本颜色等属性
        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR)) {
            normalTextColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR) as Long).toInt()
        } else {
            // Default to old behavior: use border color
            normalTextColor = normalColor
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR)) {
            pressedTextColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR) as Long).toInt()
        } else {
            // Default to old behavior: use border color
            pressedTextColor = pressedColor
        }

        if (attributesMap.containsKey(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT)) {
            textSizePercent = (attributesMap.get(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT) as Long).toInt()
        } else {
            // Default based on original hardcoded logic
            textSizePercent = 25
        }

        valueSendHandler = controller.getSendEventHandler(value)
        listener = object : DigitalMovableButtonListener {
            override fun onClick() {
                valueSendHandler!!.sendEvent(true)
            }

            override fun onLongClick() {
            }

            override fun onRelease() {
                valueSendHandler!!.sendEvent(false)
            }
        }

        dragTimerRunnable = Runnable {
            if (confirmedMove) return@Runnable
            confirmedDrag = true
            listener!!.onClick()
            elementController.buttonVibrator()
            isPressed = true
            invalidate()
        }
    }

    override fun onElementDraw(canvas: Canvas) {
        // Get element dimensions
        val elementWidth = getElementWidth()
        val elementHeight = getElementHeight()

        // Set text size based on percentage of height
        val textSize = getPercent(elementHeight.toFloat(), textSizePercent.toFloat())
        paintText.textSize = textSize
        // Set text color based on press state using new properties
        paintText.setColor(if (isPressed) pressedTextColor else normalTextColor)
        // Border
        paintBorder.strokeWidth = thick.toFloat()
        paintBorder.setColor(if (isPressed) pressedColor else normalColor)
        // Background color
        paintBackground.setColor(backgroundColor)

        val centerX = elementWidth / 2f
        // Calculate the baseline Y for vertical centering
        val fontMetrics = paintText.fontMetrics
        val baselineY = elementHeight / 2f - (fontMetrics.top + fontMetrics.bottom) / 2f

        // 3. Start drawing
        // Drawing bounds
        rect.left = thick.toFloat() / 2
        rect.top = rect.left
        rect.right = getElementWidth() - rect.left
        rect.bottom = height - rect.top
        // Draw background
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBackground)
        // Draw border
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBorder)
        // Draw text using the calculated precise coordinates
        canvas.drawText(text!!, centerX, baselineY, paintText)

        val mode = elementController.getMode()
        if (mode === ElementController.Mode.Edit || mode === ElementController.Mode.Select) {
            // Drawing bounds
            rect.left = 2f
            rect.top = rect.left
            rect.right = width - 2f
            rect.bottom = height - 2f
            // Border
            paintEdit.setColor(editColor)
            canvas.drawRect(rect, paintEdit)
        }
    }

    private fun onClickCallback() {
        // notify listenersbuttonListener.onClick();
        println("onClickCallback")
        listener!!.onClick()
        elementController.getHandler().removeCallbacks(longClickRunnable)
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        // notify listeners
        listener!!.onLongClick()
    }

    private fun onReleaseCallback() {
        // notify listeners
        println("onReleaseCallback")
        listener!!.onRelease()

        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        if (isTrackpadMode) {
            return handleTrackpadTouchEvent(event)
        } else if (enableTouch == 1) {
            return handleJoystickTouchEvent(event)
        } else { // enableTouch == 0
            return handleButtonTouchEvent(event)
        }
    }

    private fun handleTrackpadTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                originalTouchX = event.x
                originalTouchY = event.y
                confirmedMove = false
                confirmedDrag = false
                handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD.toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastX
                val deltaY = event.y - lastY
                if (!confirmedMove && !confirmedDrag) {
                    val moveDistance = Math.hypot((event.x - originalTouchX).toDouble(), (event.y - originalTouchY).toDouble()).toFloat()
                    if (moveDistance > TAP_MOVEMENT_THRESHOLD) {
                        confirmedMove = true
                        handler.removeCallbacks(dragTimerRunnable)
                    }
                }
                if (confirmedMove || confirmedDrag) {
                    touchController!!.mouseMove(deltaX, deltaY, 0.01 * sense)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(dragTimerRunnable)
                if (confirmedDrag) {
                    listener!!.onRelease()
                } else if (!confirmedMove) {
                    elementController.buttonVibrator()
                    listener!!.onClick()
                    handler.postDelayed({ listener!!.onRelease() }, 50)
                }
                isPressed = false
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleButtonTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                elementController.buttonVibrator()
                lastX = event.x
                lastY = event.y
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastX
                val deltaY = event.y - lastY
                touchController!!.mouseMove(deltaX, deltaY, 0.01 * sense)
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleJoystickTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                elementController.buttonVibrator()
                isPressed = true
                onClickCallback()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                invalidate()
            }
        }
        if (isFirstTouch) {
            isFirstTouch = false
            FirstTouchX = event.x
            FirstTouchY = event.y
        }
        val touchXTemp: Float
        val touchYTemp: Float
        touchXTemp = (game.streamView.width / 2 + (event.x - FirstTouchX) * sense * 0.01).toFloat()
        touchYTemp = (game.streamView.height / 2 + (event.y - FirstTouchY) * sense * 0.01).toFloat()
        val EventTemp = MotionEvent.obtain(event.downTime, event.eventTime, action, touchXTemp, touchYTemp, event.pressure, event.size, event.metaState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags)
        if (touchXTemp < 0 || touchXTemp > game.streamView.width || touchYTemp < 0 || touchYTemp > game.streamView.height) {
            FirstTouchX = event.x
            FirstTouchY = event.y
            EventTemp.action = MotionEvent.ACTION_CANCEL
        }
        try {
            game.getHandleMotionEvent(game.streamView, EventTemp)
        } catch (e: NullPointerException) {
            println("NullPointerException")
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            FirstTouchX = 0f
            FirstTouchY = 0f
            isFirstTouch = true
        }
        return true
    }

    override fun save() {
        val contentValues = ContentValues()

        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
        contentValues.put(COLUMN_INT_ELEMENT_MODE, enableTouch)
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, sense)
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius)
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent)

        // --- 创建并保存 extra_attributes 的 JSON 字符串 ---
        val extraAttrs = HashMap<String, Any>()
        extraAttrs["isTrackpadMode"] = isTrackpadMode

        val gson = Gson()
        val json = gson.toJson(extraAttrs)

        contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, json)

        elementController.updateElement(elementId!!, contentValues)
    }

    override fun updatePage() {
        if (digitalMovableButtonPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_digital_movable_button_text_e279e)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (digitalMovableButtonPage == null) {
            digitalMovableButtonPage = LayoutInflater.from(context).inflate(R.layout.page_digital_movable_button_clean, null) as SuperPageLayout
            centralXNumberSeekbar = digitalMovableButtonPage!!.findViewById(R.id.page_digital_movable_button_central_x)
            centralYNumberSeekbar = digitalMovableButtonPage!!.findViewById(R.id.page_digital_movable_button_central_y)
        }

        val digitalMovableButtonPage = digitalMovableButtonPage!!
        // --- 获取所有需要的控件 ---
        val joystickModeSwitch = digitalMovableButtonPage.findViewById<SwitchCompat>(R.id.page_digital_movable_button_enable_touch)
        val joystickModeContainer = digitalMovableButtonPage.findViewById<View>(R.id.page_digital_movable_button_joystick_mode_container)
        val trackpadModeSwitch = digitalMovableButtonPage.findViewById<SwitchCompat>(R.id.page_digital_movable_button_trackpad_mode)
        val trackpadModeContainer = digitalMovableButtonPage.findViewById<View>(R.id.page_digital_movable_button_trackpad_mode_container)
        val valueTextView = digitalMovableButtonPage.findViewById<TextView>(R.id.page_digital_movable_button_value)

        val updateValueViewState = Runnable {
            if (isTrackpadMode) {
                valueTextView.text = pageDeviceController!!.getKeyNameByValue(LEFT_MOUSE_VALUE)
                valueTextView.isEnabled = false
                valueTextView.alpha = 0.5f // 设置为半透明，视觉上更明确
            } else {
                valueTextView.text = pageDeviceController!!.getKeyNameByValue(value)
                valueTextView.isEnabled = true
                valueTextView.alpha = 1.0f
            }
        }

        // 根据初始状态设置控件的可见性
        if (isTrackpadMode) {
            joystickModeContainer.visibility = View.GONE
        } else if (enableTouch == 1) {
            trackpadModeContainer.visibility = View.GONE
        }

        // 设置开关的初始选中状态
        joystickModeSwitch.isChecked = enableTouch == 1
        trackpadModeSwitch.isChecked = isTrackpadMode

        // 调用辅助方法，初始化键值UI
        updateValueViewState.run()

        // --- 为开关设置新的监听器，包含所有联动逻辑 ---
        joystickModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            enableTouch = if (isChecked) 1 else 0
            trackpadModeContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
            if (isChecked && isTrackpadMode) {
                isTrackpadMode = false
                trackpadModeSwitch.isChecked = false
                // 因为触控板模式被关闭了，所以需要更新键值UI
                updateValueViewState.run()
            }
            save()
        }

        trackpadModeSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            isTrackpadMode = isChecked
            joystickModeContainer.visibility = if (isChecked) View.GONE else View.VISIBLE

            if (isChecked) {
                // --- 开启触控板模式 ---
                // 1. 保存当前键值 (如果还没保存过)
                if (valueBeforeTrackpad == null) {
                    valueBeforeTrackpad = value
                }
                // 2. 强制设置键值为鼠标左键
                setElementValue(LEFT_MOUSE_VALUE)
            } else {
                // --- 关闭触控板模式 ---
                // 1. 恢复之前的键值 (如果存在)
                if (valueBeforeTrackpad != null) {
                    setElementValue(valueBeforeTrackpad!!)
                    valueBeforeTrackpad = null // 清空临时存储
                }
            }

            // 确保与摇杆模式互斥
            if (isChecked && enableTouch == 1) {
                enableTouch = 0
                joystickModeSwitch.isChecked = false
            }

            // 更新键值UI状态
            updateValueViewState.run()
            save()
        }

        val widthNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_width)
        val heightNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_height)
        val radiusNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_radius)
        val textElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_text)
        val senseNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_sense)
        val thickNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_thick)
        val layerNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_layer)
        val normalColorElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_normal_color)
        val pressedColorElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_pressed_color)
        val backgroundColorElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_background_color)
        val copyButton = digitalMovableButtonPage.findViewById<Button>(R.id.page_digital_movable_button_copy)
        val deleteButton = digitalMovableButtonPage.findViewById<Button>(R.id.page_digital_movable_button_delete)
        val textSizeNumberSeekbar = digitalMovableButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_movable_button_text_size)
        val normalTextColorElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_normal_text_color)
        val pressedTextColorElementEditText = digitalMovableButtonPage.findViewById<ElementEditText>(R.id.page_digital_movable_button_pressed_text_color)

        textElementEditText.setTextWithNoTextChangedCallBack(text!!)
        textElementEditText.setOnTextChangedListener { text ->
            setElementText(text)
            save()
        }

        valueTextView.setOnClickListener { v ->
            val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                val text = key.text
                (v as TextView).text = text
                textElementEditText.setText(text)
                setElementValue(key.tag.toString())
                // 当用户手动选择一个键值后，清除临时的“恢复值”
                valueBeforeTrackpad = null
                save()
            }
            pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
        }

        centralXNumberSeekbar!!.setProgressMin(centralXMin)
        centralXNumberSeekbar!!.setProgressMax(centralXMax)
        centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
        centralXNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementCentralX(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        centralYNumberSeekbar!!.setProgressMin(centralYMin)
        centralYNumberSeekbar!!.setProgressMax(centralYMax)
        centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        centralYNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementCentralY(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        widthNumberSeekbar.setProgressMax(widthMax)
        widthNumberSeekbar.setProgressMin(widthMin)
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth())
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementWidth(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
                save()
            }
        })

        heightNumberSeekbar.setProgressMax(heightMax)
        heightNumberSeekbar.setProgressMin(heightMin)
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight())
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementHeight(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
                save()
            }
        })

        senseNumberSeekbar.setValueWithNoCallBack(sense)
        senseNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementSense(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
        radiusNumberSeekbar.setValueWithNoCallBack(radius)
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementRadius(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        thickNumberSeekbar.setValueWithNoCallBack(thick)
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementThick(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        layerNumberSeekbar.setValueWithNoCallBack(layer)
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setElementLayer(layerNumberSeekbar.value)
                save()
            }
        })

        // Setup for new text size seekbar
        textSizeNumberSeekbar.setProgressMin(10) // 10%
        textSizeNumberSeekbar.setProgressMax(150) // 150%
        textSizeNumberSeekbar.setValueWithNoCallBack(textSizePercent)
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementTextSizePercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        // Setup for all color pickers
        CrownColorPickerBinder.bind(this, normalColorElementEditText, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColorElementEditText, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColorElementEditText, { this.backgroundColor }, this::setElementBackgroundColor)
        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, { this.normalTextColor }, this::setElementNormalTextColor)
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, { this.pressedTextColor }, this::setElementPressedTextColor)

        copyButton.setOnClickListener {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
            contentValues.put(COLUMN_INT_ELEMENT_MODE, enableTouch)
            val copyExtraAttrs = HashMap<String, Any>()
            copyExtraAttrs["isTrackpadMode"] = isTrackpadMode
            contentValues.put(COLUMN_STRING_EXTRA_ATTRIBUTES, Gson().toJson(copyExtraAttrs))
            contentValues.put(COLUMN_INT_ELEMENT_SENSE, sense)
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin))
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
            // Add new properties for copy
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor)
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor)
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent)
            elementController.addElement(contentValues)
        }

        deleteButton.setOnClickListener {
            elementController.toggleInfoPage(digitalMovableButtonPage)
            elementController.deleteElement(digitalMovableButton!!)
        }

        return digitalMovableButtonPage
    }

    protected fun setElementText(text: String?) {
        this.text = text
        invalidate()
    }

    protected fun setElementValue(value: String?) {
        this.value = value
        valueSendHandler = elementController.getSendEventHandler(value)
    }

    protected fun setElementSense(sense: Int) {
        this.sense = sense
    }

    fun setElementRadius(radius: Int) {
        this.radius = radius
        invalidate()
    }

    fun setElementThick(thick: Int) {
        this.thick = thick
        invalidate()
    }

    fun setElementNormalColor(normalColor: Int) {
        this.normalColor = normalColor
        invalidate()
    }

    fun setElementPressedColor(pressedColor: Int) {
        this.pressedColor = pressedColor
        invalidate() // Added invalidate() for immediate visual feedback
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidate()
    }

    // New setters for text properties
    fun setElementNormalTextColor(normalTextColor: Int) {
        this.normalTextColor = normalTextColor
        invalidate()
    }

    fun setElementPressedTextColor(pressedTextColor: Int) {
        this.pressedTextColor = pressedTextColor
        invalidate()
    }

    fun setElementTextSizePercent(textSizePercent: Int) {
        this.textSizePercent = textSizePercent
        invalidate()
    }
}
