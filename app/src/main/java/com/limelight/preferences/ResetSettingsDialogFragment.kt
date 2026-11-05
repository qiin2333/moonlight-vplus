package com.limelight.preferences

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limelight.R
import com.limelight.utils.AppDialogStyler
import kotlinx.coroutines.launch

/** Retains an in-flight reset across rotation and prevents duplicate confirmation. */
class ResetSettingsDialogFragment : DialogFragment() {
    private lateinit var model: SettingsResetViewModel

    /** Observes retained state while visible, delivering completion to the current activity instance. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SettingsResetViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val alert = dialog as? AlertDialog ?: return@collect
                    val running = state == SettingsResetViewModel.State.RUNNING
                    isCancelable = !running
                    alert.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = !running
                    alert.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = !running
                    val message = when (state) {
                        SettingsResetViewModel.State.RUNNING -> R.string.message_reset_settings_running
                        SettingsResetViewModel.State.FAILED -> R.string.message_reset_settings_failed
                        else -> R.string.message_reset_settings
                    }
                    alert.setMessage(getString(message))
                    if (state == SettingsResetViewModel.State.COMPLETE) {
                        model.consumeCompletion()
                        val host = activity as? StreamSettings
                        dismiss()
                        Toast.makeText(context, R.string.toast_reset_settings_success, Toast.LENGTH_LONG).show()
                        host?.onSettingsReset()
                    }
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext(), R.style.AppDialogStyle)
            .setTitle(R.string.title_reset_settings)
            .setMessage(R.string.message_reset_settings)
            .setPositiveButton(R.string.action_reset_settings, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    /** Binds confirmation and dismissal handlers that honor the retained running state. */
    override fun onStart() {
        super.onStart()
        val alert = requireDialog() as AlertDialog
        AppDialogStyler.apply(alert, requireContext())
        // AppDialogStyler also supports Escape / controller B; honor the in-flight state there.
        AppDialogStyler.installDismissKeys(alert, onDismiss = { if (isCancelable) dismiss() })
        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { model.reset() }
    }

    companion object {
        const val TAG = "reset_all_settings"
    }
}
