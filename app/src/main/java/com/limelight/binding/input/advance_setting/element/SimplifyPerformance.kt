//串流信息
package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar

import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class SimplifyPerformance(attributesMap: Map<String, Any>,
                          controller: ElementController,
                          context: Context) : Element(attributesMap, controller, context) {
    companion object {
        private const val SIMPLIFY_PERFORMANCE_TEXT_DEFAULT = "  带宽: ##带宽##    主机/网络/解码: ##主机延时## / ##网络延时## / ##解码时间##    帧率: ##帧率##    丢帧: ##丢帧率##    渲染:##渲染延迟##    时间:HH:MM:SS"
        private const val COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE = Element.COLUMN_INT_ELEMENT_THICK
        private const val COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR = Element.COLUMN_INT_ELEMENT_NORMAL_COLOR
        private const val COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT = Element.COLUMN_STRING_ELEMENT_TEXT
        private const val HEARTBEAT_INTERVAL_MS = 1000L // 1秒心跳间隔

        @JvmStatic
        fun getInitialInfo(context: Context): ContentValues {
            val values = getInitialInfo()
            values.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, context.getString(R.string.crown_performance_default))
            return values
        }

        @JvmStatic
        fun getInitialInfo(): ContentValues {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE)
            contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, SIMPLIFY_PERFORMANCE_TEXT_DEFAULT)
            contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100)
            contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 20)
            contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100)
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100)
            contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 19)
            contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE, 30)
            contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR, 0xB3FFFFFF.toInt())
            contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0xF0555555.toInt())
            return contentValues
        }
    }

    private val simplifyPerformance: SimplifyPerformance
    private val pattern = Pattern.compile("##(.*?)##")
    private val displayMetrics = DisplayMetrics()

    private var afterParseText = "null"

    private var preParseText: String?
    private var radius: Int
    private var verticalPadding = 10 // 给一个默认的垂直内边距，使文本看起来更舒适
    private var horizontalPadding = 10 // 增加一个水平内边距
    private var textColor: Int
    private var textSize: Int
    // Java 原代码在本类声明了私有 layer 字段，遮蔽 Element.layer：
    // setElementLayer 只更新基类字段，而 getSaveContentValues 读取本类副本。
    // Kotlin 属性不允许重名遮蔽，改用独立字段保留该行为。
    private var layerValue: Int
    private var backgroundColor: Int

    // 添加时钟相关字段
    private var showClock = false
    private val hourFormat = SimpleDateFormat("HH", Locale.getDefault())
    private val minuteFormat = SimpleDateFormat("mm", Locale.getDefault())
    private val secondFormat = SimpleDateFormat("ss", Locale.getDefault())

    private var simplifyPerformancePage: SuperPageLayout? = null
    private var centralXNumberSeekbar: NumberSeekbar? = null
    private var centralYNumberSeekbar: NumberSeekbar? = null
    private var radiusNumberSeekbar: NumberSeekbar? = null

    private val paintBackground = Paint()
    private val paintText = Paint()
    private val paintEdit = Paint()
    private val rect = RectF()

    // --- 自驱动心跳机制 ---
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var isHeartbeatActive = false
    private val performanceInfoDisplayListener: Game.PerformanceInfoDisplay

    init {
        simplifyPerformance = this
        (context as Game).windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        centralXMax = displayMetrics.widthPixels
        centralXMin = 0
        centralYMax = displayMetrics.heightPixels
        centralYMin = 0
        widthMax = 100000
        widthMin = 0
        heightMax = 100000
        heightMin = 0

        paintEdit.style = Paint.Style.STROKE
        paintEdit.strokeWidth = 4f
        paintEdit.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
        paintText.textAlign = Paint.Align.LEFT
        paintBackground.style = Paint.Style.FILL

        preParseText = attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT) as String?
        radius = (attributesMap.get(COLUMN_INT_ELEMENT_RADIUS) as Long).toInt()
        layerValue = (attributesMap.get(COLUMN_INT_ELEMENT_LAYER) as Long).toInt()
        textSize = (attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE) as Long).toInt()
        textColor = (attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR) as Long).toInt()
        backgroundColor = (attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR) as Long).toInt()

        // 检查是否包含时钟占位符
        checkForClockPlaceholder()

        // 1. 创建监听器并持有其引用
        performanceInfoDisplayListener = object : Game.PerformanceInfoDisplay {
            override fun display(performanceAttrs: Map<String, String>) {
                // 先复制原始文本
                var tempText = preParseText

                // 处理性能数据占位符
                val matcher = pattern.matcher(tempText)
                val sb = StringBuffer()
                try {
                    while (matcher.find()) {
                        val key = matcher.group(1)
                        val replacement = performanceAttrs.getOrDefault(
                            com.limelight.utils.PerformanceTemplateTokens.canonicalize(key), "N/A")
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
                    }
                    matcher.appendTail(sb)
                    tempText = sb.toString()
                } catch (e: Exception) {
                    // 如果处理出错，保留原始文本
                    tempText = preParseText
                }

                // 处理时钟占位符
                if (showClock) {
                    val calendar = Calendar.getInstance()
                    val hour = hourFormat.format(calendar.time)
                    val minute = minuteFormat.format(calendar.time)
                    val second = secondFormat.format(calendar.time)

                    tempText = tempText!!.replace("HH", hour)
                    tempText = tempText.replace("MM", minute)
                    tempText = tempText.replace("SS", second)
                }

                afterParseText = tempText!!

                // 数据更新后，立即重算尺寸
                changeSize()
            }
        }
        (context as Game).addPerformanceInfoDisplay(performanceInfoDisplayListener)

        // 2. 初始化并启动心跳
        initializeHeartbeat()
        startHeartbeat()
    }

    // --- 心跳和生命周期管理 ---

    private fun initializeHeartbeat() {
        heartbeatRunnable = Runnable {
            if (isHeartbeatActive) {// 如果显示时钟，则每秒更新一次
                if (showClock) {
                    // 更新时间显示
                    if (!"null".equals(afterParseText) && !afterParseText.isEmpty()) {
                        val calendar = Calendar.getInstance()
                        val hour = hourFormat.format(calendar.time)
                        val minute = minuteFormat.format(calendar.time)
                        val second = secondFormat.format(calendar.time)

                        // 先复制当前文本
                        var tempText = afterParseText

                        // 更新时间占位符
                        tempText = tempText.replace("HH", hour)
                        tempText = tempText.replace("MM", minute)
                        tempText = tempText.replace("SS", second)

                        afterParseText = tempText
                    }
                    invalidate()
                } else {
                    invalidate() // 周期性地请求重绘
                }
                heartbeatHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun startHeartbeat() {
        if (!isHeartbeatActive) {
            isHeartbeatActive = true
            heartbeatHandler.post(heartbeatRunnable!!)
        }
    }

    private fun stopHeartbeat() {
        isHeartbeatActive = false
        heartbeatHandler.removeCallbacks(heartbeatRunnable!!)
    }

    /**
     * 元素销毁时调用的清理方法
     */
    fun destroy() {
        stopHeartbeat()
        if (context is Game && performanceInfoDisplayListener != null) {
            (context as Game).removePerformanceInfoDisplay(performanceInfoDisplayListener)
        }
    }

    // --- 尺寸和绘制 ---

    private fun changeSize() {
        paintText.textSize = textSize.toFloat()

        // 按换行符分割文本
        val lines = afterParseText.split("\n")
        if (lines.size == 0) { // 处理空文本的情况
            setElementWidth(horizontalPadding * 2)
            setElementHeight(verticalPadding * 2)
            return
        }

        // 计算最长一行的宽度
        var maxWidth = 0f
        for (line in lines) {
            val lineWidth = paintText.measureText(line)
            if (lineWidth > maxWidth) {
                maxWidth = lineWidth
            }
        }

        // 计算总高度
        val fontMetrics = paintText.fontMetrics
        val singleLineHeight = fontMetrics.bottom - fontMetrics.top
        val totalTextHeight = (singleLineHeight * lines.size).toInt()

        val width = maxWidth.toInt() + horizontalPadding * 2
        val height = totalTextHeight + verticalPadding * 2
        setElementWidth(width)
        setElementHeight(height)

        // 更新半径滑块的最大值
        if (radiusNumberSeekbar != null) {
            radiusNumberSeekbar!!.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
        }
        invalidate()
    }

    override fun onElementDraw(canvas: Canvas) {
        // 设置画笔颜色
        paintText.setColor(textColor)
        paintBackground.setColor(backgroundColor)

        // 定义背景绘制范围
        rect.left = 0f
        rect.top = 0f
        rect.right = getElementWidth().toFloat()
        rect.bottom = getElementHeight().toFloat()

        // 绘制圆角背景
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paintBackground)

        // [修改] 逐行绘制文本以支持换行
        val lines = afterParseText.split("\n")
        val fontMetrics = paintText.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top

        // 计算第一行文本的基线 (baseline) Y坐标
        // y坐标在 drawText 中是基线的位置，而不是文本的顶部。
        // -fontMetrics.top 可以得到从顶部到基线的距离
        val startY = verticalPadding - fontMetrics.top

        for (i in lines.indices) {
            // 计算当前行的Y坐标
            val currentY = startY + (i * lineHeight)
            // 从水平内边距开始绘制
            canvas.drawText(lines[i], horizontalPadding.toFloat(), currentY, paintText)
        }

        // 绘制编辑模式下的边框
        val mode = elementController.getMode()
        if (mode === ElementController.Mode.Edit || mode === ElementController.Mode.Select) {
            rect.left = 2f
            rect.top = 2f
            rect.right = getElementWidth() - 2f
            rect.bottom = getElementHeight() - 2f
            paintEdit.setColor(editColor)
            canvas.drawRect(rect, paintEdit)
        }
    }

    // --- UI 和页面逻辑 ---

    override fun getInfoPage(): SuperPageLayout {
        if (simplifyPerformancePage == null) {
            simplifyPerformancePage = LayoutInflater.from(context).inflate(R.layout.page_simplify_performance_clean, null) as SuperPageLayout
            centralXNumberSeekbar = simplifyPerformancePage!!.findViewById(R.id.page_simplify_performance_central_x)
            centralYNumberSeekbar = simplifyPerformancePage!!.findViewById(R.id.page_simplify_performance_central_y)
        }

        val simplifyPerformancePage = simplifyPerformancePage!!
        radiusNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_radius)
        val textEditText = simplifyPerformancePage.findViewById<EditText>(R.id.page_simplify_performance_text)
        val textEnsureButton = simplifyPerformancePage.findViewById<Button>(R.id.page_simplify_performance_text_ensure)
        val textResetButton = simplifyPerformancePage.findViewById<Button>(R.id.page_simplify_performance_text_reset)
        val textSizeNumberSeekbar = simplifyPerformancePage.findViewById<NumberSeekbar>(R.id.page_simplify_performance_text_size)
        val layerNumberSeekbar = simplifyPerformancePage.findViewById<NumberSeekbar>(R.id.page_simplify_performance_layer)
        val textColorElementEditText = simplifyPerformancePage.findViewById<ElementEditText>(R.id.page_simplify_performance_text_color)
        val backgroundColorElementEditText = simplifyPerformancePage.findViewById<ElementEditText>(R.id.page_simplify_performance_background_color)
        val copyButton = simplifyPerformancePage.findViewById<Button>(R.id.page_simplify_performance_copy)
        val deleteButton = simplifyPerformancePage.findViewById<Button>(R.id.page_simplify_performance_delete)

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

        radiusNumberSeekbar!!.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2)
        radiusNumberSeekbar!!.setValueWithNoCallBack(radius)
        radiusNumberSeekbar!!.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementRadius(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                save()
            }
        })

        // 使 EditText 支持多行输入
        textEditText.setSingleLine(false)
        textEditText.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        textEditText.gravity = Gravity.TOP or Gravity.START
        textEditText.setText(preParseText!!.replace("\\n", "\n"))
        textEnsureButton.setOnClickListener {
            setElementPreParseText(textEditText.getText().toString())
            save()
        }

        textResetButton.setOnClickListener {
            setElementPreParseText(context.getString(R.string.crown_performance_default))
            textEditText.setText(context.getString(R.string.crown_performance_default))
            save()
        }

        textSizeNumberSeekbar.setProgressMin(10)
        textSizeNumberSeekbar.setProgressMax(50)
        textSizeNumberSeekbar.setValueWithNoCallBack(textSize)
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                setElementTextSize(progress)
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

        CrownColorPickerBinder.bind(this, textColorElementEditText, { this.textColor }, this::setElementTextColor)
        CrownColorPickerBinder.bind(this, backgroundColorElementEditText, { this.backgroundColor }, this::setElementBackgroundColor)

        copyButton.setOnClickListener {
            val contentValues = getSaveContentValues()
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin))
            elementController.addElement(contentValues)
        }

        deleteButton.setOnClickListener {
            destroy() // 在删除前清理资源
            elementController.toggleInfoPage(simplifyPerformancePage)
            elementController.deleteElement(simplifyPerformance)
        }

        return simplifyPerformancePage
    }

    // --- 数据持久化和状态更新 ---

    private fun getSaveContentValues(): ContentValues {
        val contentValues = ContentValues()
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE)
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText)
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth())
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight())
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layerValue)
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX())
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY())
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius)
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE, textSize)
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR, textColor)
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor)
        return contentValues
    }

    override fun save() {
        elementController.updateElement(elementId!!, getSaveContentValues())
    }

    override fun updatePage() {
        if (simplifyPerformancePage != null) {
            centralXNumberSeekbar!!.setValueWithNoCallBack(getElementCentralX())
            centralYNumberSeekbar!!.setValueWithNoCallBack(getElementCentralY())
        }
    }

    // --- Setters ---

    fun setElementPreParseText(preParseText: String) {
        this.preParseText = if (preParseText.isEmpty()) " " else preParseText
        checkForClockPlaceholder() // 检查是否包含时钟占位符
    }

    fun setElementRadius(radius: Int) {
        this.radius = radius
        invalidate()
    }

    fun setElementTextColor(textColor: Int) {
        this.textColor = textColor
        invalidate()
    }

    fun setElementTextSize(textSize: Int) {
        this.textSize = textSize
        changeSize()
    }

    fun setElementBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        invalidate()
    }

    // --- 其他 ---

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        return false
    }

    // 添加检查时钟占位符的方法
    private fun checkForClockPlaceholder() {
        showClock = preParseText!!.contains("HH") || preParseText!!.contains("MM") || preParseText!!.contains("SS")
    }
}
