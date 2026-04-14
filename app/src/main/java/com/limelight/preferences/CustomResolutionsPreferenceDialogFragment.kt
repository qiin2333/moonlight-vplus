package com.limelight.preferences

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

import androidx.preference.PreferenceDialogFragmentCompat

import com.limelight.R

class CustomResolutionsPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private fun getPref(): CustomResolutionsPreference =
        preference as CustomResolutionsPreference

    override fun onCreateDialogView(context: Context): View {
        val pref = getPref()
        val body = createMainLayout(context)
        val list = createListView(context, pref)
        val inputRow = createInputRow(context, pref)
        body.addView(list)
        body.addView(inputRow)
        return body
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        getPref().loadStoredResolutions()
    }

    private fun createMainLayout(context: Context): LinearLayout {
        val body = LinearLayout(context)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = minOf((screenWidth * 0.8).toInt(), dpToPx(context, 400))
        val layoutParams = LinearLayout.LayoutParams(dialogWidth, AbsListView.LayoutParams.WRAP_CONTENT)
        layoutParams.gravity = Gravity.CENTER
        body.layoutParams = layoutParams
        body.orientation = LinearLayout.VERTICAL
        val pad = dpToPx(context, 16)
        body.setPadding(pad, pad, pad, pad)
        return body
    }

    private fun createListView(context: Context, pref: CustomResolutionsPreference): ListView {
        val list = ListView(context)
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        list.layoutParams = layoutParams
        list.adapter = pref.adapter
        list.dividerHeight = dpToPx(context, 1)
        @Suppress("DEPRECATION")
        list.divider = context.resources.getDrawable(android.R.color.darker_gray)
        return list
    }

    private fun createInputRow(context: Context, pref: CustomResolutionsPreference): View {
        val inflater = LayoutInflater.from(context)
        val inputRow = inflater.inflate(R.layout.custom_resolutions_form, null)

        val widthField = inputRow.findViewById<EditText>(R.id.custom_resolution_width_field)
        val heightField = inputRow.findViewById<EditText>(R.id.custom_resolution_height_field)
        val addButton = inputRow.findViewById<Button>(R.id.add_resolution_button)

        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            AbsListView.LayoutParams.WRAP_CONTENT
        )
        layoutParams.topMargin = dpToPx(context, 16)
        inputRow.layoutParams = layoutParams

        addButton.setOnClickListener { pref.onSubmitResolution(widthField, heightField) }
        heightField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                pref.onSubmitResolution(widthField, heightField)
                true
            } else false
        }

        return inputRow
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val settingsActivity = requireActivity() as StreamSettings
        settingsActivity.reloadSettings()
    }

    companion object {
        fun newInstance(key: String): CustomResolutionsPreferenceDialogFragment {
            val fragment = CustomResolutionsPreferenceDialogFragment()
            val args = Bundle(1)
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }

        private fun dpToPx(context: Context, value: Int): Int {
            val density = context.resources.displayMetrics.density
            return (value * density + 0.5f).toInt()
        }
    }
}
