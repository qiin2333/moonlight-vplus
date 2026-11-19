//开关按键
package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
class DigitalSwitchButton(attributesMap: Map<String, Any>,
                          controller: ElementController,
                          pageDeviceController: PageDeviceController,
                          context: Context) : Element(attributesMap, controller, context) {
    /**
     * Listener interface to update registered observers.
     */
    interface DigitalSwitchButtonListener {
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

    private var superConfigDatabaseHelper: SuperConfigDatabaseHelper? = null
    private var pageDeviceController: PageDeviceController? = null
    private var digitalSwitchButton: DigitalSwitchButton? = null

    private var listener: DigitalSwitchButtonListener? = null
    private var valueSendHandler: ElementController.SendEventHandler? = null
    private var text: String?
    private var value: String?
    private var radius: Int
    private var thick: Int
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int

    // New member variables for text color and size
    private var normalTextColor: Int
    private var pressedTextColor: Int
    private var textSizePercent: Int

    private var digitalSwitchButtonPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null

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
        this.pageDeviceController = pageDeviceController
        this.digitalSwitchButton = this

        val displayMetrics = DisplayMetrics()
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
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

        text = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT) as String?
        radius = (attributesMap.get(COLUMN_INT_ELEMENT_RADIUS) as Long).toInt()
        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        value = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String?

        // Load new text properties with backward compatibility
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
        listener = object : DigitalSwitchButtonListener {
            override fun onClick() {
                valueSendHandler!!.sendDigitalSwitchEvent(true, false)
            }

            override fun onLongClick() {
            }

            override fun onRelease() {
                valueSendHandler!!.sendDigitalSwitchEvent(false, false)
            }
        }
    }

    override fun onElementDraw(canvas: Canvas) {
        // 获取元素尺寸
        val elementWidth = getElementWidth()
        val elementHeight = getElementHeight()

        // 1. 设置画笔属性
        // 根据百分比设置字体大小
        val textSize = getPercent(elementHeight.toFloat(), textSizePercent.toFloat())
        paintText.textSize = textSize
        // 设置字体颜色
        paintText.setColor(if (isPressed) pressedTextColor else normalTextColor)
        // 边框
        paintBorder.strokeWidth = thick.toFloat()
        paintBorder.setColor(if (isPressed) pressedColor else normalColor)
        // 背景颜色
        paintBackground.setColor(backgroundColor)

        // 2. 计算精确的居中坐标
        // 水平中心 X 坐标
        val centerX = elementWidth / 2f

        // 计算垂直居中的基线 Y 坐标
        val fontMetrics = paintText.fontMetrics
        val baselineY = elementHeight / 2f - (fontMetrics.top + fontMetrics.bottom) / 2f

        // 3. 开始绘制
        // 绘画范围
        rect.left = thick.toFloat() / 2
        rect.top = rect.left
        rect.right = elementWidth - rect.left
        rect.bottom = elementHeight - rect.top
        // 绘制背景
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBackground)
        // 绘制边框
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBorder)

        // 绘制文字 (使用计算出的精确坐标)
        canvas.drawText(text!!, centerX, baselineY, paintText)

        // 4. 绘制编辑模式的虚线框
        val mode = elementController.getMode()
        if (mode === ElementController.Mode.Edit || mode === ElementController.Mode.Select) {
            // 绘画范围
            rect.left = 2f
            rect.top = rect.left
            rect.right = width - 2f
            rect.bottom = height - 2f
            // 边框
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
        // get masked (not specific to a pointer) action
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                elementController.buttonVibrator()
                if (isPressed) {
                    isPressed = false
                    onReleaseCallback()
                } else {
                    isPressed = true
                    onClickCallback()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                return true
            }
            else -> {
            }
        }
        return true
    }

    override fun releaseLatchedInput() {
        if (isPressed) {
            isPressed = false
            valueSendHandler!!.sendDigitalSwitchEvent(false, true)
            elementController.getHandler().removeCallbacks(longClickRunnable)
        }
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
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
        // Save new text properties
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, normalTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, pressedTextColor)
        contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, textSizePercent)
        elementController.updateElement(elementId!!, contentValues)
    }

    override fun updatePage() {
        if (digitalSwitchButtonPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_digital_switch_button_text_a96b6)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (digitalSwitchButtonPage == null) {
            digitalSwitchButtonPage = LayoutInflater.from(context).inflate(R.layout.page_digital_switch_button_clean, null) as SuperPageLayout
            centralXNumberSeekbar = digitalSwitchButtonPage!!.findViewById(R.id.page_digital_switch_button_central_x)
            centralYNumberSeekbar = digitalSwitchButtonPage!!.findViewById(R.id.page_digital_switch_button_central_y)
        }

        val digitalSwitchButtonPage = digitalSwitchButtonPage!!
        val widthNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_width)
        val heightNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_height)
        val radiusNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_radius)
        val textElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_text)
        val valueTextView = digitalSwitchButtonPage.findViewById<TextView>(R.id.page_digital_switch_button_value)
        val thickNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_thick)
        val layerNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_layer)
        val normalColorElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_normal_color)
        val pressedColorElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_pressed_color)
        val backgroundColorElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_background_color)
        // Find new views for text properties (assuming these IDs exist in the XML layout)
        val textSizeNumberSeekbar = digitalSwitchButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_switch_button_text_size)
        val normalTextColorElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_normal_text_color)
        val pressedTextColorElementEditText = digitalSwitchButtonPage.findViewById<ElementEditText>(R.id.page_digital_switch_button_pressed_text_color)
        val copyButton = digitalSwitchButtonPage.findViewById<Button>(R.id.page_digital_switch_button_copy)
        val deleteButton = digitalSwitchButtonPage.findViewById<Button>(R.id.page_digital_switch_button_delete)

        textElementEditText.setTextWithNoTextChangedCallBack(text!!)
        textElementEditText.setOnTextChangedListener(object : ElementEditText.OnTextChangedListener {
            override fun textChanged(text: String) {
                setElementText(text)
                save()
            }
        })

        valueTextView.text = pageDeviceController!!.getKeyNameByValue(value)
        valueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    val text = key.text
                    // page页设置值文本
                    (v as TextView).text = text
                    // element text 设置文本
                    textElementEditText.setText(text)
                    setElementValue(key.tag.toString())
                    save()
                }
                pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
            }
        })

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

        // Refactored setup for all color pickers
        CrownColorPickerBinder.bind(this, normalColorElementEditText, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColorElementEditText, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColorElementEditText, { this.backgroundColor }, this::setElementBackgroundColor)
        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, { this.normalTextColor }, this::setElementNormalTextColor)
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, { this.pressedTextColor }, this::setElementPressedTextColor)

        copyButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON)
                contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
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
        })

        deleteButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                elementController.toggleInfoPage(digitalSwitchButtonPage)
                elementController.deleteElement(digitalSwitchButton!!)
            }
        })

        return digitalSwitchButtonPage
    }

    protected fun setElementText(text: String?) {
        this.text = text
        invalidate()
    }

    protected fun setElementValue(value: String?) {
        this.value = value
        valueSendHandler = elementController.getSendEventHandler(value)
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
        invalidate()
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidate()
    }

    // New setter methods for text properties
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

    companion object {
        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "A")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k29")
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
            // Add new text properties with good defaults for new buttons
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF.toInt()) // White
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC.toInt()) // Light Grey for pressed state
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25)
            return contentValues
        }
    }
}
