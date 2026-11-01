package com.limelight.unlock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.utils.AppDialogStyler
import java.util.Arrays
import javax.crypto.Cipher

/**
 * PIN enrollment and biometric prompts for host unlock, shared by the host list and the stream.
 * Callbacks that receive a PIN take ownership of it and must wipe it.
 */
class HostUnlockUi(
    private val activity: Activity,
    private val onOverlayVisibilityChanged: () -> Unit = {}
) {
    enum class Availability { AVAILABLE, NOT_ENROLLED, UNSUPPORTED }

    private var cancellationSignal: CancellationSignal? = null
        set(value) {
            field = value
            onOverlayVisibilityChanged()
        }
    private var activeDialog: AlertDialog? = null
        set(value) {
            field = value
            onOverlayVisibilityChanged()
        }

    /** True while a PIN dialog or biometric prompt owns input, so keys must not reach the host. */
    val isOverlayVisible: Boolean
        get() = activeDialog != null || cancellationSignal != null

    /** Shows a toast and returns false when biometrics cannot protect the PIN on this device. */
    fun ensureAvailable(): Boolean = when (availability(activity)) {
        Availability.AVAILABLE -> true
        Availability.NOT_ENROLLED -> {
            toast(R.string.host_unlock_not_enrolled)
            false
        }
        Availability.UNSUPPORTED -> {
            toast(R.string.host_unlock_unsupported)
            false
        }
    }

    /** Asks for the host PIN, encrypts it behind a fingerprint and hands the PIN to [onSaved]. */
    fun showSetup(hostUuid: String, hostName: String, onSaved: (CharArray) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !ensureAvailable()) return

        val builder = AlertDialog.Builder(activity, R.style.AppDialogStyle)
        val dialogContext = builder.context
        val density = dialogContext.resources.displayMetrics.density
        val input = EditText(dialogContext).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(HostUnlockPin.MAX_LENGTH))
            isSingleLine = true
            hint = dialogContext.getString(R.string.host_unlock_setup_hint)
            setTextColor(ContextCompat.getColor(dialogContext, R.color.app_dialog_text_primary))
            setHintTextColor(ContextCompat.getColor(dialogContext, R.color.app_dialog_text_secondary))
        }
        val container = LinearLayout(dialogContext).apply {
            val horizontal = (20 * density).toInt()
            setPadding(horizontal, (8 * density).toInt(), horizontal, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = builder
            .setTitle(R.string.host_unlock_setup_title)
            .setMessage(activity.getString(R.string.host_unlock_setup_message, hostName))
            .setView(container)
            .setPositiveButton(R.string.host_unlock_setup_save, null)
            .setNegativeButton(R.string.dialog_button_cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnDismissListener {
            input.text?.clear()
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text
                val pin = CharArray(text.length) { text[it] }
                text.clear()
                if (!HostUnlockPin.isValid(pin)) {
                    Arrays.fill(pin, '\u0000')
                    input.error = activity.getString(
                        R.string.host_unlock_setup_invalid,
                        HostUnlockPin.MIN_LENGTH,
                        HostUnlockPin.MAX_LENGTH
                    )
                    return@setOnClickListener
                }
                dialog.dismiss()
                encryptAndSave(hostUuid, hostName, pin, onSaved)
            }
            input.requestFocus()
            dialog.window?.let { window ->
                WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
            }
        }
        activeDialog = dialog
        dialog.show()
        AppDialogStyler.apply(dialog, activity)
    }

    /**
     * Verifies the fingerprint and hands the stored PIN to [onPin]. Returns false without
     * prompting when no usable PIN is stored, e.g. after biometric enrollment changed.
     */
    fun authenticateStoredPin(
        hostUuid: String,
        hostName: String,
        description: String,
        onPin: (CharArray) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val cipher = try {
            HostUnlockCredentialStore.createDecryptCipher(activity, hostUuid)
        } catch (_: KeyPermanentlyInvalidatedException) {
            HostUnlockCredentialStore.clearAll(activity)
            toast(R.string.host_unlock_key_invalidated)
            return false
        } catch (e: Exception) {
            LimeLog.warning("Host unlock: failed to prepare decryption: $e")
            toast(R.string.host_unlock_error)
            return false
        } ?: return false

        authenticate(cipher, hostName, description) { authenticated ->
            val pin = HostUnlockCredentialStore.decrypt(activity, hostUuid, authenticated)
            if (pin == null) {
                HostUnlockCredentialStore.remove(activity, hostUuid)
                toast(R.string.host_unlock_error)
            } else {
                onPin(pin)
            }
        }
        return true
    }

    /** Asks before typing a freshly saved PIN when the host lock state is unknown. */
    fun confirmUnlockNow(pin: CharArray, onConfirm: (CharArray) -> Unit) {
        var consumed = false
        val dialog = AlertDialog.Builder(activity, R.style.AppDialogStyle)
            .setTitle(R.string.host_unlock_saved_title)
            .setMessage(R.string.host_unlock_saved_message)
            .setPositiveButton(R.string.host_unlock_now) { _, _ ->
                consumed = true
                onConfirm(pin)
            }
            .setNegativeButton(R.string.host_unlock_later, null)
            .create()
        dialog.setOnDismissListener {
            if (!consumed) Arrays.fill(pin, '\u0000')
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
        AppDialogStyler.apply(dialog, activity)
    }

    fun showStillLocked(onReset: () -> Unit) {
        val dialog = AlertDialog.Builder(activity, R.style.AppDialogStyle)
            .setTitle(R.string.host_unlock_still_locked_title)
            .setMessage(R.string.host_unlock_still_locked_message)
            .setPositiveButton(R.string.host_unlock_reset_pin) { _, _ -> onReset() }
            .setNegativeButton(R.string.host_unlock_close, null)
            .create()
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
        AppDialogStyler.apply(dialog, activity)
    }

    /** Dismisses a pending biometric prompt, e.g. because the host was unlocked elsewhere. */
    fun cancelPrompt() {
        cancellationSignal?.cancel()
    }

    fun release() {
        cancellationSignal?.cancel()
        cancellationSignal = null
        activeDialog?.dismiss()
        activeDialog = null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun encryptAndSave(
        hostUuid: String,
        hostName: String,
        pin: CharArray,
        onSaved: (CharArray) -> Unit
    ) {
        val cipher = try {
            HostUnlockCredentialStore.createEncryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            HostUnlockCredentialStore.clearAll(activity)
            runCatching { HostUnlockCredentialStore.createEncryptCipher() }.getOrNull()
        } catch (e: Exception) {
            LimeLog.warning("Host unlock: failed to prepare encryption: $e")
            null
        }
        if (cipher == null) {
            Arrays.fill(pin, '\u0000')
            toast(R.string.host_unlock_error)
            return
        }

        authenticate(
            cipher,
            hostName,
            activity.getString(R.string.host_unlock_prompt_setup_description),
            onCancel = { Arrays.fill(pin, '\u0000') }
        ) { authenticated ->
            val saved = runCatching {
                HostUnlockCredentialStore.save(activity, hostUuid, authenticated, pin)
            }.getOrElse {
                LimeLog.warning("Host unlock: failed to save PIN: $it")
                false
            }
            if (saved) {
                onSaved(pin)
            } else {
                Arrays.fill(pin, '\u0000')
                toast(R.string.host_unlock_error)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun authenticate(
        cipher: Cipher,
        hostName: String,
        description: String,
        onCancel: () -> Unit = {},
        onSuccess: (Cipher) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val signal = CancellationSignal()
        cancellationSignal = signal

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.host_unlock_prompt_title))
            .setSubtitle(hostName)
            .setDescription(description)
            .setNegativeButton(activity.getString(R.string.dialog_button_cancel), executor) { _, _ ->
                if (cancellationSignal === signal) cancellationSignal = null
                onCancel()
            }
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setConfirmationRequired(false)
            }
            .build()

        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            signal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cancellationSignal === signal) cancellationSignal = null
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated == null) {
                        onCancel()
                        toast(R.string.host_unlock_error)
                        return
                    }
                    onSuccess(authenticated)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (cancellationSignal === signal) cancellationSignal = null
                    onCancel()
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                        !errString.isNullOrEmpty()
                    ) {
                        Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun availability(context: Context): Availability = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> availabilityR(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> availabilityQ(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> availabilityP(context)
            else -> Availability.UNSUPPORTED
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun availabilityR(context: Context): Availability {
            val manager = context.getSystemService(BiometricManager::class.java)
                ?: return Availability.UNSUPPORTED
            return mapBiometricResult(
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            )
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        @Suppress("DEPRECATION")
        private fun availabilityQ(context: Context): Availability {
            val manager = context.getSystemService(BiometricManager::class.java)
                ?: return Availability.UNSUPPORTED
            return mapBiometricResult(manager.canAuthenticate())
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun mapBiometricResult(result: Int): Availability = when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> Availability.UNSUPPORTED
        }

        @RequiresApi(Build.VERSION_CODES.P)
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        private fun availabilityP(context: Context): Availability {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                return Availability.UNSUPPORTED
            }
            val fingerprint = context.getSystemService(FingerprintManager::class.java)
                ?: return Availability.UNSUPPORTED
            return when {
                !fingerprint.isHardwareDetected -> Availability.UNSUPPORTED
                !fingerprint.hasEnrolledFingerprints() -> Availability.NOT_ENROLLED
                else -> Availability.AVAILABLE
            }
        }
    }
}
