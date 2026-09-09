package com.limelight.binding.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.view.KeyEvent
import android.view.Surface

import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration

/** Outcome of applying a device-gyro listener request. */
internal enum class DeviceGyroRegistrationResult {
    APPLIED,
    UNAVAILABLE,
    RETRYABLE_FAILURE,
}

/** Which local assistant, if any, consumes controller 0's gyroscope. */
enum class GyroAssistantMode {
    OFF,
    RIGHT_STICK,
    MOUSE;

    companion object {
        /** The only place that interprets the two mutually exclusive persisted flags. */
        fun from(prefConfig: PreferenceConfiguration): GyroAssistantMode = when {
            prefConfig.gyroToMouse -> MOUSE
            prefConfig.gyroToRightStick -> RIGHT_STICK
            else -> OFF
        }
    }
}

/**
 * 陀螺仪相关功能管理器
 * 处理陀螺仪到鼠标映射、陀螺仪到右摇杆映射、传感器注册与保持激活逻辑
 *
 * 状态由主线程持有并修改；传感器/驱动回调线程只读取 @Volatile 字段。
 */
class ControllerGyroManager(private val handler: ControllerHandler) {

    /** Where controller 0's gyro samples currently come from. */
    private enum class GyroSource {
        /** Nothing registered for an assistant; host demand owns the sensor. */
        NONE,
        /** Controller 0's own Android InputDevice sensor. */
        INPUT_DEVICE,
        /** A USB driver controller that pushes samples through its own callback. */
        DRIVER,
        /** The built-in device gyroscope, registered on defaultContext. */
        DEVICE,
    }

    companion object {
        const val TRIGGER_ACTIVATE_THRESHOLD = 0.2f
        const val GYRO_ACTIVATION_ALWAYS = -1000
    }

    // Gyro-to-mouse accumulator (sub-pixel remainder)
    @Volatile var gyroMouseRemainX = 0f
    @Volatile var gyroMouseRemainY = 0f
    @Volatile var gyroMouseLastTimestamp = 0L

    private val controllerGyroLivenessTracker = ControllerGyroLivenessTracker()
    private val controllerGyroDemand = ControllerGyroDemandState()

    @Volatile private var activeSource = GyroSource.NONE
    /** Sticky: controller 0's own sensor proved unusable, so never select it again. */
    @Volatile private var controllerSensorRejected = false
    /** Bumped when the controller-0 source changes, to drop stale async fallback work. */
    @Volatile private var sourceGeneration = 0L
    private var deviceGyroRetryAvailable = true


    fun applyGyroToMouse(wx: Float, wy: Float, timestamp: Long) {
        if (gyroMouseLastTimestamp == 0L) {
            gyroMouseLastTimestamp = timestamp
            return
        }
        var dt = (timestamp - gyroMouseLastTimestamp) * 1e-9f
        gyroMouseLastTimestamp = timestamp
        // Clamp dt to avoid huge jumps after sensor pauses (e.g. app backgrounded)
        if (dt > 0.05f) dt = 0.05f

        // sensitivity: pixels per radian. gyroSensitivityMultiplier scales it.
        val sensitivity = 800f * handler.prefConfig.gyroSensitivityMultiplier

        var deltaX = wx * dt * sensitivity
        var deltaY = wy * dt * sensitivity

        if (handler.prefConfig.gyroInvertXAxis) deltaX = -deltaX
        if (handler.prefConfig.gyroInvertYAxis) deltaY = -deltaY

        // Accumulate sub-pixel remainder to avoid truncation at slow speeds
        gyroMouseRemainX += deltaX
        gyroMouseRemainY += deltaY

        val sendX = gyroMouseRemainX.toInt().toShort()
        val sendY = gyroMouseRemainY.toInt().toShort()
        if (sendX.toInt() != 0) gyroMouseRemainX -= sendX
        if (sendY.toInt() != 0) gyroMouseRemainY -= sendY

        if (sendX.toInt() != 0 || sendY.toInt() != 0) {
            handler.conn.sendMouseMove(sendX, sendY)
        }
    }

    fun applyGyroToRightStick(controllerNumber: Short, gyroXDegPerSec: Float, gyroYDegPerSec: Float) {
        // 计算陀螺仪映射到摇杆的值
        val effectiveSensitivity = 180.0f / handler.prefConfig.gyroSensitivityMultiplier
        var scaledX = -ControllerHandler.clampFloat(gyroXDegPerSec / effectiveSensitivity, -1.0f, 1.0f)
        var scaledY = ControllerHandler.clampFloat(gyroYDegPerSec / effectiveSensitivity, -1.0f, 1.0f)

        // 应用X轴反转设置
        if (handler.prefConfig.gyroInvertXAxis) {
            scaledX = -scaledX
        }

        // 应用Y轴反转设置
        if (handler.prefConfig.gyroInvertYAxis) {
            scaledY = -scaledY
        }

        val mappedX = (scaledX * 0x7FFE).toInt().toShort()
        val mappedY = (scaledY * 0x7FFE).toInt().toShort()

        // 更新对应控制器上下文的陀螺仪摇杆值
        val targetContext = findControllerContext(controllerNumber)
        if (targetContext != null) {
            updateContextWithGyroData(targetContext, mappedX, mappedY)
        }
    }

    private fun findControllerContext(controllerNumber: Short): GenericControllerContext? {
        // 首先查找USB设备上下文
        for (context in handler.driverControllerContexts.values) {
            if (context.controllerNumber == controllerNumber) {
                return context
            }
        }

        // 查找输入设备上下文
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val context = handler.inputDeviceContexts.valueAt(i)
            if (context.controllerNumber == controllerNumber) {
                return context
            }
        }

        // 控制器0的默认上下文回退
        if (controllerNumber.toInt() == 0) {
            return handler.defaultContext
        }

        return null
    }

    private fun updateContextWithGyroData(context: GenericControllerContext, mappedX: Short, mappedY: Short) {
        context.gyroRightStickX = mappedX
        context.gyroRightStickY = mappedY

        // 如果陀螺仪到右摇杆映射启用且hold状态激活，则应用融合
        if (isRightStickMode && isGyroHoldActiveFor(context.controllerNumber)) {
            // 按轴叠加并限幅（物理值应用EPS去噪）
            val px = ControllerHandler.denoisePhys(context.physRightStickX)
            val py = ControllerHandler.denoisePhys(context.physRightStickY)
            context.rightStickX = ControllerHandler.clampShortToStickRange(px + context.gyroRightStickX)
            context.rightStickY = ControllerHandler.clampShortToStickRange(py + context.gyroRightStickY)
            handler.sendControllerInputPacket(context)
        }
    }

    val assistantMode: GyroAssistantMode
        get() = GyroAssistantMode.from(handler.prefConfig)

    val isRightStickMode: Boolean
        get() = assistantMode == GyroAssistantMode.RIGHT_STICK

    val isMouseMode: Boolean
        get() = assistantMode == GyroAssistantMode.MOUSE

    fun setAssistantMode(mode: GyroAssistantMode) {
        beginNewSourceLifecycle()
        // Storing the mode as two flags keeps the persisted format, but only here.
        handler.prefConfig.gyroToMouse = mode == GyroAssistantMode.MOUSE
        handler.prefConfig.gyroToRightStick = mode == GyroAssistantMode.RIGHT_STICK
        controllerGyroDemand.updateAssistantEnabled(mode != GyroAssistantMode.OFF)
        gyroMouseRemainX = 0f
        gyroMouseRemainY = 0f
        gyroMouseLastTimestamp = 0

        applySource(resolveSource())

        if (mode == GyroAssistantMode.OFF) {
            clearAllGyroStates()
        } else {
            recomputeGyroHoldForAllContexts()
        }
    }

    /** Controller 0's Android InputDevice contexts. */
    private fun controller0InputContexts(): List<InputDeviceContext> =
        (0 until handler.inputDeviceContexts.size())
            .map { handler.inputDeviceContexts.valueAt(it) }
            .filter { it.controllerNumber.toInt() == 0 }

    /**
     * A gamepad often enumerates as several InputDevices sharing controller 0, and device IDs
     * are reassigned on every reconnect, so pick by capability rather than by ordering.
     */
    private fun controller0InputContextWithGyro(): InputDeviceContext? =
        controller0InputContexts().firstOrNull {
            it.sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }

    private fun controller0DriverContext(): DriverControllerContext? =
        handler.driverControllerContexts.values.firstOrNull { it.controllerNumber.toInt() == 0 }

    /** Decide which source should feed the assistant. Pure: performs no registration. */
    private fun resolveSource(): GyroSource {
        if (assistantMode == GyroAssistantMode.OFF) return GyroSource.NONE
        if (controllerSensorRejected) return GyroSource.DEVICE

        if (controller0InputContexts().isNotEmpty()) {
            return if (controller0InputContextWithGyro() != null) {
                GyroSource.INPUT_DEVICE
            } else {
                GyroSource.DEVICE
            }
        }

        val driverContext = controller0DriverContext()
        if (driverContext != null) {
            val hasDriverGyro = driverContext.device?.let {
                (it.capabilities.toInt() and MoonBridge.LI_CCAP_GYRO.toInt()) != 0
            } == true
            return if (hasDriverGyro) GyroSource.DRIVER else GyroSource.DEVICE
        }

        // Nothing owns slot 0. The on-screen controller writes into defaultContext too,
        // so the device gyroscope is always the right target here.
        return GyroSource.DEVICE
    }

    /** Make [source] the only registered gyro source for controller 0. */
    private fun applySource(source: GyroSource) {
        when (source) {
            GyroSource.NONE -> releaseAssistantSource()

            GyroSource.INPUT_DEVICE -> {
                registerDeviceGyroForDefaultContext(false)
                activeSource = GyroSource.INPUT_DEVICE
                controller0InputContextWithGyro()?.let {
                    handler.backgroundThreadHandler.removeCallbacks(it.enableSensorRunnable)
                }
                handler.handleSetMotionEventState(
                    0.toShort(),
                    MoonBridge.LI_MOTION_TYPE_GYRO,
                    controllerGyroDemand.effectiveReportRateHz,
                    isHostRequest = false
                )
            }

            GyroSource.DRIVER -> {
                registerDeviceGyroForDefaultContext(false)
                activeSource = GyroSource.DRIVER
            }

            GyroSource.DEVICE -> {
                // Publish before unregistering so driver callbacks racing this switch are
                // already routed away from the controller sensor.
                val previous = activeSource
                activeSource = GyroSource.DEVICE
                val result = registerDeviceGyroForDefaultContext(
                    enable = true,
                    allowWhenControllerPresent = true,
                    reportRateHz = controllerGyroDemand.effectiveReportRateHz
                )
                if (result == DeviceGyroRegistrationResult.APPLIED) {
                    unregisterController0InputListeners()
                } else {
                    activeSource = previous
                    LimeLog.warning("Device gyroscope unavailable for controller 0 ($result)")
                }
            }
        }
    }

    /** Assistant is off: hand controller 0's sensor back to whatever the host asked for. */
    private fun releaseAssistantSource() {
        val servedByDeviceGyro = activeSource == GyroSource.DEVICE
        val hostReportRateHz = controllerGyroDemand.hostReportRateHz
        activeSource = GyroSource.NONE

        if (servedByDeviceGyro) {
            if (hostReportRateHz.toInt() == 0) {
                registerDeviceGyroForDefaultContext(false)
                return
            }
            activeSource = GyroSource.DEVICE
            registerDeviceGyroForDefaultContext(
                enable = true,
                allowWhenControllerPresent = true,
                reportRateHz = controllerGyroDemand.effectiveReportRateHz
            )
            return
        }

        registerDeviceGyroForDefaultContext(false)
        handler.handleSetMotionEventState(
            0.toShort(),
            MoonBridge.LI_MOTION_TYPE_GYRO,
            hostReportRateHz,
            isHostRequest = false
        )
    }

    /** Drop Android InputDevice gyro listeners for controller 0, keeping their stored rate. */
    private fun unregisterController0InputListeners() {
        for (context in controller0InputContexts()) {
            context.gyroListener?.let { listener ->
                context.sensorManager?.unregisterListener(listener)
                context.gyroListener = null
            }
        }
    }

    /**
     * Observe physical-controller gyro samples before they are routed to an assistant
     * or forwarded to the host. This is shared by Android sensors and custom drivers.
     */
    fun onControllerGyroSample(
        x: Float,
        y: Float,
        z: Float,
        controllerNumber: Short,
        timestampNanos: Long
    ) {
        if (controllerNumber.toInt() != 0) return
        if (activeSource == GyroSource.DEVICE || controllerSensorRejected) return
        if (!isDeviceGyroFallbackAllowed()) return

        val generation = sourceGeneration
        if (!controllerGyroLivenessTracker.onSample(x, y, z, timestampNanos)) {
            return
        }

        handler.mainThreadHandler.post {
            if (handler.stopped || generation != sourceGeneration) return@post
            if (activeSource == GyroSource.DEVICE || controllerSensorRejected) return@post
            if (!isDeviceGyroFallbackAllowed()) return@post

            rejectControllerSensor("is reporting only zero samples")
        }
    }

    /**
     * Controller 0's own sensor is unusable. Pin the demand to the device gyroscope, allowing
     * a single retry when the device sensor merely refused this registration attempt.
     */
    private fun rejectControllerSensor(reason: String) {
        when (
            registerDeviceGyroForDefaultContext(
                enable = true,
                allowWhenControllerPresent = true,
                reportRateHz = controllerGyroDemand.effectiveReportRateHz
            )
        ) {
            DeviceGyroRegistrationResult.APPLIED -> {
                controllerSensorRejected = true
                activeSource = GyroSource.DEVICE
                unregisterController0InputListeners()
                LimeLog.warning("Controller 0 gyroscope $reason; using device gyroscope")
            }
            DeviceGyroRegistrationResult.RETRYABLE_FAILURE -> {
                if (deviceGyroRetryAvailable) {
                    deviceGyroRetryAvailable = false
                    reopenLivenessDetection()
                    LimeLog.warning("Device gyroscope registration failed; retrying once")
                } else {
                    LimeLog.warning("Device gyroscope retry failed; keeping controller gyroscope")
                }
            }
            DeviceGyroRegistrationResult.UNAVAILABLE ->
                LimeLog.warning("Device gyroscope is unavailable; keeping controller gyroscope")
        }
    }

    private fun isDeviceGyroFallbackAllowed(): Boolean =
        controllerGyroDemand.assistantEnabled ||
            (handler.prefConfig.gamepadMotionSensorsFallbackToDevice &&
                controllerGyroDemand.hostReportRateHz.toInt() != 0)

    /** Controller 0's own sensor refused registration, so move the demand to the device gyro. */
    internal fun onControllerGyroRegistrationFailed(controllerNumber: Short) {
        if (controllerNumber.toInt() != 0 || controllerSensorRejected) return
        if (!isDeviceGyroFallbackAllowed()) return
        rejectControllerSensor("could not be registered")
    }

    private fun updateAssistantDemand() {
        controllerGyroDemand.updateAssistantEnabled(isAssistantEnabled)
    }

    /** Starts a new source lifecycle with a fresh bounded registration retry. */
    private fun beginNewSourceLifecycle() {
        deviceGyroRetryAvailable = true
        reopenLivenessDetection()
    }

    /** Reopens liveness detection without replenishing the current source's retry budget. */
    private fun reopenLivenessDetection() {
        sourceGeneration++
        controllerGyroLivenessTracker.reset()
    }

    fun isUsingDeviceGyroFallback(controllerNumber: Short): Boolean =
        controllerNumber.toInt() == 0 && activeSource == GyroSource.DEVICE

    fun onControllerSourceChanged(controllerNumber: Short) {
        if (controllerNumber.toInt() != 0) return

        if (activeSource == GyroSource.DEVICE) {
            registerDeviceGyroForDefaultContext(false)
        }
        activeSource = GyroSource.NONE
        controllerSensorRejected = false
        beginNewSourceLifecycle()
    }

    /** Route later host enable/disable requests away from a known phantom sensor. */
    fun handleControllerGyroReportRate(
        controllerNumber: Short,
        reportRateHz: Short,
        isHostRequest: Boolean
    ): Boolean {
        if (controllerNumber.toInt() != 0) return false
        if (isHostRequest) {
            controllerGyroDemand.updateHostReportRate(reportRateHz)
        }
        if (!isUsingDeviceGyroFallback(controllerNumber)) return false

        registerDeviceGyroForDefaultContext(
            enable = controllerGyroDemand.shouldSample,
            allowWhenControllerPresent = true,
            reportRateHz = controllerGyroDemand.effectiveReportRateHz
        )
        return true
    }

    /**
     * Combine independent host and assistant demand into the rate used by the
     * physical listener. Controller contexts store this effective sampling rate,
     * while [controllerGyroDemand] remains the source of truth for host demand.
     */
    fun effectiveControllerGyroReportRate(
        controllerNumber: Short,
        requestedReportRateHz: Short
    ): Short {
        if (controllerNumber.toInt() != 0) return requestedReportRateHz
        return if (controllerGyroDemand.shouldSample) {
            controllerGyroDemand.effectiveReportRateHz
        } else {
            0
        }
    }

    // 在系统重新启用传感器时，检查并恢复陀螺仪功能
    fun onSensorsReenabled() {
        // Contexts created or migrated after the assistant was turned on start with a
        // cleared hold flag, so demand and hold must be rebuilt on every restore.
        updateAssistantDemand()

        val mode = assistantMode
        if (mode != GyroAssistantMode.OFF) {
            LimeLog.info("Sensors re-enabled, restoring gyro assistant: $mode")
            recomputeGyroHoldForAllContexts()
            applySource(resolveSource())
            return
        }

        if (controllerGyroDemand.hostReportRateHz.toInt() != 0) {
            LimeLog.info("Sensors re-enabled, restoring host gyroscope request")
            handler.handleSetMotionEventState(
                0.toShort(),
                MoonBridge.LI_MOTION_TYPE_GYRO,
                controllerGyroDemand.hostReportRateHz,
                isHostRequest = false
            )
        }
    }

    /**
     * 直接在 defaultContext 上注册/注销手机内置陀螺仪。
     * handleSetMotionEventState 只遍历 inputDeviceContexts，defaultContext 不在其中，
     * 所以需要这个专用方法。
     *
     * 注意：通常如果已有物理手柄（controllerNumber=0 的 InputDeviceContext 存在），
     * 则不注册，避免与手柄自身的传感器路径冲突产生双重输入。调用方确认手柄
     * 没有陀螺仪时，可以显式允许设备传感器回退。
     * 启用请求会区分永久不可用和可重试的监听注册失败。
     */
    internal fun registerDeviceGyroForDefaultContext(
        enable: Boolean,
        allowWhenControllerPresent: Boolean = false,
        reportRateHz: Short = 120
    ): DeviceGyroRegistrationResult {
        if (enable) {
            // 如果已有物理手柄占据 controllerNumber=0，不在 defaultContext 上额外注册
            // 手柄的传感器由 handleSetMotionEventState 通过 inputDeviceContexts 管理
            if (!allowWhenControllerPresent) {
                for (i in 0 until handler.inputDeviceContexts.size()) {
                    if (handler.inputDeviceContexts.valueAt(i).controllerNumber.toInt() == 0) {
                        LimeLog.info("Physical controller present, skipping defaultContext gyro registration")
                        return DeviceGyroRegistrationResult.UNAVAILABLE
                    }
                }
                // 同样检查 USB 手柄
                for (context in handler.driverControllerContexts.values) {
                    if (context.controllerNumber.toInt() == 0) {
                        LimeLog.info("USB controller present, skipping defaultContext gyro registration")
                        return DeviceGyroRegistrationResult.UNAVAILABLE
                    }
                }
            }
            if (handler.defaultContext.sensorManager == null) {
                if (handler.deviceSensorManager == null) {
                    LimeLog.warning("deviceSensorManager is null, cannot register gyro on defaultContext")
                    return DeviceGyroRegistrationResult.UNAVAILABLE
                }
                handler.defaultContext.sensorManager = handler.deviceSensorManager
            }
            val sensorManager = handler.defaultContext.sensorManager!!
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyroSensor == null) {
                LimeLog.warning("No gyroscope sensor available on this device")
                return DeviceGyroRegistrationResult.UNAVAILABLE
            }

            val oldListener = handler.defaultContext.gyroListener
            val newListener = createSensorListener(
                handler.defaultContext.controllerNumber,
                MoonBridge.LI_MOTION_TYPE_GYRO,
                true /* needsDeviceOrientationCorrection */
            )
            val registered = sensorManager.registerListener(
                newListener, gyroSensor, 1000000 / reportRateHz
            )
            if (!registered) {
                LimeLog.warning("Failed to register gyro on defaultContext")
                return DeviceGyroRegistrationResult.RETRYABLE_FAILURE
            }

            oldListener?.let { sensorManager.unregisterListener(it) }
            handler.defaultContext.gyroListener = newListener
            handler.defaultContext.gyroReportRateHz = reportRateHz
            LimeLog.info("Gyro registered on defaultContext")
            return DeviceGyroRegistrationResult.APPLIED
        } else {
            if (handler.defaultContext.gyroListener != null && handler.defaultContext.sensorManager != null) {
                handler.defaultContext.sensorManager!!.unregisterListener(handler.defaultContext.gyroListener)
                handler.defaultContext.gyroListener = null
                handler.defaultContext.gyroReportRateHz = 0
                // 清除我们设置的 sensorManager，避免泄漏给右摇杆模式
                handler.defaultContext.sensorManager = null
                LimeLog.info("Gyro unregistered from defaultContext")
            }
            return DeviceGyroRegistrationResult.APPLIED
        }
    }

    fun clearAllGyroStates() {
        // 清除所有控制器的陀螺仪摇杆数据和保持状态
        for (c in handler.driverControllerContexts.values) {
            c.gyroRightStickX = 0
            c.gyroRightStickY = 0
            c.gyroHoldActive = false
        }
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val c = handler.inputDeviceContexts.valueAt(i)
            c.gyroRightStickX = 0
            c.gyroRightStickY = 0
            c.gyroHoldActive = false
        }
        handler.defaultContext.gyroRightStickX = 0
        handler.defaultContext.gyroRightStickY = 0
        handler.defaultContext.gyroHoldActive = false
    }

    /**
     * 检查是否有任何物理手柄或虚拟手柄连接
     * @return 如果有手柄连接返回true，否则返回false
     */
    fun hasAnyController(): Boolean {
        // 检查是否有物理手柄（InputDevice）
        if (handler.inputDeviceContexts.size() > 0) {
            return true
        }
        // 检查是否有USB手柄
        if (handler.driverControllerContexts.isNotEmpty()) {
            return true
        }
        // 检查虚拟手柄是否启用
        // 虚拟手柄通常使用 defaultContext (controllerNumber=0)
        // 如果王冠功能启用，说明虚拟手柄可用
        if (handler.prefConfig.onscreenController) {
            return true
        }
        return false
    }

    fun recomputeGyroHoldForAllContexts() {
        val alwaysOn = handler.prefConfig.gyroActivationKeyCode == GYRO_ACTIVATION_ALWAYS
        val useL2 = handler.prefConfig.gyroActivationKeyCode == KeyEvent.KEYCODE_BUTTON_L2
        val useR2 = handler.prefConfig.gyroActivationKeyCode == KeyEvent.KEYCODE_BUTTON_R2

        for (c in handler.driverControllerContexts.values) {
            c.gyroHoldActive = when {
                alwaysOn -> true
                useL2 -> (c.leftTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
                useR2 -> (c.rightTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
                else -> false
            }
        }

        for (i in 0 until handler.inputDeviceContexts.size()) {
            val c = handler.inputDeviceContexts.valueAt(i)
            c.gyroHoldActive = when {
                alwaysOn -> true
                useL2 -> (c.leftTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
                useR2 -> (c.rightTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
                else -> false
            }
        }

        handler.defaultContext.gyroHoldActive = when {
            alwaysOn -> true
            useL2 -> (handler.defaultContext.leftTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
            useR2 -> (handler.defaultContext.rightTrigger.toInt() and 0xFF) / 255.0f >= TRIGGER_ACTIVATE_THRESHOLD
            else -> false
        }
    }

    // Future-proof activation handling helpers
    fun computeAnalogActivation(leftTrigger: Float, rightTrigger: Float): Boolean {
        return when (handler.prefConfig.gyroActivationKeyCode) {
            GYRO_ACTIVATION_ALWAYS -> true
            KeyEvent.KEYCODE_BUTTON_L2 -> leftTrigger >= TRIGGER_ACTIVATE_THRESHOLD
            KeyEvent.KEYCODE_BUTTON_R2 -> rightTrigger >= TRIGGER_ACTIVATE_THRESHOLD
            else -> false
        }
    }

    val isAssistantEnabled: Boolean
        get() = assistantMode != GyroAssistantMode.OFF

    /** Hold state implied by analog trigger positions; false whenever no assistant is active. */
    fun computeHoldFromAnalog(leftTrigger: Float, rightTrigger: Float): Boolean =
        isAssistantEnabled && computeAnalogActivation(leftTrigger, rightTrigger)

    fun updateGyroHoldFromDigital(context: InputDeviceContext, keyCode: Int, isDown: Boolean) {
        if (!isAssistantEnabled) {
            context.gyroHoldActive = false
            return
        }
        if (handler.prefConfig.gyroActivationKeyCode == GYRO_ACTIVATION_ALWAYS) {
            context.gyroHoldActive = true
            return
        }
        if (keyCode == handler.prefConfig.gyroActivationKeyCode) {
            val was = context.gyroHoldActive
            context.gyroHoldActive = isDown
            if (was && !isDown) {
                onGyroHoldDeactivated(context as GenericControllerContext)
            }
        }
    }

    fun updateGyroHoldFromDigitalGeneric(context: GenericControllerContext, keyCode: Int, isDown: Boolean) {
        if (!isAssistantEnabled) {
            context.gyroHoldActive = false
            return
        }
        if (handler.prefConfig.gyroActivationKeyCode == GYRO_ACTIVATION_ALWAYS) {
            context.gyroHoldActive = true
            return
        }
        if (keyCode == handler.prefConfig.gyroActivationKeyCode) {
            val was = context.gyroHoldActive
            context.gyroHoldActive = isDown
            if (was && !isDown) {
                onGyroHoldDeactivated(context)
            }
        }
    }

    fun onGyroHoldDeactivated(context: GenericControllerContext) {
        context.gyroRightStickX = 0
        context.gyroRightStickY = 0
        // In mouse mode there's no right-stick data to flush; skip the controller packet
        if (isMouseMode) return
        // 恢复为纯物理值并立即发送
        context.rightStickX = context.physRightStickX
        context.rightStickY = context.physRightStickY
        handler.sendControllerInputPacket(context)
    }

    fun onGyroHoldDeactivatedInput(context: InputDeviceContext) {
        context.gyroRightStickX = 0
        context.gyroRightStickY = 0
        // In mouse mode there's no right-stick data to flush; skip the controller packet
        if (isMouseMode) return
        // 立即发送仅物理摇杆的状态，确保停止模拟
        handler.sendControllerInputPacket(context)
    }

    fun isGyroHoldActiveFor(controllerNumber: Short): Boolean {
        for (c in handler.driverControllerContexts.values) {
            if (c.controllerNumber == controllerNumber && c.gyroHoldActive) return true
        }
        for (i in 0 until handler.inputDeviceContexts.size()) {
            val c = handler.inputDeviceContexts.valueAt(i)
            if (c.controllerNumber == controllerNumber && c.gyroHoldActive) return true
        }
        if (handler.defaultContext.controllerNumber == controllerNumber && handler.defaultContext.gyroHoldActive) return true
        return false
    }

    fun createSensorListener(controllerNumber: Short, motionType: Byte, needsDeviceOrientationCorrection: Boolean): SensorEventListener {
        return object : SensorEventListener {
            private val lastValues = FloatArray(3)

            override fun onSensorChanged(sensorEvent: SensorEvent) {
                if (handler.isControllerLocallyCaptured(controllerNumber)) return

                if (motionType == MoonBridge.LI_MOTION_TYPE_GYRO &&
                    !needsDeviceOrientationCorrection
                ) {
                    onControllerGyroSample(
                        sensorEvent.values[0],
                        sensorEvent.values[1],
                        sensorEvent.values[2],
                        controllerNumber,
                        sensorEvent.timestamp
                    )
                }

                // Android will invoke our callback any time we get a new reading,
                // even if the values are the same as last time. Don't report a
                // duplicate set of values to save bandwidth.
                if (sensorEvent.values[0] == lastValues[0] &&
                    sensorEvent.values[1] == lastValues[1] &&
                    sensorEvent.values[2] == lastValues[2]
                ) {
                    return
                } else {
                    lastValues[0] = sensorEvent.values[0]
                    lastValues[1] = sensorEvent.values[1]
                    lastValues[2] = sensorEvent.values[2]
                }

                var x = 0
                var y = 1
                var z = 2
                var xFactor = 1
                var yFactor = 1
                var zFactor = 1

                if (needsDeviceOrientationCorrection) {
                    @Suppress("DEPRECATION")
                    val deviceRotation = handler.activityContext.windowManager.defaultDisplay.rotation
                    when (deviceRotation) {
                        Surface.ROTATION_0, Surface.ROTATION_180 -> {
                            x = 0; y = 2; z = 1
                        }
                        Surface.ROTATION_90, Surface.ROTATION_270 -> {
                            x = 1; y = 2; z = 0
                        }
                    }

                    when (deviceRotation) {
                        Surface.ROTATION_0 -> zFactor = -1
                        Surface.ROTATION_90 -> { xFactor = -1; zFactor = -1 }
                        Surface.ROTATION_180 -> xFactor = -1
                        Surface.ROTATION_270 -> { /* defaults */ }
                    }
                }

                if (motionType == MoonBridge.LI_MOTION_TYPE_GYRO) {
                    // Convert from rad/s to deg/s
                    val gx = sensorEvent.values[x] * xFactor * 57.2957795f
                    val gy = sensorEvent.values[y] * yFactor * 57.2957795f
                    val gz = sensorEvent.values[z] * zFactor * 57.2957795f

                    if (isMouseMode && isGyroHoldActiveFor(controllerNumber)) {
                        // 使用已经过屏幕旋转修正的轴值（rad/s）
                        // 横屏下：gz(yaw) → mouseX，gx(pitch) → mouseY
                        val mouseX = sensorEvent.values[z] * zFactor
                        val mouseY = sensorEvent.values[x] * xFactor
                        applyGyroToMouse(mouseX, mouseY, sensorEvent.timestamp)
                        return
                    }

                    if (isRightStickMode && isGyroHoldActiveFor(controllerNumber)) {
                        // Map device/controller gyro to right stick
                        applyGyroToRightStick(controllerNumber, gz, gx)
                        return
                    }

                    if (controllerNumber.toInt() != 0 ||
                        controllerGyroDemand.hostReportRateHz.toInt() != 0
                    ) {
                        handler.conn.sendControllerMotionEvent(
                            controllerNumber.toByte(), motionType, gx, gy, gz
                        )
                    }
                } else {
                    // Pass m/s^2 directly without conversion
                    handler.conn.sendControllerMotionEvent(
                        controllerNumber.toByte(), motionType,
                        sensorEvent.values[x] * xFactor.toFloat(),
                        sensorEvent.values[y] * yFactor.toFloat(),
                        sensorEvent.values[z] * zFactor.toFloat()
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
    }

    fun onStreamStopped() {
        beginNewSourceLifecycle()
        activeSource = GyroSource.NONE
        controllerSensorRejected = false
        controllerGyroDemand.clear()
    }
}
