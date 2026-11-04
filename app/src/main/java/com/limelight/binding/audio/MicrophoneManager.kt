package com.limelight.binding.audio

import android.content.Context
import android.media.AudioRecord
import android.content.pm.PackageManager
import android.os.Build
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
    internal val inputDevices = MicrophoneInputDeviceRepository(context)
    private var pendingInitialStart = false
    @Volatile private var lifecycleGeneration = 0L
    private var permissionRequestGeneration: Long? = null
    private var permissionRequestStartsInitial = false
    private val handler = Handler(Looper.getMainLooper())
    private var switchingInput = false
    private var activeInput: MicrophoneActiveInput? = null
    private var pendingResumePersistState = false
    private var initialBluetoothFallbackTried = false
    @Volatile private var initialBluetoothFallbackActive = false
    @Volatile private var initialChoiceCommunication = false
    @Volatile private var inputRequestGeneration = 0L
    private val bluetoothRoute = BluetoothMicrophoneRouteController(context)

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
            microphoneStream = createMicrophoneStream(activeConnection)

            if (!microphoneStream!!.start(captureInitially = false)) {
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
        cancelInputSwitch()
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
            pendingResumePersistState = persistState
            if (microphoneStream!!.resume()) {
                updateMicrophoneButtonState()
            } else {
                restartMicrophoneStream(persistState)
            }
        }
    }

    private fun restartMicrophoneStream(
        persistState: Boolean, initialOverride: MicrophoneInputResolution? = null
    ) {
        cancelInputSwitch()
        LimeLog.warning("麦克风恢复失败，尝试重新初始化")
        microphoneStream!!.stop()
        MicrophoneConfig.updateBitrateFromConfig(context)
        MicrophoneConfig.updateVolumeProcessingFromConfig(context)

        val activeConnection = connection ?: return
        microphoneStream = createMicrophoneStream(activeConnection, initialOverride)
        pendingResumePersistState = persistState
        if (microphoneStream!!.start()) {
            updateMicrophoneButtonState()
        } else {
            cancelInputSwitch()
            microphoneStream!!.stop()
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

    internal fun selectInputDevice(key: String): Boolean {
        if (switchingInput || microphoneStream?.isInputSwitchPending() == true) return false
        val devices = inputDevices.choices()
        val choice = devices.firstOrNull { it.key == key }
        if (key != DEFAULT_MICROPHONE_INPUT && choice == null) return false
        if (!isMicrophoneActive()) {
            return selectMicrophoneInput(key, devices, false, { false }, inputDevices::saveSelection)
        }
        val stream = microphoneStream ?: return false
        val wasInputAllowed = stream.setInputAllowed(false)
        val generation = lifecycleGeneration
        val captureGeneration = stream.currentCaptureGeneration()
        val requestGeneration = ++inputRequestGeneration
        val previous = activeInput
        val isAlive = { generation == lifecycleGeneration && microphoneStream === stream &&
            stream.currentCaptureGeneration() == captureGeneration }
        val isCurrent = { isAlive() && inputRequestGeneration == requestGeneration }
        val input = captureInput(choice, previous, isAlive) {
            handler.post { if (isCurrent()) handleInputLost(stream, captureGeneration) }
        }
        switchingInput = true
        val queued = selectMicrophoneInput(key, devices, true,
            requestRoute = { complete ->
                stream.requestInput(input) { result ->
                    handler.post {
                        complete(result.takeIf { it.active == null || stream.hasConfirmedInput() }
                            ?: MicrophoneInputResult.Stopped)
                    }
                }
            },
            save = inputDevices::saveSelection,
            canComplete = isCurrent,
            onComplete = { finishInputResult(stream, it, initial = false) })
        if (!queued) {
            switchingInput = false
            inputRequestGeneration--
            stream.setInputAllowed(wasInputAllowed)
        }
        return queued
    }

    private fun captureInput(
        choice: MicrophoneInputDevice?, previous: MicrophoneActiveInput?,
        isAlive: () -> Boolean, initial: Boolean = false, routeLost: () -> Unit
    ): MicrophoneCaptureInput {
        val matchedBluetoothInput = choice?.takeIf { it.communication }?.let {
            matchingBluetoothInput(it, inputDevices.devices())
        }
        val previousBluetooth = previous?.choice?.takeIf { it.communication }
        val change = if (initial || choice?.communication == true || previousBluetooth != null) MicrophoneRouteChange(
            prepare = { done ->
                if (!isAlive()) done(false)
                else if (choice?.communication == true) bluetoothRoute.activate(choice, isAlive, routeLost, done)
                else bluetoothRoute.release { released -> done(released && isAlive()) }
            },
            rollback = { done ->
                if (!isAlive()) done(false)
                else if (previousBluetooth != null) bluetoothRoute.activate(previousBluetooth, isAlive, routeLost, done)
                else bluetoothRoute.release { released -> done(released && isAlive()) }
            },
            isReady = { choice?.communication != true || bluetoothRoute.isReady(choice) },
            restoreInput = previousBluetooth?.let { configureFor(it) },
            restoredInputId = { previous?.choice?.takeUnless { it.communication }?.id },
            recreateRecorder = true,
            communicationInput = choice?.communication == true
        ) else null
        return MicrophoneCaptureInput(
            choice,
            configureFor(choice),
            change,
            expectedCommunicationInputId = matchedBluetoothInput?.id
        )
    }

    /** setCommunicationDevice alone does not steer every OEM recording input. Classic
     *  SCO must have an unambiguous recording port or the switch fails closed. */
    private fun configureFor(choice: MicrophoneInputDevice?): (AudioRecord) -> Boolean {
        return { record ->
            val target = choice?.takeIf { it.communication }?.let {
                matchingBluetoothInput(it, inputDevices.devices())
            }
            if (choice?.communication == true && requiresBluetoothRecordingBinding(choice.type) &&
                target == null) {
                LimeLog.warning("Bluetooth microphone recording input cannot be identified; rejecting switch")
                false
            } else {
                val key = target?.key ?: choice?.key ?: DEFAULT_MICROPHONE_INPUT
                val applied = inputDevices.apply(record, key)
                if (applied && target != null && Build.VERSION.SDK_INT >= 23 &&
                    record.preferredDevice?.id != target.id) {
                    LimeLog.warning("Bluetooth microphone recording input was not retained")
                    false
                } else applied
            }
        }
    }

    private fun finishInputResult(stream: MicrophoneStream, result: MicrophoneInputResult, initial: Boolean) {
        switchingInput = false
        activeInput = result.active
        if (result.active == null) {
            stream.setInputAllowed(false)
            cancelInputSwitch()
            stream.pause()
            showMessage(context.getString(R.string.mic_input_failed))
            notifyStateChange(false, persistState = false)
        } else {
            stream.setInputAllowed(true)
            if (result is MicrophoneInputResult.Restored) showMessage(context.getString(R.string.mic_input_failed))
            else if (initial) showMessage(context.getString(R.string.mic_enabled))
            notifyStateChange(true, persistState = initial && pendingResumePersistState)
        }
        updateMicrophoneButtonState()
    }

    private fun handleInputLost(stream: MicrophoneStream, captureGeneration: Long) {
        if (microphoneStream !== stream || captureGeneration != stream.currentCaptureGeneration() ||
            switchingInput) return
        stream.setInputAllowed(false)
        LimeLog.warning("Microphone input lost; verifying system default before resuming")
        if (!stream.isRunning()) {
            restartMicrophoneStream(persistState = false, initialOverride = MicrophoneInputResolution.Default)
            return
        }
        if (!selectInputDevice(DEFAULT_MICROPHONE_INPUT)) {
            finishInputResult(stream, MicrophoneInputResult.Stopped, initial = false)
        }
    }

    internal fun isInputSwitchPending(): Boolean = switchingInput || microphoneStream?.isInputSwitchPending() == true

    private fun cancelInputSwitch() {
        lifecycleGeneration++
        switchingInput = false
        activeInput = null
        bluetoothRoute.release()
    }

    private fun createMicrophoneStream(
        activeConnection: NvConnection, initialOverride: MicrophoneInputResolution? = null
    ): MicrophoneStream {
        lateinit var stream: MicrophoneStream
        var startupOverride = initialOverride
        stream = MicrophoneStream(activeConnection,
            initialInput = input@{ captureGeneration ->
                val resolution = startupOverride ?: inputDevices.resolveSelection()
                startupOverride = null
                val choice = when (resolution) {
                    MicrophoneInputResolution.Default -> null
                    is MicrophoneInputResolution.Available -> resolution.device
                    MicrophoneInputResolution.Unavailable -> {
                        showMessage(context.getString(R.string.mic_input_unavailable))
                        return@input null
                    }
                }
                initialChoiceCommunication = choice?.communication == true
                val generation = lifecycleGeneration
                val requestGeneration = ++inputRequestGeneration
                val alive = { generation == lifecycleGeneration && microphoneStream === stream &&
                    captureGeneration == stream.currentCaptureGeneration() }
                switchingInput = true
                activeInput = null
                captureInput(choice, null, alive, initial = true) {
                    handler.post {
                        if (alive() && requestGeneration == inputRequestGeneration)
                            handleInputLost(stream, captureGeneration)
                    }
                }
            },
            onCaptureStopped = { captureGeneration ->
                val generation = lifecycleGeneration
                handler.post {
                    if (generation == lifecycleGeneration && microphoneStream === stream &&
                        captureGeneration == stream.currentCaptureGeneration() && !stream.isRunning()) {
                        if (!switchingInput && activeInput?.choice?.communication == true) {
                            handleInputLost(stream, captureGeneration)
                            return@post
                        }
                        stream.pause()
                        cancelInputSwitch()
                        updateMicrophoneButtonState()
                        notifyStateChange(false, persistState = false)
                    }
                }
            },
            onInitialResult = { captureGeneration, result ->
                val generation = lifecycleGeneration
                handler.post {
                    if (generation == lifecycleGeneration && microphoneStream === stream &&
                        captureGeneration == stream.currentCaptureGeneration()) {
                        val effective = result.takeIf { it.active == null || stream.hasConfirmedInput() }
                            ?: MicrophoneInputResult.Stopped
                        if (effective.active == null && initialChoiceCommunication &&
                            !initialBluetoothFallbackTried) {
                            initialBluetoothFallbackTried = true
                            initialBluetoothFallbackActive = true
                            showMessage(context.getString(R.string.mic_input_unavailable))
                            restartMicrophoneStream(persistState = false,
                                initialOverride = MicrophoneInputResolution.Default)
                            return@post
                        }
                        completeMicrophoneInput(effective, save = { saved ->
                            // A failed initial Bluetooth route must not publish the
                            // default as the user's saved choice.
                            if (!initialBluetoothFallbackActive) inputDevices.saveSelection(saved)
                        }) {
                            finishInputResult(stream, it, initial = true)
                        }
                        initialBluetoothFallbackActive = false
                    }
                }
            },
            onInputLost = { captureGeneration ->
                handler.post { handleInputLost(stream, captureGeneration) }
            })
        return stream
    }

    internal fun routedInputId(): Int? = microphoneStream?.routedInputId()

    internal fun isSystemSilenced(): Boolean = microphoneStream?.isSystemSilenced() == true

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
            // BLUETOOTH_CONNECT gates the HFP query that confirms a headset route on 31+.
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(android.Manifest.permission.RECORD_AUDIO)
            ActivityCompat.requestPermissions(context,
                permissions,
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
        cancelInputSwitch()
        pendingInitialStart = false
        initialBluetoothFallbackTried = false
        initialBluetoothFallbackActive = false
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
        val status = context.getString(R.string.mic_status_debug,
            hasMicrophonePermission(),
            microphoneStream != null,
            isMicrophoneAvailable(),
            isMicrophoneActive())

        LimeLog.info("麦克风状态: $status")
        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }

    fun setEnableMic(enable: Boolean) {
        this.enableMic = enable

        if (!enable) stopMicrophoneStream()

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
        cancelInputSwitch()
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
