package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

import androidx.preference.DialogPreference

import com.limelight.R

import java.util.regex.Pattern

/**
 * 自定义分辨率常量类
 */
object CustomResolutionsConsts {
    const val CUSTOM_RESOLUTIONS_FILE = "custom_resolutions"
    const val CUSTOM_RESOLUTIONS_KEY = "custom_resolutions"
}

/**
 * 分辨率验证工具类
 */
object ResolutionValidator {
    private const val MIN_WIDTH = 320
    private const val MAX_WIDTH = 7680
    private const val MIN_HEIGHT = 240
    private const val MAX_HEIGHT = 4320

    private val RESOLUTION_PATTERN = Pattern.compile("^\\d{3,5}x\\d{3,5}$")

    fun isValidResolutionFormat(resolution: String): Boolean {
        return RESOLUTION_PATTERN.matcher(resolution).matches()
    }

    fun isValidWidth(width: Int): Boolean = width in MIN_WIDTH..MAX_WIDTH

    fun isValidHeight(height: Int): Boolean = height in MIN_HEIGHT..MAX_HEIGHT

    fun isEven(value: Int): Boolean = value % 2 == 0

    fun parseResolution(resolution: String): Resolution? {
        return try {
            val parts = resolution.split("x")
            if (parts.size != 2) return null
            Resolution(parts[0].toInt(), parts[1].toInt())
        } catch (e: NumberFormatException) {
            null
        }
    }

    class Resolution(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x${height}"
    }
}

/**
 * 自定义分辨率偏好设置类
 */
class CustomResolutionsPreference(
        context: Context,
        attrs: AttributeSet
) : DialogPreference(context, attrs) {

    val adapter = CustomResolutionsAdapter(context)

    init {
        adapter.setOnDataChangedListener(object : EventListener {
            override fun onTrigger() {
                saveResolutions()
            }
        })
    }

    /**
     * 处理分辨率提交
     */
    fun onSubmitResolution(widthField: EditText, heightField: EditText) {
        clearErrors(widthField, heightField)

        val widthText = widthField.text.toString().trim()
        val heightText = heightField.text.toString().trim()

        val validation = validateInput(widthText, heightText)
        if (!validation.isValid) {
            showValidationErrors(validation, widthField, heightField)
            return
        }

        val resolution = validation.resolution!!.toString()

        if (adapter.exists(resolution)) {
            Toast.makeText(context, context.getString(R.string.resolution_already_exists), Toast.LENGTH_SHORT).show()
            return
        }

        addResolution(resolution, widthField, heightField)
    }

    private fun clearErrors(widthField: EditText, heightField: EditText) {
        widthField.error = null
        heightField.error = null
    }

    private fun validateInput(widthText: String, heightText: String): ResolutionValidationResult {
        if (widthText.isEmpty()) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_EMPTY)
        }
        if (heightText.isEmpty()) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_EMPTY)
        }

        val width: Int
        val height: Int
        try {
            width = widthText.toInt()
            height = heightText.toInt()
        } catch (e: NumberFormatException) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.INVALID_FORMAT)
        }

        if (!ResolutionValidator.isValidWidth(width)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_OUT_OF_RANGE)
        }
        if (!ResolutionValidator.isValidHeight(height)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_OUT_OF_RANGE)
        }

        if (!ResolutionValidator.isEven(width)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_ODD)
        }
        if (!ResolutionValidator.isEven(height)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_ODD)
        }

        return ResolutionValidationResult.success(ResolutionValidator.Resolution(width, height))
    }

    private fun showValidationErrors(validation: ResolutionValidationResult, widthField: EditText, heightField: EditText) {
        val errorMessage = getErrorMessage(validation.errorType!!)

        when (validation.errorType) {
            ResolutionValidationResult.ErrorType.WIDTH_EMPTY,
            ResolutionValidationResult.ErrorType.WIDTH_OUT_OF_RANGE,
            ResolutionValidationResult.ErrorType.WIDTH_ODD,
            ResolutionValidationResult.ErrorType.INVALID_FORMAT -> widthField.error = errorMessage

            ResolutionValidationResult.ErrorType.HEIGHT_EMPTY,
            ResolutionValidationResult.ErrorType.HEIGHT_OUT_OF_RANGE,
            ResolutionValidationResult.ErrorType.HEIGHT_ODD -> heightField.error = errorMessage
        }
    }

    private fun getErrorMessage(errorType: ResolutionValidationResult.ErrorType): String {
        return when (errorType) {
            ResolutionValidationResult.ErrorType.WIDTH_EMPTY,
            ResolutionValidationResult.ErrorType.HEIGHT_EMPTY -> context.getString(R.string.width_hint)
            ResolutionValidationResult.ErrorType.INVALID_FORMAT -> context.getString(R.string.invalid_resolution_format)
            ResolutionValidationResult.ErrorType.WIDTH_OUT_OF_RANGE -> "宽度应在320-7680之间"
            ResolutionValidationResult.ErrorType.HEIGHT_OUT_OF_RANGE -> "高度应在240-4320之间"
            ResolutionValidationResult.ErrorType.WIDTH_ODD -> "宽度不能为奇数"
            ResolutionValidationResult.ErrorType.HEIGHT_ODD -> "高度不能为奇数"
        }
    }

    private fun addResolution(resolution: String, widthField: EditText, heightField: EditText) {
        adapter.addItem(resolution)
        Toast.makeText(context, context.getString(R.string.resolution_added_successfully), Toast.LENGTH_SHORT).show()
        clearInputFields(widthField, heightField)
    }

    private fun clearInputFields(widthField: EditText, heightField: EditText) {
        widthField.setText("")
        heightField.setText("")
        widthField.requestFocus()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        // No persisted value needed; resolutions are stored in a separate SharedPreferences file
    }

    fun loadStoredResolutions() {
        val prefs = context.getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, null) ?: return

        val sortedList = ArrayList(stored)
        sortedList.sortWith(ResolutionComparator())
        adapter.addAll(sortedList)
    }

    private fun saveResolutions() {
        val prefs = context.getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
                .putStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, HashSet(adapter.getAll()))
                .apply()
    }
}

/**
 * 事件监听器接口
 */
interface EventListener {
    fun onTrigger()
}

/**
 * 分辨率验证结果类
 */
class ResolutionValidationResult private constructor(
        val isValid: Boolean,
        val resolution: ResolutionValidator.Resolution?,
        val errorType: ErrorType?
) {
    enum class ErrorType {
        WIDTH_EMPTY,
        HEIGHT_EMPTY,
        INVALID_FORMAT,
        WIDTH_OUT_OF_RANGE,
        HEIGHT_OUT_OF_RANGE,
        WIDTH_ODD,
        HEIGHT_ODD
    }

    companion object {
        fun success(resolution: ResolutionValidator.Resolution): ResolutionValidationResult {
            return ResolutionValidationResult(true, resolution, null)
        }

        fun error(errorType: ErrorType): ResolutionValidationResult {
            return ResolutionValidationResult(false, null, errorType)
        }
    }
}

/**
 * 分辨率比较器
 */
class ResolutionComparator : Comparator<String> {
    override fun compare(s1: String, s2: String): Int {
        val res1 = ResolutionValidator.parseResolution(s1)
        val res2 = ResolutionValidator.parseResolution(s2)

        if (res1 == null || res2 == null) return s1.compareTo(s2)

        return if (res1.width == res2.width) {
            res1.height.compareTo(res2.height)
        } else {
            res1.width.compareTo(res2.width)
        }
    }
}

/**
 * 自定义分辨率适配器
 */
class CustomResolutionsAdapter(private val context: Context) : BaseAdapter() {
    private val resolutions = ArrayList<String>()
    private var listener: EventListener? = null

    fun setOnDataChangedListener(listener: EventListener) {
        this.listener = listener
    }

    override fun notifyDataSetChanged() {
        listener?.onTrigger()
        super.notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: createListItemView()
        setupListItemView(view, position)
        return view
    }

    private fun createListItemView(): View {
        val row = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            orientation = LinearLayout.HORIZONTAL
        }

        val listItemText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dpToPx(8)
            }
            textSize = 16f
        }

        val deleteButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }

        row.addView(listItemText)
        row.addView(deleteButton)

        return row
    }

    private fun setupListItemView(convertView: View, position: Int) {
        val row = convertView as LinearLayout
        val listItemText = row.getChildAt(0) as TextView
        val deleteButton = row.getChildAt(1) as ImageButton

        listItemText.text = resolutions[position]

        deleteButton.setOnClickListener {
            resolutions.removeAt(position)
            notifyDataSetChanged()
        }
    }

    override fun getCount(): Int = resolutions.size

    override fun getItem(position: Int): Any = resolutions[position]

    override fun getItemId(position: Int): Long = position.toLong()

    fun addItem(value: String) {
        if (!resolutions.contains(value)) {
            resolutions.add(value)
            notifyDataSetChanged()
        }
    }

    fun getAll(): ArrayList<String> = ArrayList(resolutions)

    fun addAll(list: ArrayList<String>) {
        resolutions.addAll(list)
        notifyDataSetChanged()
    }

    fun exists(item: String): Boolean = resolutions.contains(item)

    private fun dpToPx(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}
