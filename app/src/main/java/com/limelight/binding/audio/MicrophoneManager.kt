package com.limelight.binding.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.Toast

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.MicrophoneInitialState
import com.limelight.preferences.PreferenceConfiguration

class MicrophoneManager(
    private val context: Context,
    private val connection: NvConnection?,
    private var enableMic: Boolean,
    private val hostUuid: String? = null,
) {
    private var microphoneStream: MicrophoneStream? = null
    private var micButton: ImageButton? = null
    private val buttonPreferences = MicrophoneButtonPreferences(context)
    private val initialStateStore = MicrophoneInitialStateStore(context)
    private var pendingInitialStart = false
    private var lifecycleGeneration = 0L
    private var permissionRequestGeneration: Long? = null
    private var permissionRequestStartsInitial = false

    interface MicrophoneStateListener {
        fun onMicrophoneStateChanged(isActive: Boolean)
        fun onPermissionRequested()
    }

    private var stateListener: MicrophoneStateListener? = null

    fun setStateListener(listener: MicrophoneStateListener?) {
        this.stateListener = listener
    }

    fun setMicrophoneButton(button: ImageButton?) {
        this.micButton = button
        setupMicrophoneButton()
    }

    fun initializeMicrophoneStream(): Boolean {
        if (!enableMic) {
            LimeLog.info("麦克风功能已禁用")
            return false
        }

        val activeConnection = connection ?: run {
            showMessage(context.getString(R.string.mic_error_no_connection))
            return false
        }

        if (microphoneStream != null) {
            LimeLog.info("麦克风流已存在")
            return true
        }

        if (!hasMicrophonePermission()) {
            showMessage(context.getString(R.string.mic_permission_required))
            return false
        }

        try {
            MicrophoneConfig.updateBitrateFromConfig(context)
            MicrophoneConfig.updateVolumeProcessingFromConfig(context)
            microphoneStream = MicrophoneStream(activeConnection)

            if (!microphoneStream!!.start()) {
                microphoneStream?.stop()
                microphoneStream = null
                showMessage(context.getString(R.string.mic_error_start))
                return false
            }

            LimeLog.info("麦克风流启动成功")

            if (!microphoneStream!!.isMicrophoneAvailable()) {
                showMessage(context.getString(R.string.mic_error_host_unsupported))
            }

            if (microphoneStream!!.isRunning()) {
                microphoneStream!!.pause()
                LimeLog.info("麦克风流已初始化，默认状态为关闭")
            }

            return true
        } catch (e: Exception) {
            microphoneStream?.stop()
            microphoneStream = null
            LimeLog.warning(context.getString(R.string.mic_error_init_detail, e.message.orEmpty()))
            showMessage(context.getString(R.string.mic_error_init_detail, e.message.orEmpty()))
            return false
        }
    }

    /** Applies the configured initial state after the stream handshake has completed. */
    fun applyInitialState(initialState: MicrophoneInitialState) {
        if (!enableMic || !MoonBridge.isMicrophoneRequested()) {
            pendingInitialStart = false
            updateMicrophoneButtonState()
            return
        }

        if (!initialState.resolve(initialStateStore.load(hostUuid))) {
            pendingInitialStart = false
            setDefaultStateOff()
            return
        }

        pendingInitialStart = true
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission(forInitialStart = true)
            return
        }
        startPendingInitialState()
    }

    private fun startPendingInitialState() {
        if (!pendingInitialStart) return
        if (!enableMic || !MoonBridge.isMicrophoneRequested() || !hasMicrophonePermission()) {
            pendingInitialStart = false
            updateMicrophoneButtonState()
            return
        }

        if (!initializeMicrophoneStream()) {
            pendingInitialStart = false
            updateMicrophoneButtonState()
            return
        }

        pendingInitialStart = false
        resumeMicrophone(persistState = false)
    }

    fun toggleMicrophone() {
        if (!checkMicrophonePermission()) return

        if (microphoneStream != null) {
            if (microphoneStream!!.isRunning()) {
                pauseMicrophone()
            } else {
                resumeMicrophone()
            }
        } else if (connection != null) {
            if (initializeMicrophoneStream()) {
                resumeMicrophone()
            } else {
                showMessage(context.getString(R.string.mic_error_init))
            }
        } else {
            showMessage(context.getString(R.string.mic_error_no_connection))
        }

        updateMicrophoneButtonState()
    }

    fun pauseMicrophone() {
        if (microphoneStream != null && microphoneStream!!.isRunning()) {
            microphoneStream!!.pause()
            showMessage(context.getString(R.string.mic_disabled))
            notifyStateChange(false)
            updateMicrophoneButtonState()
        }
    }

    fun resumeMicrophone(persistState: Boolean = true) {
        if (!checkMicrophonePermission()) return

        if (microphoneStream != null && !microphoneStream!!.isRunning()) {
            if (microphoneStream!!.resume()) {
                showMessage(context.getString(R.string.mic_enabled))
                notifyStateChange(true, persistState)
                updateMicrophoneButtonState()
            } else {
                restartMicrophoneStream(persistState)
            }
        }
    }

    private fun restartMicrophoneStream(persistState: Boolean) {
        LimeLog.warning("麦克风恢复失败，尝试重新初始化")
        microphoneStream!!.stop()
        MicrophoneConfig.updateBitrateFromConfig(context)
        MicrophoneConfig.updateVolumeProcessingFromConfig(context)

        val activeConnection = connection ?: return
        microphoneStream = MicrophoneStream(activeConnection)
        if (microphoneStream!!.start()) {
            showMessage(context.getString(R.string.mic_enabled))
            notifyStateChange(true, persistState)
            updateMicrophoneButtonState()
        } else {
            showMessage(context.getString(R.string.mic_error_resume))
        }
    }

    private fun notifyStateChange(isActive: Boolean, persistState: Boolean = true) {
        if (persistState) {
            initialStateStore.save(hostUuid, isActive)
        }
        stateListener?.onMicrophoneStateChanged(isActive)
    }

    fun isMicrophoneActive(): Boolean = microphoneStream != null && microphoneStream!!.isRunning()

    fun isMicrophoneAvailable(): Boolean = microphoneStream != null && microphoneStream!!.isMicrophoneAvailable()

    fun isMicrophoneTrulyAvailable(): Boolean {
        return hasMicrophonePermission() &&
                microphoneStream != null &&
                microphoneStream!!.isMicrophoneAvailable()
    }

    fun checkMicrophonePermission(): Boolean {
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            return false
        }
        return true
    }

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun requestMicrophonePermission(forInitialStart: Boolean = false) {
        permissionRequestGeneration = lifecycleGeneration
        permissionRequestStartsInitial = forInitialStart
        if (context is android.app.Activity) {
            ActivityCompat.requestPermissions(context,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                MicrophoneConfig.PERMISSION_REQUEST_MICROPHONE)
            stateListener?.onPermissionRequested()
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == MicrophoneConfig.PERMISSION_REQUEST_MICROPHONE &&
            grantResults.isNotEmpty()) {
            val requestGeneration = permissionRequestGeneration ?: return
            val startsInitial = permissionRequestStartsInitial
            permissionRequestGeneration = null
            permissionRequestStartsInitial = false
            if (requestGeneration != lifecycleGeneration) return

            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (requestGeneration != lifecycleGeneration) return@postDelayed
                    if (hasMicrophonePermission()) {
                        if (startsInitial) {
                            if (pendingInitialStart) {
                                startPendingInitialState()
                            }
                        } else {
                            toggleMicrophone()
                        }
                    } else {
                        showPermissionError()
                    }
                }, MicrophoneConfig.PERMISSION_DELAY_MS.toLong())
            } else {
                pendingInitialStart = false
                showPermissionError()
            }
        }
    }

    private fun setupMicrophoneButton() {
        val button = micButton ?: return

        val showButton = enableMic && buttonPreferences.isButtonShownByDefault()

        button.visibility = if (showButton) View.VISIBLE else View.GONE
        if (showButton) {
            updateMicrophoneButtonState()
            button.setOnClickListener {
                if (checkMicrophonePermission()) {
                    toggleMicrophone()
                }
            }
        }
    }

    fun updateMicrophoneButtonState() {
        val button = micButton ?: return

        val isActive = isMicrophoneActive()
        button.isSelected = isActive

        val iconResource = getMicIconResource(isActive)
        button.setImageResource(iconResource)

        button.contentDescription = context.getString(
            if (isActive) R.string.mic_enabled else R.string.mic_disabled
        )
        button.isEnabled = true
    }

    private fun getMicIconResource(isActive: Boolean): Int {
        val prefConfig = PreferenceConfiguration.readPreferences(context)
        val colorScheme = prefConfig.micIconColor ?: "solid_white"

        val resourceName = if (isActive)
            getEnabledIconResourceName(colorScheme)
        else
            getDisabledIconResourceName(colorScheme)

        return context.resources.getIdentifier(resourceName, "drawable", context.packageName)
    }

    private fun getEnabledIconResourceName(colorScheme: String): String {
        return when (colorScheme) {
            "gradient_blue" -> "ic_btn_mic_gradient_blue"
            "gradient_purple" -> "ic_btn_mic_gradient_purple"
            "gradient_green" -> "ic_btn_mic_gradient_green"
            "gradient_orange" -> "ic_btn_mic_gradient_orange"
            "gradient_red" -> "ic_btn_mic_gradient_red"
            else -> "ic_btn_mic"
        }
    }

    private fun getDisabledIconResourceName(colorScheme: String): String {
        return when (colorScheme) {
            "gradient_blue" -> "ic_btn_mic_gradient_blue_disabled"
            "gradient_purple" -> "ic_btn_mic_gradient_purple_disabled"
            "gradient_green" -> "ic_btn_mic_gradient_green_disabled"
            "gradient_orange" -> "ic_btn_mic_gradient_orange_disabled"
            "gradient_red" -> "ic_btn_mic_gradient_red_disabled"
            else -> "ic_btn_mic_disabled"
        }
    }

    fun stopMicrophoneStream() {
        lifecycleGeneration++
        pendingInitialStart = false
        microphoneStream?.stop()
        microphoneStream = null
    }

    private fun showPermissionError() {
        showMessage(context.getString(R.string.mic_permission_required))
    }

    private fun showMessage(message: String) {
        if (context is android.app.Activity) {
            context.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun testMicrophoneStatus() {
        val status = String.format("权限: %s, 流存在: %s, 可用: %s, 激活: %s",
            hasMicrophonePermission(),
            microphoneStream != null,
            isMicrophoneAvailable(),
            isMicrophoneActive())

        LimeLog.info("麦克风状态: $status")
        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }

    fun setEnableMic(enable: Boolean) {
        this.enableMic = enable

        if (enable) {
            MicrophoneConfig.updateBitrateFromConfig(context)
            MicrophoneConfig.updateVolumeProcessingFromConfig(context)
        }

        if (micButton != null) {
            setupMicrophoneButton()
        }
    }

    fun getMicrophoneStream(): MicrophoneStream? = microphoneStream

    fun setDefaultStateOff() {
        if (microphoneStream != null && microphoneStream!!.isRunning()) {
            microphoneStream!!.pause()
            LimeLog.info("麦克风已设置为默认关闭状态")
        }
        updateMicrophoneButtonState()
    }

    companion object {
        private const val TAG = "MicrophoneManager"
    }
}
