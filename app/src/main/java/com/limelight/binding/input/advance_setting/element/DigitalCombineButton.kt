//组合键
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
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
class DigitalCombineButton(attributesMap: Map<String, Any>,
                           controller: ElementController,
                           pageDeviceController: PageDeviceController,
                           context: Context) : Element(attributesMap, controller, context) {
    companion object {
        private const val COLUMN_STRING_ELEMENT_VALUE_1 = Element.COLUMN_STRING_ELEMENT_VALUE
        private const val COLUMN_STRING_ELEMENT_VALUE_2 = Element.COLUMN_STRING_ELEMENT_UP_VALUE
        private const val COLUMN_STRING_ELEMENT_VALUE_3 = Element.COLUMN_STRING_ELEMENT_DOWN_VALUE
        private const val COLUMN_STRING_ELEMENT_VALUE_4 = Element.COLUMN_STRING_ELEMENT_LEFT_VALUE
        private const val COLUMN_STRING_ELEMENT_VALUE_5 = Element.COLUMN_STRING_ELEMENT_RIGHT_VALUE

        @JvmStatic
        fun getInitialInfo(context: Context): ContentValues {
            val values = getInitialInfo()
            values.put(COLUMN_STRING_ELEMENT_TEXT, context.getString(R.string.crown_combo_default))
            return values
        }

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "组合键")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, "k29")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, "null")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, "null")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, "null")
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, "null")
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
            // Add new properties with good defaults
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, 0xFFFFFFFF.toInt()) // White
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, 0xFFCCCCCC.toInt()) // Light Grey for pressed state
            contentValues.put(COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25)
            return contentValues
        }
    }

    /**
     * Listener interface to update registered observers.
     */
    interface DigitalCombineButtonListener {
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

    private var pageDeviceController: PageDeviceController? = null
    private var digitalCombineButton: DigitalCombineButton? = null

    private var listener: DigitalCombineButtonListener? = null
    private var value1SendHandler: ElementController.SendEventHandler? = null
    private var value2SendHandler: ElementController.SendEventHandler? = null
    private var value3SendHandler: ElementController.SendEventHandler? = null
    private var value4SendHandler: ElementController.SendEventHandler? = null
    private var value5SendHandler: ElementController.SendEventHandler? = null
    private var text: String?
    private var value1: String?
    private var value2: String?
    private var value3: String?
    private var value4: String?
    private var value5: String?
    private var radius: Int
    private var thick: Int
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int
    // New member variables
    private var normalTextColor: Int
    private var pressedTextColor: Int
    private var textSizePercent: Int

    private var digitalCombineButtonPage: SuperPageLayout? = null
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
        this.digitalCombineButton = this

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
        value1 = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_1) as String?
        value2 = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_2) as String?
        value3 = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_3) as String?
        value4 = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_4) as String?
        value5 = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE_5) as String?

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

        value1SendHandler = controller.getSendEventHandler(value1)
        value2SendHandler = controller.getSendEventHandler(value2)
        value3SendHandler = controller.getSendEventHandler(value3)
        value4SendHandler = controller.getSendEventHandler(value4)
        value5SendHandler = controller.getSendEventHandler(value5)
        listener = object : DigitalCombineButtonListener {
            override fun onClick() {
                value1SendHandler!!.sendEvent(true)
                value2SendHandler!!.sendEvent(true)
                value3SendHandler!!.sendEvent(true)
                value4SendHandler!!.sendEvent(true)
                value5SendHandler!!.sendEvent(true)
            }

            override fun onLongClick() {
            }

            override fun onRelease() {
                // Release combinations in reverse order so modifiers remain held
                // until every non-modifier key has been released.
                value5SendHandler!!.sendEvent(false)
                value4SendHandler!!.sendEvent(false)
                value3SendHandler!!.sendEvent(false)
                value2SendHandler!!.sendEvent(false)
                value1SendHandler!!.sendEvent(false)
            }
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
        // get masked (not specific to a pointer) action
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                elementController.buttonVibrator()
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                invalidate()
                return true
            }
            else -> {
            }
        }
        return true
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, value1)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, value2)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, value3)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, value4)
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, value5)
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius)
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
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
        if (digitalCombineButtonPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_digital_combine_button_text_3bb27)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (digitalCombineButtonPage == null) {
            digitalCombineButtonPage = LayoutInflater.from(context).inflate(R.layout.page_digital_combine_button_clean, null) as SuperPageLayout
            centralXNumberSeekbar = digitalCombineButtonPage!!.findViewById(R.id.page_digital_combine_button_central_x)
            centralYNumberSeekbar = digitalCombineButtonPage!!.findViewById(R.id.page_digital_combine_button_central_y)
        }

        val digitalCombineButtonPage = digitalCombineButtonPage!!
        val widthNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_width)
        val heightNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_height)
        val radiusNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_radius)
        val textElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_text)
        val value1TextView = digitalCombineButtonPage.findViewById<TextView>(R.id.page_digital_combine_button_value_1)
        val value2TextView = digitalCombineButtonPage.findViewById<TextView>(R.id.page_digital_combine_button_value_2)
        val value3TextView = digitalCombineButtonPage.findViewById<TextView>(R.id.page_digital_combine_button_value_3)
        val value4TextView = digitalCombineButtonPage.findViewById<TextView>(R.id.page_digital_combine_button_value_4)
        val value5TextView = digitalCombineButtonPage.findViewById<TextView>(R.id.page_digital_combine_button_value_5)
        val thickNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_thick)
        val layerNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_layer)
        val normalColorElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_normal_color)
        val pressedColorElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_pressed_color)
        val backgroundColorElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_background_color)
        val copyButton = digitalCombineButtonPage.findViewById<Button>(R.id.page_digital_combine_button_copy)
        val deleteButton = digitalCombineButtonPage.findViewById<Button>(R.id.page_digital_combine_button_delete)

        // Find new views for text properties (assuming these IDs exist in the XML layout)
        val textSizeNumberSeekbar = digitalCombineButtonPage.findViewById<NumberSeekbar>(R.id.page_digital_combine_button_text_size)
        val normalTextColorElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_normal_text_color)
        val pressedTextColorElementEditText = digitalCombineButtonPage.findViewById<ElementEditText>(R.id.page_digital_combine_button_pressed_text_color)

        textElementEditText.setTextWithNoTextChangedCallBack(text!!)
        textElementEditText.setOnTextChangedListener { text ->
            setElementText(text)
            save()
        }

        setupValueTextView(value1TextView, value1, this::setElementValue1)
        setupValueTextView(value2TextView, value2, this::setElementValue2)
        setupValueTextView(value3TextView, value3, this::setElementValue3)
        setupValueTextView(value4TextView, value4, this::setElementValue4)
        setupValueTextView(value5TextView, value5, this::setElementValue5)

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

        copyButton.setOnClickListener {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON)
            contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_1, value1)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_2, value2)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_3, value3)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_4, value4)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE_5, value5)
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
            elementController.toggleInfoPage(digitalCombineButtonPage)
            elementController.deleteElement(digitalCombineButton!!)
        }

        return digitalCombineButtonPage
    }

    private fun interface StringConsumer {
        fun accept(value: String?)
    }

    private fun setupValueTextView(textView: TextView, initialValue: String?, valueSetter: StringConsumer) {
        textView.text = pageDeviceController!!.getKeyNameByValue(initialValue)
        textView.setOnClickListener { v ->
            val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                (v as TextView).text = key.text
                valueSetter.accept(key.tag.toString())
                save()
            }
            pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
        }
    }

    protected fun setElementText(text: String?) {
        this.text = text
        invalidate()
    }

    protected fun setElementValue1(value1: String?) {
        this.value1 = value1
        value1SendHandler = elementController.getSendEventHandler(value1)
    }

    protected fun setElementValue2(value2: String?) {
        this.value2 = value2
        value2SendHandler = elementController.getSendEventHandler(value2)
    }

    protected fun setElementValue3(value3: String?) {
        this.value3 = value3
        value3SendHandler = elementController.getSendEventHandler(value3)
    }

    protected fun setElementValue4(value4: String?) {
        this.value4 = value4
        value4SendHandler = elementController.getSendEventHandler(value4)
    }

    protected fun setElementValue5(value5: String?) {
        this.value5 = value5
        value5SendHandler = elementController.getSendEventHandler(value5)
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
