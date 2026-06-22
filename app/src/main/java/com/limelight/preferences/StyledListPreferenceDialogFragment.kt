package com.limelight.preferences

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
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
            R.layout.app_dialog_single_choice_item,
            android.R.id.text1,
            entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<CheckedTextView>(android.R.id.text1)?.let { textView ->
                    AppDialogStyler.bindChoiceRow(view, textView, context, position == clickedIndex)
                }
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
        val alert = dialog as? AlertDialog ?: return
        AppDialogStyler.apply(alert, requireContext())
        AppDialogStyler.styleChoiceListContainer(alert.findViewById<ListView>(android.R.id.list), requireContext())
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
