package com.limelight.preferences

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.limelight.R
import com.limelight.utils.AppDialogStyler

class StyledListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var clickedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppDialogStyle)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val pref = preference as? ListPreference ?: return
        val entries = pref.entries ?: return

        clickedIndex = pref.findIndexOfValue(pref.value)
        val adapter = object : ArrayAdapter<CharSequence>(
            requireContext(),
            android.R.layout.select_dialog_singlechoice,
            entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<TextView>(android.R.id.text1)?.setTextColor(
                    ContextCompat.getColor(context, R.color.app_dialog_text_primary)
                )
                return view
            }
        }
        builder.setSingleChoiceItems(adapter, clickedIndex) { dialog, which ->
            clickedIndex = which
            onClick(dialog, AlertDialog.BUTTON_POSITIVE)
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute)
        val alert = dialog as? AlertDialog ?: return
        AppDialogStyler.tintTitle(alert, requireContext())
        tintDialogButtons(alert)
        styleList(alert.listView)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val pref = preference as? ListPreference ?: return
        if (positiveResult && clickedIndex >= 0) {
            val value = pref.entryValues[clickedIndex].toString()
            if (pref.callChangeListener(value)) {
                pref.value = value
            }
        }
    }

    private fun tintDialogButtons(dialog: AlertDialog) {
        val accentColor = ContextCompat.getColor(requireContext(), R.color.app_dialog_accent_color)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { buttonId ->
                dialog.getButton(buttonId)?.setTextColor(accentColor)
            }
    }

    private fun styleList(listView: ListView?) {
        listView ?: return
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.cacheColorHint = Color.TRANSPARENT
        listView.divider = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.app_dialog_outline))
        listView.dividerHeight = dpToPx(1)
        ContextCompat.getDrawable(requireContext(), R.drawable.app_dialog_list_item_bg)?.let {
            listView.selector = it
        }
    }

    private fun dpToPx(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    companion object {
        fun newInstance(key: String): StyledListPreferenceDialogFragment {
            val fragment = StyledListPreferenceDialogFragment()
            val args = Bundle(1)
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}
