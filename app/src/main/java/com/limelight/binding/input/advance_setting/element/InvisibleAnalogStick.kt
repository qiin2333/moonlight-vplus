//隐藏式手柄摇杆
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.PageDeviceController
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

import java.util.ArrayList

class InvisibleAnalogStick(attributesMap: Map<String, Any>,
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
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_INVISIBLE_ANALOG_STICK)
            contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "LS")
            contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, "g64")
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
    interface InvisibleAnalogStickListener {
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
    private var invisibleAnalogStick: InvisibleAnalogStick? = null

    private var middleValueSendHandler: ElementController.SendEventHandler? = null
    private var valueSendHandler: ElementController.SendEventHandler? = null
    private var middleValue: String? = null
    private var value: String? = null
    private var radius = 0
    private var deadZoneRadius = 0 //dead zone radius
    private var thick = 0
    private var normalColor = 0
    private var pressedColor = 0
    private var backgroundColor = 0

    private var invisibleAnalogStickPage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null

    private val paintStick = Paint()
    private val paintBackground = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()

    private var stick_state = InvisibleAnalogStick.STICK_STATE.NO_MOVEMENT
    private var click_state = InvisibleAnalogStick.CLICK_STATE.SINGLE

    private var listener: InvisibleAnalogStickListener? = null
    private val listeners = ArrayList<InvisibleAnalogStickListener>()
    private var timeLastClick: Long = 0

    init {
        // reset stick position
        circleCenterX = (width / 2).toFloat()
        circleCenterY = (height / 2).toFloat()
        position_stick_x = circleCenterX
        position_stick_y = circleCenterY

        this.pageDeviceController = pageDeviceController
        this.invisibleAnalogStick = this

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
        value = attributesMap.get(COLUMN_STRING_ELEMENT_VALUE) as String?
        middleValue = attributesMap.get(COLUMN_STRING_ELEMENT_MIDDLE_VALUE) as String?
        valueSendHandler = controller.getSendEventHandler(value)
        middleValueSendHandler = controller.getSendEventHandler(middleValue)

        listener = object : InvisibleAnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                valueSendHandler!!.sendEvent((x * 0x7FFE).toInt(), (y * 0x7FFE).toInt())
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
        return context.getString(R.string.layout_page_invisible_analog_stick_text_5e3cb)
    }

    override fun getInfoPage(): SuperPageLayout {
        if (invisibleAnalogStickPage == null) {
            invisibleAnalogStickPage = LayoutInflater.from(context).inflate(R.layout.page_invisible_analog_stick_clean, null) as SuperPageLayout
            centralXNumberSeekbar = invisibleAnalogStickPage!!.findViewById(R.id.page_invisible_analog_stick_central_x)
            centralYNumberSeekbar = invisibleAnalogStickPage!!.findViewById(R.id.page_invisible_analog_stick_central_y)
        }

        val invisibleAnalogStickPage = invisibleAnalogStickPage!!
        val widthNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_width)
        val heightNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_height)
        val radiusNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_radius)
        val valueRadioGroup = invisibleAnalogStickPage.findViewById<RadioGroup>(R.id.page_invisible_analog_stick_value)
        val middleValueTextView = invisibleAnalogStickPage.findViewById<TextView>(R.id.page_invisible_analog_stick_middle_value)
        val senseNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_sense)
        val thickNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_thick)
        val layerNumberSeekbar = invisibleAnalogStickPage.findViewById<NumberSeekbar>(R.id.page_invisible_analog_stick_layer)
        val normalColorEditText = invisibleAnalogStickPage.findViewById<ElementEditText>(R.id.page_invisible_analog_stick_normal_color)
        val pressedColorEditText = invisibleAnalogStickPage.findViewById<ElementEditText>(R.id.page_invisible_analog_stick_pressed_color)
        val backgroundColorEditText = invisibleAnalogStickPage.findViewById<ElementEditText>(R.id.page_invisible_analog_stick_background_color)
        val copyButton = invisibleAnalogStickPage.findViewById<Button>(R.id.page_invisible_analog_stick_copy)
        val deleteButton = invisibleAnalogStickPage.findViewById<Button>(R.id.page_invisible_analog_stick_delete)

        val radioButton = valueRadioGroup.findViewWithTag<RadioButton>(value)
        radioButton.isChecked = true
        valueRadioGroup.setOnCheckedChangeListener(object : RadioGroup.OnCheckedChangeListener {
            override fun onCheckedChanged(group: RadioGroup, checkedId: Int) {
                setElementValue(group.findViewById<View>(checkedId).tag.toString())
                save()
            }
        })

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
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_INVISIBLE_ANALOG_STICK)
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
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
                elementController.toggleInfoPage(invisibleAnalogStickPage)
                elementController.deleteElement(invisibleAnalogStick!!)
            }
        })

        return invisibleAnalogStickPage
    }

    override fun save() {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value)
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue)
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius)
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
        elementController.updateElement(elementId!!, contentValues)
    }

    override fun updatePage() {
        if (invisibleAnalogStickPage != null) {
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
            if (!isPressed || click_state === InvisibleAnalogStick.CLICK_STATE.SINGLE) {
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
        if (!isPressed || click_state === InvisibleAnalogStick.CLICK_STATE.SINGLE) {
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
            InvisibleAnalogStick.STICK_STATE.NO_MOVEMENT -> {
                paintStick.setColor(normalColor)
                canvas.drawCircle(circleCenterX, circleCenterY, radius_analog_stick, paintStick)
            }
            InvisibleAnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE, InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE -> {
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
        stick_state = if (stick_state === InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE ||
            eventTime - timeLastClick > timeoutDeadzone ||
            movement_radius > radius_dead_zone
        ) InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE else InvisibleAnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE

        //  trigger move event if state active
        if (stick_state === InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE) {
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
                stick_state = InvisibleAnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE
                // check for double click
                if (lastClickState === InvisibleAnalogStick.CLICK_STATE.SINGLE &&
                    event.eventTime - timeLastClick <= timeoutDoubleClick
                ) {
                    click_state = InvisibleAnalogStick.CLICK_STATE.DOUBLE
                    notifyOnDoubleClick()
                } else {
                    click_state = InvisibleAnalogStick.CLICK_STATE.SINGLE
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
            stick_state = InvisibleAnalogStick.STICK_STATE.NO_MOVEMENT
            notifyOnRevoke()

            // not longer pressed reset analog stick
            notifyOnMovement(0f, 0f)
        }
        // refresh view
        invalidate()
        // accept the touch event
        return true
    }

    fun setElementValue(value: String?) {
        this.value = value
        valueSendHandler = elementController.getSendEventHandler(value)
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
