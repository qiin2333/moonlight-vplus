//隐藏式按键摇杆
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


class InvisibleDigitalStick(attributesMap: Map<String, Any>,
                           controller: ElementController,
                           pageDeviceController: PageDeviceController,
                           context: Context) : Element(attributesMap, controller, context) {
    companion object {
        private const val COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS = Element.COLUMN_INT_ELEMENT_SENSE

        /**
         * outer radius size in percent of the ui element
         */
        const val SIZE_RADIUS_COMPLETE = 90
        /**
         * analog stick size in percent of the ui element
         */
        const val SIZE_RADIUS_ANALOG_STICK = 90
        /**
         * dead zone size in percent of the ui element
         */
        const val SIZE_RADIUS_DEADZONE = 90
        /**
         * time frame for a double click
         */
        const val timeoutDoubleClick = 350L

        /**
         * touch down time until the deadzone is lifted to allow precise movements with the analog sticks
         */
        const val timeoutDeadzone = 150L

        private fun getMovementRadius(x: Float, y: Float): Double {
            return Math.sqrt((x * x + y * y).toDouble())
        }

        private fun getAngle(way_x: Float, way_y: Float): Double {
            // prevent divisions by zero for corner cases
            if (way_x == 0f) {
                return if (way_y < 0) 0.0 else Math.PI
            } else if (way_y == 0f) {
                if (way_x > 0) {
                    return Math.PI * 3 / 2
                } else if (way_x < 0) {
                    return Math.PI * 1 / 2
                }
            }
            // return correct calculated angle for each quadrant
            return if (way_x > 0) {
                if (way_y < 0) {
                    // first quadrant
                    3 * Math.PI / 2 + Math.atan((-way_y / way_x).toDouble())
                } else {
                    // second quadrant
                    Math.PI + Math.atan((way_x / way_y).toDouble())
                }
            } else {
                if (way_y > 0) {
                    // third quadrant
                    Math.PI / 2 + Math.atan((way_y / -way_x).toDouble())
                } else {
                    // fourth quadrant
                    0.0 + Math.atan((-way_x / -way_y).toDouble())
                }
            }
        }

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK)
            contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, "k51")
            contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, "k47")
            contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, "k29")
            contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, "k32")
            contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, "k59")
            contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, 30)
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 400)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 400)
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 45)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 400)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 400)
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 100)
            contentValues.put(COLUMN_INT_ELEMENT_THICK, 5)
            contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0x00FFFFFF)
            return contentValues
        }
    }

    /**
     * Listener interface to update registered observers.
     */
    interface InvisibleDigitalStickListener {
        /**
         * onMovement event will be fired on real analog stick movement (outside of the deadzone).
         *
         * @param x horizontal position, value from -1.0 ... 0 .. 1.0
         * @param y vertical position, value from -1.0 ... 0 .. 1.0
         */
        fun onMovement(x: Float, y: Float)

        /**
         * onClick event will be fired on click on the analog stick
         */
        fun onClick()

        /**
         * onDoubleClick event will be fired on a double click in a short time frame on the analog
         * stick.
         */
        fun onDoubleClick()

        /**
         * onRevoke event will be fired on unpress of the analog stick.
         */
        fun onRevoke()
    }

    /**
     * Movement states of the analog sick.
     */
    private enum class STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    /**
     * Click type states.
     */
    private enum class CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    /**
     * configuration if the analog stick should be displayed as circle or square
     */
    private val circle_stick = true // TODO: implement square sick for simulations

    /**
     * outer radius, this size will be automatically updated on resize
     */
    private var radius_complete = 0f
    /**
     * analog stick radius, this size will be automatically updated on resize
     */
    private var radius_analog_stick = 0f
    /**
     * dead zone radius, this size will be automatically updated on resize
     */
    private var radius_dead_zone = 0f

    /**
     * horizontal position in relation to the center of the element
     */
    private var relative_x = 0f
    /**
     * vertical position in relation to the center of the element
     */
    private var relative_y = 0f

    private var movement_radius = 0.0
    private var movement_angle = 0.0

    private var position_stick_x = 0f
    private var position_stick_y = 0f
    private var circleCenterX = 0f
    private var circleCenterY = 0f

    private var superConfigDatabaseHelper: SuperConfigDatabaseHelper? = null
    private var pageDeviceController: PageDeviceController? = null
    private var invisibleDigitalStick: InvisibleDigitalStick? = null

    private var middleValueSendHandler: ElementController.SendEventHandler? = null
    private var upValueSendHandler: ElementController.SendEventHandler? = null
    private var downValueSendHandler: ElementController.SendEventHandler? = null
    private var leftValueSendHandler: ElementController.SendEventHandler? = null
    private var rightValueSendHandler: ElementController.SendEventHandler? = null
    private var middleValue: String? = null
    private var upValue: String? = null
    private var downValue: String? = null
    private var leftValue: String? = null
    private var rightValue: String? = null
    private var radius = 0
    private var deadZoneRadius = 0 //dead zone radius
    private var thick = 0
    private var normalColor = 0
    private var pressedColor = 0
    private var backgroundColor = 0

    private var invisibleDigitalStickPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null

    private val paintStick = Paint()
    private val paintBackground = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()

    private var stick_state = InvisibleDigitalStick.STICK_STATE.NO_MOVEMENT
    private var click_state = InvisibleDigitalStick.CLICK_STATE.SINGLE

    private var listener: InvisibleDigitalStickListener? = null
    private var timeLastClick: Long = 0
    private var upIsPressed = false
    private var downIsPressed = false
    private var leftIsPressed = false
    private var rightIsPressed = false

    init {
        // reset stick position
        circleCenterX = (width / 2).toFloat()
        circleCenterY = (height / 2).toFloat()
        position_stick_x = circleCenterX
        position_stick_y = circleCenterY

        this.pageDeviceController = pageDeviceController
        this.invisibleDigitalStick = this

        val displayMetrics = DisplayMetrics()
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        centralXMax = displayMetrics.widthPixels
        centralXMin = 0
        centralYMax = displayMetrics.heightPixels
        centralYMin = 0
        widthMax = displayMetrics.widthPixels
        widthMin = 100
        heightMax = displayMetrics.heightPixels
        heightMin = 100

        paintBackground.style = Paint.Style.FILL
        paintStick.style = Paint.Style.STROKE
        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)

        radius = (attributesMap.get(COLUMN_INT_ELEMENT_RADIUS) as Long).toInt()
        deadZoneRadius = (attributesMap.get(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS) as Long).toInt()
        thick = (attributesMap.get(COLUMN_INT_ELEMENT_THICK) as Long).toInt()
        normalColor = (attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR) as Long).toInt()
        pressedColor = (attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()
        middleValue = attributesMap.get(COLUMN_STRING_ELEMENT_MIDDLE_VALUE) as String?
        upValue = attributesMap.get(COLUMN_STRING_ELEMENT_UP_VALUE) as String?
        downValue = attributesMap.get(COLUMN_STRING_ELEMENT_DOWN_VALUE) as String?
        leftValue = attributesMap.get(COLUMN_STRING_ELEMENT_LEFT_VALUE) as String?
        rightValue = attributesMap.get(COLUMN_STRING_ELEMENT_RIGHT_VALUE) as String?
        middleValueSendHandler = controller.getSendEventHandler(middleValue)
        upValueSendHandler = controller.getSendEventHandler(upValue)
        downValueSendHandler = controller.getSendEventHandler(downValue)
        leftValueSendHandler = controller.getSendEventHandler(leftValue)
        rightValueSendHandler = controller.getSendEventHandler(rightValue)

        listener = object : InvisibleDigitalStickListener {
            override fun onMovement(x: Float, y: Float) {
                if (x < -deadZoneRadius * 0.01 && !leftIsPressed) {
                    elementController.buttonVibrator()
                    leftValueSendHandler!!.sendEvent(true)
                    leftIsPressed = true
                } else if (x > -deadZoneRadius * 0.01 && leftIsPressed) {
                    leftValueSendHandler!!.sendEvent(false)
                    leftIsPressed = false
                }
                if (x > deadZoneRadius * 0.01 && !rightIsPressed) {
                    elementController.buttonVibrator()
                    rightValueSendHandler!!.sendEvent(true)
                    rightIsPressed = true
                } else if (x < deadZoneRadius * 0.01 && rightIsPressed) {
                    rightValueSendHandler!!.sendEvent(false)
                    rightIsPressed = false
                }
                if (y < -deadZoneRadius * 0.01 && !downIsPressed) {
                    elementController.buttonVibrator()
                    downValueSendHandler!!.sendEvent(true)
                    downIsPressed = true
                } else if (y > -deadZoneRadius * 0.01 && downIsPressed) {
                    downValueSendHandler!!.sendEvent(false)
                    downIsPressed = false
                }
                if (y > deadZoneRadius * 0.01 && !upIsPressed) {
                    elementController.buttonVibrator()
                    upValueSendHandler!!.sendEvent(true)
                    upIsPressed = true
                } else if (y < deadZoneRadius * 0.01 && upIsPressed) {
                    upValueSendHandler!!.sendEvent(false)
                    upIsPressed = false
                }
            }

            override fun onClick() {
                elementController.buttonVibrator()
            }

            override fun onDoubleClick() {
                elementController.buttonVibrator()
                middleValueSendHandler!!.sendEvent(true)
            }

            override fun onRevoke() {
                middleValueSendHandler!!.sendEvent(false)
            }
        }

        radius_complete = getPercent(radius.toFloat(), 100f) - 2 * thick
        radius_dead_zone = getPercent(radius.toFloat(), deadZoneRadius.toFloat())
        radius_analog_stick = getPercent(radius.toFloat(), 20f)
    }

    private fun notifyOnMovement(x: Float, y: Float) {
        // notify listeners
        listener!!.onMovement(x, y)
    }

    private fun notifyOnClick() {
        // notify listeners
        listener!!.onClick()
    }

    private fun notifyOnDoubleClick() {
        // notify listeners
        listener!!.onDoubleClick()
    }

    private fun notifyOnRevoke() {
        // notify listeners
        listener!!.onRevoke()
    }

    override fun getCrownInspectorTitle(): String {
        return context.getString(R.string.layout_page_invisible_digital_stick_text_e60ec)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (invisibleDigitalStickPage == null) {
            invisibleDigitalStickPage = LayoutInflater.from(context).inflate(R.layout.page_invisible_digital_stick_clean, null) as SuperPageLayout
            centralXNumberSeekbar = invisibleDigitalStickPage!!.findViewById(R.id.page_invisible_digital_stick_central_x)
            centralYNumberSeekbar = invisibleDigitalStickPage!!.findViewById(R.id.page_invisible_digital_stick_central_y)
        }

        val invisibleDigitalStickPage = invisibleDigitalStickPage!!
        val widthNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_width)
        val heightNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_height)
        val radiusNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_radius)
        val middleValueTextView = invisibleDigitalStickPage.findViewById<TextView>(R.id.page_invisible_digital_stick_middle_value)
        val upValueTextView = invisibleDigitalStickPage.findViewById<TextView>(R.id.page_invisible_digital_stick_up_value)
        val downValueTextView = invisibleDigitalStickPage.findViewById<TextView>(R.id.page_invisible_digital_stick_down_value)
        val leftValueTextView = invisibleDigitalStickPage.findViewById<TextView>(R.id.page_invisible_digital_stick_left_value)
        val rightValueTextView = invisibleDigitalStickPage.findViewById<TextView>(R.id.page_invisible_digital_stick_right_value)
        val senseNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_sense)
        val thickNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_thick)
        val layerNumberSeekbar = invisibleDigitalStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_digital_stick_layer)
        val normalColorEditText = invisibleDigitalStickPage.findViewById<ElementEditText>(R.id.page_invisible_digital_stick_normal_color)
        val pressedColorEditText = invisibleDigitalStickPage.findViewById<ElementEditText>(R.id.page_invisible_digital_stick_pressed_color)
        val backgroundColorEditText = invisibleDigitalStickPage.findViewById<ElementEditText>(R.id.page_invisible_digital_stick_background_color)
        val copyButton = invisibleDigitalStickPage.findViewById<Button>(R.id.page_invisible_digital_stick_copy)
        val deleteButton = invisibleDigitalStickPage.findViewById<Button>(R.id.page_invisible_digital_stick_delete)

        middleValueTextView.text = pageDeviceController!!.getKeyNameByValue(middleValue)
        middleValueTextView.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val deviceCallBack = PageDeviceController.DeviceCallBack { key ->
                    // page页设置值文本
                    (v as TextView).text = key.text
                    setElementMiddleValue(key.tag.toString())
                    save()
                }
                pageDeviceController!!.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE)
            }
        })
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
                    // page页设置值文本
                    (v as TextView).text = key.text
                    setElementDownValue(key.tag.toString())
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

        senseNumberSeekbar.setValueWithNoCallBack(deadZoneRadius)
        senseNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementDeadZoneRadius(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
        radiusNumberSeekbar.setProgressMin(10)
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

        CrownColorPickerBinder.bind(this, normalColorEditText, { this.normalColor }, this::setElementNormalColor)
        CrownColorPickerBinder.bind(this, pressedColorEditText, { this.pressedColor }, this::setElementPressedColor)
        CrownColorPickerBinder.bind(this, backgroundColorEditText, { this.backgroundColor }, this::setElementBackgroundColor)

        copyButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK)
                contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue)
                contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue)
                contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue)
                contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue)
                contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue)
                contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius)
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
                elementController.addElement(contentValues)
            }
        })

        deleteButton.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View) {
                elementController.toggleInfoPage(invisibleDigitalStickPage)
                elementController.deleteElement(invisibleDigitalStick!!)
            }
        })

        return invisibleDigitalStickPage
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE, upValue)
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE, downValue)
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE, leftValue)
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE, rightValue)
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue)
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius)
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
        elementController.updateElement(elementId!!, contentValues)
    }

    override fun updatePage() {
        if (invisibleDigitalStickPage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    override fun onElementDraw(canvas: Canvas) {
        // set transparent background
        paintBackground.setColor(backgroundColor)
        rect.top = 0f
        rect.left = 0f
        rect.right = width.toFloat()
        rect.bottom = height.toFloat()
        canvas.drawRect(rect, paintBackground)

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

            paintStick.strokeWidth = thick.toFloat()
            // draw outer circle
            if (!isPressed || click_state === InvisibleDigitalStick.CLICK_STATE.SINGLE) {
                paintStick.setColor(normalColor)
            } else {
                paintStick.setColor(pressedColor)
            }
            canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radius_complete, paintStick)

            paintStick.setColor(normalColor)
            // draw dead zone
            canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radius_dead_zone, paintStick)

            paintStick.setColor(normalColor)
            canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radius_analog_stick, paintStick)
        }

        if (!isPressed) {
            return
        }

        paintStick.style = Paint.Style.STROKE
        paintStick.strokeWidth = thick.toFloat()
        // draw outer circle
        if (!isPressed || click_state === InvisibleDigitalStick.CLICK_STATE.SINGLE) {
            paintStick.setColor(normalColor)
        } else {
            paintStick.setColor(pressedColor)
        }
        canvas.drawCircle(circleCenterX, circleCenterY, radius_complete, paintStick)

        paintStick.setColor(normalColor)
        // draw dead zone
        canvas.drawCircle(circleCenterX, circleCenterY, radius_dead_zone, paintStick)

        // draw stick depending on state
        when (stick_state) {
            InvisibleDigitalStick.STICK_STATE.NO_MOVEMENT -> {
                paintStick.setColor(normalColor)
                canvas.drawCircle(circleCenterX, circleCenterY, radius_analog_stick, paintStick)
            }
            InvisibleDigitalStick.STICK_STATE.MOVED_IN_DEAD_ZONE, InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE -> {
                paintStick.setColor(pressedColor)
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paintStick)
            }
        }
    }

    private fun updatePosition(eventTime: Long) {
        // get 100% way
        val complete = radius_complete - radius_analog_stick

        // calculate relative way
        val correlated_y = (Math.sin(Math.PI / 2 - movement_angle) * movement_radius).toFloat()
        val correlated_x = (Math.cos(Math.PI / 2 - movement_angle) * movement_radius).toFloat()

        // update positions
        position_stick_x = circleCenterX - correlated_x
        position_stick_y = circleCenterY - correlated_y

        // Stay active even if we're back in the deadzone because we know the user is actively
        // giving analog stick input and we don't want to snap back into the deadzone.
        // We also release the deadzone if the user keeps the stick pressed for a bit to allow
        // them to make precise movements.
        stick_state = if (stick_state === InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE ||
            eventTime - timeLastClick > timeoutDeadzone ||
            movement_radius > radius_dead_zone
        ) InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE else InvisibleDigitalStick.STICK_STATE.MOVED_IN_DEAD_ZONE

        //  trigger move event if state active
        if (stick_state === InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE) {
            notifyOnMovement(-correlated_x / complete, correlated_y / complete)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            //点击后扩大view，防止摇杆显示不全
            circleCenterX = event.x
            circleCenterY = event.y
            invalidate()
        }
        // save last click state
        val lastClickState = click_state

        // get absolute way for each axis
        relative_x = -(circleCenterX - event.x)
        relative_y = -(circleCenterY - event.y)

        // get radius and angel of movement from center
        movement_radius = getMovementRadius(relative_x, relative_y)
        movement_angle = getAngle(relative_x, relative_y)

        // pass touch event to parent if out of outer circle
        if (movement_radius > radius_complete && !isPressed) return false

        // chop radius if out of outer circle or near the edge
        if (movement_radius > radius_complete - radius_analog_stick) {
            movement_radius = (radius_complete - radius_analog_stick).toDouble()
        }

        // handle event depending on action
        when (event.actionMasked) {
            // down event (touch event)
            MotionEvent.ACTION_DOWN -> {
                // set to dead zoned, will be corrected in update position if necessary
                stick_state = InvisibleDigitalStick.STICK_STATE.MOVED_IN_DEAD_ZONE
                // check for double click
                if (lastClickState === InvisibleDigitalStick.CLICK_STATE.SINGLE &&
                    event.eventTime - timeLastClick <= timeoutDoubleClick
                ) {
                    click_state = InvisibleDigitalStick.CLICK_STATE.DOUBLE
                    notifyOnDoubleClick()
                } else {
                    click_state = InvisibleDigitalStick.CLICK_STATE.SINGLE
                    notifyOnClick()
                }
                // reset last click timestamp
                timeLastClick = event.eventTime
                // set item pressed and update
                isPressed = true
            }
            // up event (revoke touch)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
            }
        }

        if (isPressed) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.eventTime)
        } else {
            stick_state = InvisibleDigitalStick.STICK_STATE.NO_MOVEMENT
            notifyOnRevoke()

            // not longer pressed reset analog stick
            notifyOnMovement(0f, 0f)
        }
        // refresh view
        invalidate()
        // accept the touch event
        return true
    }

    fun setElementUpValue(upValue: String?) {
        this.upValue = upValue
        upValueSendHandler = elementController.getSendEventHandler(upValue)
    }

    fun setElementDownValue(downValue: String?) {
        this.downValue = downValue
        downValueSendHandler = elementController.getSendEventHandler(downValue)
    }

    fun setElementLeftValue(leftValue: String?) {
        this.leftValue = leftValue
        leftValueSendHandler = elementController.getSendEventHandler(leftValue)
    }

    fun setElementRightValue(rightValue: String?) {
        this.rightValue = rightValue
        rightValueSendHandler = elementController.getSendEventHandler(rightValue)
    }

    fun setElementMiddleValue(middleValue: String?) {
        this.middleValue = middleValue
        middleValueSendHandler = elementController.getSendEventHandler(middleValue)
    }

    fun setElementRadius(radius: Int) {
        this.radius = radius
        radius_complete = getPercent(radius.toFloat(), 100f) - 2 * thick
        radius_dead_zone = getPercent(radius.toFloat(), deadZoneRadius.toFloat())
        radius_analog_stick = getPercent(radius.toFloat(), 20f)
        invalidate()
    }

    fun setElementDeadZoneRadius(deadZoneRadius: Int) {
        this.deadZoneRadius = deadZoneRadius
        radius_dead_zone = getPercent(radius.toFloat(), deadZoneRadius.toFloat())
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
}
