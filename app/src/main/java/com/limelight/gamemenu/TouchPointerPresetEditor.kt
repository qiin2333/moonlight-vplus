package com.limelight.gamemenu

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.utils.AppDialogStyler

internal object TouchPointerPresetEditor {
    internal class CodePointLengthFilter(private val maxCodePoints: Int) : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: android.text.Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val retainedDestCodePoints = Character.codePointCount(dest, 0, dest.length) -
                Character.codePointCount(dest, dstart, dend)
            val availableCodePoints = maxCodePoints - retainedDestCodePoints
            if (availableCodePoints <= 0) return ""

            val sourceCodePoints = Character.codePointCount(source, start, end)
            if (sourceCodePoints <= availableCodePoints) return null

            val keepEnd = Character.offsetByCodePoints(source, start, availableCodePoints)
            return source.subSequence(start, keepEnd)
        }
    }

    data class FieldOption(
        val field: TouchPointerPresetField,
        val label: String,
        val value: String,
        val checked: Boolean
    )

    private data class NumericSpec(
        val min: Int,
        val max: Int,
        val keyStep: Int,
        val suffix: String
    )

    private data class FieldControl(
        val option: FieldOption,
        val includeCheckBox: CheckBox,
        val valueViews: List<View>,
        val container: View,
        val readValue: () -> String
    )

    fun show(
        context: Context,
        title: String,
        initialName: String,
        fields: List<FieldOption>,
        onSave: (String, Map<TouchPointerPresetField, String>) -> TouchPointerPresetSaveResult
    ): Dialog {
        val builder = AlertDialog.Builder(context, R.style.AppDialogStyle)
        val dialogContext = builder.context
        val density = dialogContext.resources.displayMetrics.density
        val primaryTextColor = ContextCompat.getColor(dialogContext, R.color.app_dialog_text_primary)
        val secondaryTextColor = ContextCompat.getColor(dialogContext, R.color.app_dialog_text_secondary)
        fun dp(value: Int): Int = (value * density).toInt()

        val nameInput = EditText(dialogContext).apply {
            id = View.generateViewId()
            setText(initialName)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(
                CodePointLengthFilter(TouchPointerSensitivityPolicy.MAX_NAME_LENGTH)
            )
            hint = dialogContext.getString(R.string.game_menu_touch_pointer_preset_name_hint)
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        val fieldControls = fields.map { option ->
            val includeCheckBox = CheckBox(dialogContext).apply {
                id = View.generateViewId()
                text = option.label
                isChecked = option.checked
                setTextColor(primaryTextColor)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            val container = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(includeCheckBox, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            numericSpec(option.field)?.let { spec ->
                val initialValue = option.value.toIntOrNull()?.coerceIn(spec.min, spec.max)
                    ?: spec.min
                val valueText = TextView(dialogContext).apply {
                    setTextColor(secondaryTextColor)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                }
                val seekBar = SeekBar(dialogContext).apply {
                    id = View.generateViewId()
                    max = spec.max - spec.min
                    progress = initialValue - spec.min
                    keyProgressIncrement = spec.keyStep
                    contentDescription = option.label
                }
                fun updateValueText() {
                    valueText.text = "${spec.min + seekBar.progress}${spec.suffix}"
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) = updateValueText()

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
                updateValueText()

                container.addView(LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(38), 0, dp(4), dp(8))
                    addView(seekBar, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ))
                    addView(valueText, LinearLayout.LayoutParams(
                        dp(76),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ))
                })
                return@map FieldControl(
                    option = option,
                    includeCheckBox = includeCheckBox,
                    valueViews = listOf(seekBar),
                    container = container,
                    readValue = { (spec.min + seekBar.progress).toString() }
                )
            }

            val leftButton = RadioButton(dialogContext).apply {
                id = View.generateViewId()
                text = dialogContext.getString(R.string.game_menu_touch_pointer_side_left)
                setTextColor(primaryTextColor)
            }
            val rightButton = RadioButton(dialogContext).apply {
                id = View.generateViewId()
                text = dialogContext.getString(R.string.game_menu_touch_pointer_side_right)
                setTextColor(primaryTextColor)
            }
            val initialLeft = option.value.toBooleanStrictOrNull() ?: false
            RadioGroup(dialogContext).apply {
                orientation = RadioGroup.HORIZONTAL
                setPaddingRelative(dp(38), 0, dp(4), dp(8))
                addView(leftButton, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ))
                addView(rightButton, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ))
                check(if (initialLeft) leftButton.id else rightButton.id)
                container.addView(this)
            }
            leftButton.nextFocusRightId = rightButton.id
            rightButton.nextFocusLeftId = leftButton.id
            FieldControl(
                option = option,
                includeCheckBox = includeCheckBox,
                valueViews = listOf(leftButton, rightButton),
                container = container,
                readValue = { leftButton.isChecked.toString() }
            )
        }
        configureFocusGraph(nameInput, fieldControls)
        val content = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(TextView(dialogContext).apply {
                text = dialogContext.getString(R.string.game_menu_touch_pointer_preset_name)
                setTextColor(primaryTextColor)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
            addView(TextView(dialogContext).apply {
                text = dialogContext.getString(R.string.game_menu_touch_pointer_preset_fields)
                setTextColor(primaryTextColor)
                setTypeface(typeface, Typeface.BOLD)
            })
            fieldControls.forEach { control ->
                addView(control.container, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        val scrollView = ScrollView(dialogContext).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = builder
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(R.string.dialog_button_save, null)
            .setNegativeButton(R.string.dialog_button_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                if (!TouchPointerSensitivityPolicy.isValidName(name)) {
                    nameInput.error = context.getString(
                        R.string.game_menu_touch_pointer_preset_name_error,
                        TouchPointerSensitivityPolicy.MAX_NAME_LENGTH
                    )
                    return@setOnClickListener
                }
                val selectedValues = linkedMapOf<TouchPointerPresetField, String>()
                fieldControls.filter { it.includeCheckBox.isChecked }.forEach { control ->
                    selectedValues[control.option.field] = control.readValue()
                }
                when (onSave(name, selectedValues)) {
                    TouchPointerPresetSaveResult.SAVED -> dialog.dismiss()
                    TouchPointerPresetSaveResult.INVALID_NAME -> {
                        nameInput.error = context.getString(
                            R.string.game_menu_touch_pointer_preset_name_error,
                            TouchPointerSensitivityPolicy.MAX_NAME_LENGTH
                        )
                    }
                    TouchPointerPresetSaveResult.NO_FIELDS -> Toast.makeText(
                        context,
                        R.string.game_menu_touch_pointer_preset_no_fields,
                        Toast.LENGTH_SHORT
                    ).show()
                    TouchPointerPresetSaveResult.LIMIT_REACHED -> Toast.makeText(
                        context,
                        R.string.game_menu_touch_pointer_preset_limit,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            fieldControls.lastOrNull()?.valueViews?.forEach { valueView ->
                valueView.nextFocusDownId = positiveButton.id
            }
            positiveButton.nextFocusUpId = fieldControls.lastOrNull()
                ?.valueViews?.lastOrNull()?.id ?: nameInput.id
            negativeButton.nextFocusUpId = positiveButton.nextFocusUpId
            negativeButton.nextFocusRightId = positiveButton.id
            positiveButton.nextFocusLeftId = negativeButton.id

            fieldControls.firstOrNull()?.includeCheckBox?.post {
                fieldControls.firstOrNull()?.includeCheckBox?.requestFocus()
            }
        }
        dialog.show()
        AppDialogStyler.apply(dialog, dialogContext)
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            gravity = Gravity.CENTER
        }
        return dialog
    }

    private fun numericSpec(field: TouchPointerPresetField): NumericSpec? = when (field) {
        TouchPointerPresetField.POINTER_SPEED -> NumericSpec(
            min = TouchPointerSensitivityPolicy.MIN_PERCENT,
            max = TouchPointerSensitivityPolicy.MAX_PERCENT,
            keyStep = TouchPointerSensitivityPolicy.DPAD_STEP_PERCENT,
            suffix = "%"
        )
        TouchPointerPresetField.INITIAL_STABLE_ZONE -> NumericSpec(
            min = TouchPointerSensitivityPolicy.MIN_STABLE_ZONE_PIXELS,
            max = TouchPointerSensitivityPolicy.MAX_STABLE_ZONE_PIXELS,
            keyStep = 1,
            suffix = " px"
        )
        TouchPointerPresetField.ZONE_DIVIDER -> NumericSpec(
            min = TouchPointerSensitivityPolicy.MIN_ZONE_DIVIDER,
            max = TouchPointerSensitivityPolicy.MAX_ZONE_DIVIDER,
            keyStep = 1,
            suffix = "%"
        )
        TouchPointerPresetField.POINTER_ZONE_SIDE -> null
    }

    private fun configureFocusGraph(
        nameInput: EditText,
        fieldControls: List<FieldControl>
    ) {
        val firstCheckBox = fieldControls.firstOrNull()?.includeCheckBox ?: return
        nameInput.nextFocusDownId = firstCheckBox.id
        fieldControls.forEachIndexed { index, control ->
            val previousValue = fieldControls.getOrNull(index - 1)?.valueViews?.lastOrNull()
            val nextCheckBox = fieldControls.getOrNull(index + 1)?.includeCheckBox
            control.includeCheckBox.nextFocusUpId = previousValue?.id ?: nameInput.id
            control.includeCheckBox.nextFocusDownId = control.valueViews.first().id
            control.valueViews.forEach { valueView ->
                valueView.nextFocusUpId = control.includeCheckBox.id
                nextCheckBox?.let { valueView.nextFocusDownId = it.id }
            }
        }
    }
}
