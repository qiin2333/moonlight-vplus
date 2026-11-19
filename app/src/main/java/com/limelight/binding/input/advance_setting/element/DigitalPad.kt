//十字键
package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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

class DigitalPad(attributesMap: Map<String, Any>,
                 controller: ElementController,
                 pageDeviceController: PageDeviceController,
                 context: Context) : Element(attributesMap, controller, context) {
    companion object {
        const val DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0
        const val DIGITAL_PAD_DIRECTION_LEFT = 1
        const val DIGITAL_PAD_DIRECTION_UP = 2
        const val DIGITAL_PAD_DIRECTION_RIGHT = 4
        const val DIGITAL_PAD_DIRECTION_DOWN = 8
        private const val DPAD_MARGIN = 5

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_PAD)
            contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, "k51")
            contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, "k47")
            contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, "k29")
            contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, "k32")
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 300)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 300)
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, 5)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF)
            return contentValues
        }
    }

    var direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
    private var listener: DigitalPadListener? = null

    private var superConfigDatabaseHelper: SuperConfigDatabaseHelper? = null
    private var pageDeviceController: PageDeviceController? = null
    private var digitalPad: DigitalPad? = null

    private var upValueSenderHandler: ElementController.SendEventHandler? = null
    private var downValueSenderHandler: ElementController.SendEventHandler? = null
    private var leftValueSenderHandler: ElementController.SendEventHandler? = null
    private var rightValueSenderHandler: ElementController.SendEventHandler? = null
    private var upValue: String?
    private var downValue: String?
    private var leftValue: String?
    private var rightValue: String?
    private var thick: Int
    private var normalColor: Int
    private var pressedColor: Int
    private var backgroundColor: Int

    private var digitalPadPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null

    //加上这个防止一些无用的按键多次触发，发送大量数据，导致卡顿
    private var lastDirection = 0
    private val paintBorder = Paint()
    private val paintBackground = Paint()
    private val pathBackground = Path()
    private val paintText = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()

    init {
        this.pageDeviceController = pageDeviceController
        this.digitalPad = this

        val displayMetrics = DisplayMetrics()
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        centralXMax = displayMetrics.widthPixels
        centralXMin = 0
        centralYMax = displayMetrics.heightPixels
        centralYMin = 0
        widthMax = displayMetrics.widthPixels / 2
        widthMin = 150
        heightMax = displayMetrics.heightPixels / 2
        heightMin = 150

        paintText.textAlign = Paint.Align.CENTER
        paintBorder.style = Paint.Style.STROKE
        paintBackground.style = Paint.Style.FILL
        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)

        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        upValue = attributesMap.get(COLUMN_STRING_ELEMENT_UP_VALUE) as String?
        downValue = attributesMap.get(COLUMN_STRING_ELEMENT_DOWN_VALUE) as String?
        leftValue = attributesMap.get(COLUMN_STRING_ELEMENT_LEFT_VALUE) as String?
        rightValue = attributesMap.get(COLUMN_STRING_ELEMENT_RIGHT_VALUE) as String?

        upValueSenderHandler = controller.getSendEventHandler(upValue)
        downValueSenderHandler = controller.getSendEventHandler(downValue)
        leftValueSenderHandler = controller.getSendEventHandler(leftValue)
        rightValueSenderHandler = controller.getSendEventHandler(rightValue)
        listener = object : DigitalPadListener {
            override fun onDirectionChange(direction: Int) {
                val directionChange = lastDirection xor direction
                if ((directionChange and DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    leftValueSenderHandler!!.sendEvent((direction and DIGITAL_PAD_DIRECTION_LEFT) != 0)
                }
                if ((directionChange and DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    rightValueSenderHandler!!.sendEvent((direction and DIGITAL_PAD_DIRECTION_RIGHT) != 0)
                }
                if ((directionChange and DIGITAL_PAD_DIRECTION_UP) != 0) {
                    upValueSenderHandler!!.sendEvent((direction and DIGITAL_PAD_DIRECTION_UP) != 0)
                }
                if ((directionChange and DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    downValueSenderHandler!!.sendEvent((direction and DIGITAL_PAD_DIRECTION_DOWN) != 0)
                }
                lastDirection = direction
            }
        }
    }

    override fun onElementDraw(canvas: Canvas) {
        paintBorder.strokeWidth = thick.toFloat()
        val correctedBorderPosition = thick + DPAD_MARGIN

        paintBackground.style = Paint.Style.FILL
        paintBackground.strokeWidth = 10f
        paintBackground.setColor(backgroundColor)
        pathBackground.reset()
        pathBackground.moveTo(getPercent(width.toFloat(), 33f), correctedBorderPosition.toFloat())
        pathBackground.lineTo(getPercent(width.toFloat(), 66f), correctedBorderPosition.toFloat())
        pathBackground.lineTo((width - correctedBorderPosition).toFloat(), getPercent(height.toFloat(), 33f))
        pathBackground.lineTo((width - correctedBorderPosition).toFloat(), getPercent(height.toFloat(), 66f))
        pathBackground.lineTo(getPercent(width.toFloat(), 66f), (height - correctedBorderPosition).toFloat())
        pathBackground.lineTo(getPercent(width.toFloat(), 33f), (height - correctedBorderPosition).toFloat())
        pathBackground.lineTo(correctedBorderPosition.toFloat(), getPercent(height.toFloat(), 66f))
        pathBackground.lineTo(correctedBorderPosition.toFloat(), getPercent(height.toFloat(), 33f))
        pathBackground.lineTo(getPercent(width.toFloat(), 33f), correctedBorderPosition.toFloat())
        canvas.drawPath(pathBackground, paintBackground)

        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            // draw no direction rect
            paintBorder.style = Paint.Style.STROKE
            paintBorder.setColor(normalColor)
            canvas.drawRect(
                getPercent(width.toFloat(), 36f), getPercent(height.toFloat(), 36f),
                getPercent(width.toFloat(), 63f), getPercent(height.toFloat(), 63f),
                paintBorder
            )
        }

        // draw left rect
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) pressedColor else normalColor)
        paintBorder.style = Paint.Style.STROKE
        canvas.drawRect(
            correctedBorderPosition.toFloat(), getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f), getPercent(height.toFloat(), 66f),
            paintBorder
        )

        // draw up rect
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0) pressedColor else normalColor)
        paintBorder.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f), correctedBorderPosition.toFloat(),
            getPercent(width.toFloat(), 66f), getPercent(height.toFloat(), 33f),
            paintBorder
        )

        // draw right rect
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) pressedColor else normalColor)
        paintBorder.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 66f), getPercent(height.toFloat(), 33f),
            (width - correctedBorderPosition).toFloat(), getPercent(height.toFloat(), 66f),
            paintBorder
        )

        // draw down rect
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) pressedColor else normalColor)
        paintBorder.style = Paint.Style.STROKE
        canvas.drawRect(
            getPercent(width.toFloat(), 33f), getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f), (height - correctedBorderPosition).toFloat(),
            paintBorder
        )

        // draw left up line
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 &&
                (direction and DIGITAL_PAD_DIRECTION_UP) > 0) pressedColor else normalColor
        )
        paintBorder.style = Paint.Style.STROKE
        canvas.drawLine(
            correctedBorderPosition.toFloat(), getPercent(height.toFloat(), 33f),
            getPercent(width.toFloat(), 33f), correctedBorderPosition.toFloat(),
            paintBorder
        )

        // draw up right line
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0 &&
                (direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) pressedColor else normalColor
        )
        paintBorder.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 66f), correctedBorderPosition.toFloat(),
            (width - correctedBorderPosition).toFloat(), getPercent(height.toFloat(), 33f),
            paintBorder
        )

        // draw right down line
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 &&
                (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) pressedColor else normalColor
        )
        paintBorder.style = Paint.Style.STROKE
        canvas.drawLine(
            width - paintBorder.strokeWidth, getPercent(height.toFloat(), 66f),
            getPercent(width.toFloat(), 66f), (height - correctedBorderPosition).toFloat(),
            paintBorder
        )

        // draw down left line
        paintBorder.setColor(
            if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0 &&
                (direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) pressedColor else normalColor
        )
        paintBorder.style = Paint.Style.STROKE
        canvas.drawLine(
            getPercent(width.toFloat(), 33f), (height - correctedBorderPosition).toFloat(),
            correctedBorderPosition.toFloat(), getPercent(height.toFloat(), 66f),
            paintBorder
        )

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

    private fun newDirectionCallback(direction: Int) {
        // notify listeners
        listener!!.onDirectionChange(direction)
    }

    interface DigitalPadListener {
        fun onDirectionChange(direction: Int)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (digitalPadPage == null) {
            digitalPadPage = LayoutInflater.from(context).inflate(R.layout.page_digital_pad_clean, null) as SuperPageLayout
            centralXNumberSeekbar = digitalPadPage!!.findViewById(R.id.page_digital_pad_central_x)
            centralYNumberSeekbar = digitalPadPage!!.findViewById(R.id.page_digital_pad_central_y)
        }

        val digitalPadPage = digitalPadPage!!
        val widthNumberSeekbar = digitalPadPage.findViewById<NumberSeekbar>(R.id.page_digital_pad_width)
        val heightNumberSeekbar = digitalPadPage.findViewById<NumberSeekbar>(R.id.page_digital_pad_height)
        val upValueTextView = digitalPadPage.findViewById<TextView>(R.id.page_digital_pad_up_value)
        val downValueTextView = digitalPadPage.findViewById<TextView>(R.id.page_digital_pad_down_value)
        val leftValueTextView = digitalPadPage.findViewById<TextView>(R.id.page_digital_pad_left_value)
        val rightValueTextView = digitalPadPage.findViewById<TextView>(R.id.page_digital_pad_right_value)
        val thickNumberSeekbar = digitalPadPage.findViewById<NumberSeekbar>(R.id.page_digital_pad_thick)
        val layerNumberSeekbar = digitalPadPage.findViewById<NumberSeekbar>(R.id.page_digital_pad_layer)
        val normalColorElementEditText = digitalPadPage.findViewById<ElementEditText>(R.id.page_digital_pad_normal_color)
        val pressedColorElementEditText = digitalPadPage.findViewById<ElementEditText>(R.id.page_digital_pad_pressed_color)
        val backgroundColorElementEditText = digitalPadPage.findViewById<ElementEditText>(R.id.page_digital_pad_background_color)
        val copyButton = digitalPadPage.findViewById<Button>(R.id.page_digital_pad_copy)
        val deleteButton = digitalPadPage.findViewById<Button>(R.id.page_digital_pad_delete)

        upValueTextView.text = pageDeviceController!!.getKeyNameByValue(upValue)
        upValueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    // page页设置值文本
                    (v as TextView).text = key.text
                    setElementUpValue(key.tag.toString())
                    save()
                }
                pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
            }
        })

        downValueTextView.text = pageDeviceController!!.getKeyNameByValue(downValue)
        downValueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    setElementDownValue(key.tag.toString())
                    // page页设置值文本
                    (v as TextView).text = key.text
                    save()
                }
                pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
            }
        })

        leftValueTextView.text = pageDeviceController!!.getKeyNameByValue(leftValue)
        leftValueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    setElementLeftValue(key.tag.toString())
                    // page页设置值文本
                    (v as TextView).text = key.text
                    save()
                }
                pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
            }
        })

        rightValueTextView.text = pageDeviceController!!.getKeyNameByValue(rightValue)
        rightValueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    setElementRightValue(key.tag.toString())
                    // page页设置值文本
                    (v as TextView).text = key.text
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

        CrownColorPickerBinder.bind(this, normalColorElementEditText, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColorElementEditText, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColorElementEditText, { this.backgroundColor }, this::setElementBackgroundColor)

        copyButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_DIGITAL_PAD)
                contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue)
                contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue)
                contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue)
                contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue)
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
                contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin))
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
                contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
                contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
                contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
                elementController.addElement(contentValues)
            }
        })

        deleteButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                elementController.toggleInfoPage(digitalPadPage)
                elementController.deleteElement(digitalPad!!)
            }
        })

        return digitalPadPage
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue)
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue)
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue)
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue)
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer)
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick)
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor)
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor)
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
        elementController.updateElement(elementId!!, contentValues)
    }

    override fun updatePage() {
        if (digitalPadPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    fun setElementUpValue(upValue: String?) {
        this.upValue = upValue
        upValueSenderHandler = elementController.getSendEventHandler(upValue)
    }

    fun setElementDownValue(downValue: String?) {
        this.downValue = downValue
        downValueSenderHandler = elementController.getSendEventHandler(downValue)
    }

    fun setElementLeftValue(leftValue: String?) {
        this.leftValue = leftValue
        leftValueSenderHandler = elementController.getSendEventHandler(leftValue)
    }

    fun setElementRightValue(rightValue: String?) {
        this.rightValue = rightValue
        rightValueSenderHandler = elementController.getSendEventHandler(rightValue)
    }

    protected fun setElementThick(thick: Int) {
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

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        // get masked (not specific to a pointer) action
        when (event.actionMasked) {
            // ACTION_DOWN 在 Java 源实现中不带 break，落入 ACTION_MOVE 逻辑，这里保持等价行为
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    elementController.buttonVibrator()
                }
                direction = 0

                if (event.x < getPercent(width.toFloat(), 33f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_LEFT
                }
                if (event.x > getPercent(width.toFloat(), 66f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_RIGHT
                }
                if (event.y > getPercent(height.toFloat(), 66f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_DOWN
                }
                if (event.y < getPercent(height.toFloat(), 33f)) {
                    direction = direction or DIGITAL_PAD_DIRECTION_UP
                }
                newDirectionCallback(direction)
                invalidate()

                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                direction = 0
                newDirectionCallback(direction)
                invalidate()

                return true
            }
            else -> {
            }
        }

        return true
    }
}
