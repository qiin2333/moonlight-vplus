package com.limelight.gamemenu

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.R

internal object TouchPointerPresetEditor {
    data class FieldOption(
        val field: TouchPointerPresetField,
        val label: String,
        val value: String,
        val checked: Boolean
    )

    fun show(
        context: Context,
        title: String,
        initialName: String,
        fields: List<FieldOption>,
        onSave: (String, Set<TouchPointerPresetField>) -> TouchPointerPresetSaveResult
    ): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val nameInput = EditText(context).apply {
            setText(initialName)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(TouchPointerSensitivityPolicy.MAX_NAME_LENGTH))
            hint = context.getString(R.string.game_menu_touch_pointer_preset_name_hint)
        }
        val fieldChecks = fields.associateWith { option ->
            CheckBox(context).apply {
                text = context.getString(
                    R.string.game_menu_touch_pointer_preset_field_value,
                    option.label,
                    option.value
                )
                isChecked = option.checked
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(TextView(context).apply {
                text = context.getString(R.string.game_menu_touch_pointer_preset_name)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
            addView(TextView(context).apply {
                text = context.getString(R.string.game_menu_touch_pointer_preset_fields)
                setTypeface(typeface, Typeface.BOLD)
            })
            fieldChecks.values.forEach { checkBox ->
                addView(checkBox, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = AlertDialog.Builder(context, R.style.AppDialogStyle)
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
                val selected = fieldChecks
                    .filterValues(CheckBox::isChecked)
                    .keys
                    .mapTo(linkedSetOf(), FieldOption::field)
                when (onSave(name, selected)) {
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
            fieldChecks.values.firstOrNull()?.post {
                fieldChecks.values.firstOrNull()?.requestFocus()
            }
        }
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            gravity = Gravity.CENTER
        }
        return dialog
    }
}
