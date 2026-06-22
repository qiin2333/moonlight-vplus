package com.limelight.preferences

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.limelight.R
import com.limelight.utils.AppDialogStyler

class StyledMultiSelectListPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private val selectedValues = linkedSetOf<String>()
    private var preferenceChanged = false
    private var entries: Array<CharSequence>? = null
    private var entryValues: Array<CharSequence>? = null
    private var choiceListView: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppDialogStyle)

        if (savedInstanceState == null) {
            val pref = preference as? MultiSelectListPreference ?: return
            selectedValues.addAll(pref.values)
            entries = pref.entries
            entryValues = pref.entryValues
        } else {
            selectedValues.addAll(savedInstanceState.getStringArrayList(SAVE_STATE_VALUES).orEmpty())
            preferenceChanged = savedInstanceState.getBoolean(SAVE_STATE_CHANGED, false)
            entries = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRIES)
            entryValues = savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRY_VALUES)
        }
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val entryLabels = entries ?: return
        val values = entryValues ?: return
        val adapter = object : ArrayAdapter<CharSequence>(
            requireContext(),
            R.layout.app_dialog_multi_choice_item,
            android.R.id.text1,
            entryLabels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val checked = selectedValues.contains(values[position].toString())
                view.findViewById<CheckedTextView>(android.R.id.text1)?.let { textView ->
                    AppDialogStyler.bindChoiceRow(view, textView, context, checked)
                }
                return view
            }
        }

        val listView = ListView(requireContext()).apply {
            id = android.R.id.list
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = adapter
            setOnItemClickListener { _, _, which, _ ->
                val value = values[which].toString()
                val checked = if (selectedValues.remove(value)) {
                    false
                } else {
                    selectedValues.add(value)
                    true
                }
                preferenceChanged = true
                setItemChecked(which, checked)
                adapter.notifyDataSetChanged()
            }
        }
        choiceListView = listView

        builder.setView(listView)
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        val listView = choiceListView ?: alert.findViewById(android.R.id.list)
        entryValues?.forEachIndexed { index, value ->
            listView?.setItemChecked(index, selectedValues.contains(value.toString()))
        }
        AppDialogStyler.apply(alert, requireContext())
        AppDialogStyler.styleChoiceListContainer(listView, requireContext())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(SAVE_STATE_VALUES, ArrayList(selectedValues))
        outState.putBoolean(SAVE_STATE_CHANGED, preferenceChanged)
        outState.putCharSequenceArray(SAVE_STATE_ENTRIES, entries)
        outState.putCharSequenceArray(SAVE_STATE_ENTRY_VALUES, entryValues)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val pref = preference as? MultiSelectListPreference ?: return
        if (positiveResult && preferenceChanged) {
            val values = linkedSetOf<String>().apply {
                addAll(selectedValues)
            }
            if (pref.callChangeListener(values)) {
                pref.values = values
            }
        }
    }

    companion object {
        private const val SAVE_STATE_VALUES = "StyledMultiSelectListPreferenceDialogFragment.values"
        private const val SAVE_STATE_CHANGED = "StyledMultiSelectListPreferenceDialogFragment.changed"
        private const val SAVE_STATE_ENTRIES = "StyledMultiSelectListPreferenceDialogFragment.entries"
        private const val SAVE_STATE_ENTRY_VALUES = "StyledMultiSelectListPreferenceDialogFragment.entryValues"

        fun newInstance(key: String): StyledMultiSelectListPreferenceDialogFragment {
            val fragment = StyledMultiSelectListPreferenceDialogFragment()
            val args = Bundle(1)
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}
