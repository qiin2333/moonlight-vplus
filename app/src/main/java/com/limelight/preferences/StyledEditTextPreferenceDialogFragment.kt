package com.limelight.preferences

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import com.limelight.R
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.UiHelper

class StyledEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppDialogStyle)
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        view.findViewById<EditText>(android.R.id.edit)?.let(AppDialogStyler::styleTextInput)
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val alert = dialog as? AlertDialog ?: return
        AppDialogStyler.tintTitle(alert, requireContext())
        AppDialogStyler.installDismissKeys(alert)
        tintDialogButtons(alert)
    }

    private fun tintDialogButtons(dialog: AlertDialog) {
        val accentColor = UiHelper.accentColor(requireContext())
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { buttonId ->
                dialog.getButton(buttonId)?.setTextColor(accentColor)
            }
    }

    companion object {
        fun newInstance(key: String): StyledEditTextPreferenceDialogFragment {
            val fragment = StyledEditTextPreferenceDialogFragment()
            val args = Bundle(1)
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}
