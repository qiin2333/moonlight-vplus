package com.limelight.binding.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.view.KeyEvent
import android.view.Surface

import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge

/**
 * 陀螺仪相关功能管理器
 * 处理陀螺仪到鼠标映射、陀螺仪到右摇杆映射、传感器注册与保持激活逻辑
 */
class ControllerGyroManager(private val handler: ControllerHandler) {

    // Gyro-to-right-stick mapping sensitivity (deg/s for full deflection)
    companion object {
        private const val GYRO_DEFAULT_FULL_DEFLECTION_DPS = 180.0f
        const val TRIGGER_ACTIVATE_THRESHOLD = 0.2f
        const val GYRO_ACTIVATION_ALWAYS = -1000
    }

    // Gyro-to-mouse accumulator (sub-pixel remainder)
    @Volatile var gyroMouseRemainX = 0f
    @Volatile var gyroMouseRemainY = 0f
    @Volatile var gyroMouseLastTimestamp = 0L

    // Callback to notify VirtualController to pause/resume its own gyro listener
    // to avoid double-registration on the same sensor when mouse mode is active.
    private var virtualControllerGyroSuspendCallback: Runnable? = null
    private var virtualControllerGyroResumeCallback: Runnable? = null
    private val controllerGyroLivenessTracker = ControllerGyroLivenessTracker()
    private val controllerGyroDemand = ControllerGyroDemandState()
    @Volatile private var controllerGyroUsesDeviceFallback = false
    @Volatile private var controllerGyroSourceGeneration = 0L

    fun setVirtualControllerGyroCallbacks(suspend: Runnable?, resume: Runnable?) {
        virtualControllerGyroSuspendCallback = suspend
        virtualControllerGyroResumeCallback = resume
    }

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
        if (handler.prefConfig.gyroToRightStick && context.gyroHoldActive) {
            // 按轴叠加并限幅（物理值应用EPS去噪）
            val px = ControllerHandler.denoisePhys(context.physRightStickX)
            val py = ControllerHandler.denoisePhys(context.physRightStickY)
            context.rightStickX = ControllerHandler.clampShortToStickRange(px + context.gyroRightStickX)
            context.rightStickY = ControllerHandler.clampShortToStickRange(py + context.gyroRightStickY)
            handler.sendControllerInputPacket(context)
        }
    }

    fun setGyroToRightStickEnabled(enabled: Boolean) {
        invalidatePendingControllerGyroFallback()
        handler.prefConfig.gyroToRightStick = enabled
        if (enabled) {
            // 互斥：关闭鼠标模式
            handler.prefConfig.gyroToMouse = false
            updateAssistantDemand()
            gyroMouseRemainX = 0f
            gyroMouseRemainY = 0f
            gyroMouseLastTimestamp = 0
            registerRightStickGyro(true)

            recomputeGyroHoldForAllContexts()
        } else {
            updateAssistantDemand()
            registerRightStickGyro(false)
            clearAllGyroStates()
        }
    }

    /**
     * Register the sensor source used by gyro-to-right-stick mode.
     *
     * inputDeviceContexts is keyed by Android input device ID, not controller number,
     * so looking up key 0 does not find controller 0 on most devices. When there is no
     * Android InputDevice for controller 0, use the regular device SensorManager on
     * defaultContext (the same reliable path used by gyro-to-mouse mode).
     */
    private fun registerRightStickGyro(enable: Boolean) {
        val controllerContext = (0 until handler.inputDeviceContexts.size())
            .asSequence()
            .map { handler.inputDeviceContexts.valueAt(it) }
            .firstOrNull { it.controllerNumber.toInt() == 0 }
        val driverContext = handler.driverControllerContexts.values
            .firstOrNull { it.controllerNumber.toInt() == 0 }

        if (!enable) {
            restoreHostGyroAfterAssistantDisabled()
            return
        }

        if (controllerGyroUsesDeviceFallback) {
            registerDeviceGyroForDefaultContext(
                enable = true,
                allowWhenControllerPresent = true,
                reportRateHz = effectiveDeviceFallbackReportRateHz()
            )
            return
        }

        if (controllerContext != null) {
            // Switching from gyro-mouse can leave defaultContext registered. Clear it
            // before selecting either the controller gyro or the device fallback.
            registerDeviceGyroForDefaultContext(false)
            val controllerGyro = controllerContext.sensorManager
                ?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (controllerGyro == null) {
                LimeLog.info("Controller 0 has no gyroscope; using device gyroscope")
                registerDeviceGyroForDefaultContext(
                    enable = true,
                    allowWhenControllerPresent = true,
                    reportRateHz = effectiveDeviceFallbackReportRateHz()
                )
                return
            }
            handler.backgroundThreadHandler.removeCallbacks(controllerContext.enableSensorRunnable)
            handler.handleSetMotionEventState(
                0.toShort(),
                MoonBridge.LI_MOTION_TYPE_GYRO,
                controllerGyroDemand.effectiveReportRateHz,
                isHostRequest = false
            )
            return
        }

        if (driverContext != null) {
            registerDeviceGyroForDefaultContext(false)
            val hasDriverGyro = driverContext.device?.let {
                (it.capabilities.toInt() and MoonBridge.LI_CCAP_GYRO.toInt()) != 0
            } == true
            if (!hasDriverGyro) {
                LimeLog.info("Controller 0 driver has no gyroscope; using device gyroscope")
                registerDeviceGyroForDefaultContext(
                    enable = true,
                    allowWhenControllerPresent = true,
                    reportRateHz = effectiveDeviceFallbackReportRateHz()
                )
            }
            return
        }

        // VirtualController owns its own listener when the on-screen controller is enabled.
        // Otherwise defaultContext is the only controller-0 target available to this mode.
        if (handler.prefConfig.onscreenController) {
            registerDeviceGyroForDefaultContext(false)
            virtualControllerGyroResumeCallback?.run()
            LimeLog.info("Using VirtualController gyroscope for right-stick mode")
        } else {
            registerDeviceGyroForDefaultContext(
                enable = true,
                reportRateHz = effectiveDeviceFallbackReportRateHz()
            )
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
        if (controllerNumber.toInt() != 0 || controllerGyroUsesDeviceFallback) return
        if (!isControllerGyroFallbackAllowed()) return

        val sourceGeneration = controllerGyroSourceGeneration
        if (!controllerGyroLivenessTracker.onSample(x, y, z, timestampNanos)) {
            return
        }

        handler.mainThreadHandler.post {
            if (handler.stopped ||
                sourceGeneration != controllerGyroSourceGeneration ||
                controllerGyroUsesDeviceFallback ||
                !isControllerGyroFallbackAllowed()
            ) {
                return@post
            }

            LimeLog.warning(
                "Controller 0 gyroscope is reporting only zero samples; using device gyroscope"
            )
            controllerGyroUsesDeviceFallback = true

            // Stop every Android InputDevice listener for controller 0 without
            // clearing the host-requested rate stored on its context.
            for (i in 0 until handler.inputDeviceContexts.size()) {
                val context = handler.inputDeviceContexts.valueAt(i)
                if (context.controllerNumber.toInt() != 0) continue
                context.gyroListener?.let { listener ->
                    context.sensorManager?.unregisterListener(listener)
                    context.gyroListener = null
                }
            }

            registerDeviceGyroForDefaultContext(
                enable = true,
                allowWhenControllerPresent = true,
                reportRateHz = effectiveDeviceFallbackReportRateHz()
            )
        }
    }

    private fun isControllerGyroFallbackAllowed(): Boolean =
        controllerGyroDemand.assistantEnabled ||
            (handler.prefConfig.gamepadMotionSensorsFallbackToDevice &&
                controllerGyroDemand.hostReportRateHz.toInt() != 0)

    private fun effectiveDeviceFallbackReportRateHz(): Short =
        controllerGyroDemand.effectiveReportRateHz

    private fun updateAssistantDemand() {
        controllerGyroDemand.updateAssistantEnabled(
            handler.prefConfig.gyroToRightStick || handler.prefConfig.gyroToMouse
        )
    }

    private fun restoreHostGyroAfterAssistantDisabled() {
        val hostReportRateHz = controllerGyroDemand.hostReportRateHz
        if (controllerGyroUsesDeviceFallback) {
            registerDeviceGyroForDefaultContext(
                enable = hostReportRateHz.toInt() != 0,
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

    private fun invalidatePendingControllerGyroFallback() {
        controllerGyroSourceGeneration++
        controllerGyroLivenessTracker.reset()
    }

    fun isUsingDeviceGyroFallback(controllerNumber: Short): Boolean =
        controllerNumber.toInt() == 0 && controllerGyroUsesDeviceFallback

    fun onControllerSourceChanged(controllerNumber: Short) {
        if (controllerNumber.toInt() != 0) return

        if (controllerGyroUsesDeviceFallback) {
            registerDeviceGyroForDefaultContext(false)
        }
        invalidatePendingControllerGyroFallback()
        controllerGyroUsesDeviceFallback = false
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

        val effectiveReportRateHz = effectiveDeviceFallbackReportRateHz()
        registerDeviceGyroForDefaultContext(
            enable = controllerGyroDemand.shouldSample,
            allowWhenControllerPresent = true,
            reportRateHz = effectiveReportRateHz
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
        if (handler.prefConfig.gyroToMouse) {
            LimeLog.info("Sensors re-enabled, restoring gyro-to-mouse")
            registerDeviceGyroForDefaultContext(
                enable = true,
                reportRateHz = controllerGyroDemand.effectiveReportRateHz
            )
        } else if (handler.prefConfig.gyroToRightStick) {
            LimeLog.info("Sensors re-enabled, restoring gyro-to-right-stick")
            registerRightStickGyro(true)
        } else if (controllerGyroDemand.hostReportRateHz.toInt() != 0) {
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
     */
    fun registerDeviceGyroForDefaultContext(
        enable: Boolean,
        allowWhenControllerPresent: Boolean = false,
        reportRateHz: Short = 120
    ) {
        if (enable) {
            // 如果已有物理手柄占据 controllerNumber=0，不在 defaultContext 上额外注册
            // 手柄的传感器由 handleSetMotionEventState 通过 inputDeviceContexts 管理
            if (!allowWhenControllerPresent) {
                for (i in 0 until handler.inputDeviceContexts.size()) {
                    if (handler.inputDeviceContexts.valueAt(i).controllerNumber.toInt() == 0) {
                        LimeLog.info("Physical controller present, skipping defaultContext gyro registration")
                        return
                    }
                }
                // 同样检查 USB 手柄
                for (context in handler.driverControllerContexts.values) {
                    if (context.controllerNumber.toInt() == 0) {
                        LimeLog.info("USB controller present, skipping defaultContext gyro registration")
                        return
                    }
                }
            }
            if (handler.defaultContext.sensorManager == null) {
                if (handler.deviceSensorManager == null) {
                    LimeLog.warning("deviceSensorManager is null, cannot register gyro on defaultContext")
                    return
                }
                handler.defaultContext.sensorManager = handler.deviceSensorManager
            }
            val gyroSensor = handler.defaultContext.sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyroSensor != null) {
                if (handler.defaultContext.gyroListener != null) {
                    handler.defaultContext.sensorManager!!.unregisterListener(handler.defaultContext.gyroListener)
                    handler.defaultContext.gyroListener = null
                }
                handler.defaultContext.gyroReportRateHz = reportRateHz
                handler.defaultContext.gyroListener = createSensorListener(
                    handler.defaultContext.controllerNumber,
                    MoonBridge.LI_MOTION_TYPE_GYRO,
                    true /* needsDeviceOrientationCorrection */
                )
                val registered = handler.defaultContext.sensorManager!!.registerListener(
                    handler.defaultContext.gyroListener, gyroSensor, 1000000 / reportRateHz
                )
                if (registered) {
                    LimeLog.info("Gyro registered on defaultContext")
                } else {
                    handler.defaultContext.gyroListener = null
                    handler.defaultContext.gyroReportRateHz = 0
                    handler.defaultContext.sensorManager = null
                    LimeLog.warning("Failed to register gyro on defaultContext")
                }
            } else {
                LimeLog.warning("No gyroscope sensor available on this device")
            }
        } else {
            if (handler.defaultContext.gyroListener != null && handler.defaultContext.sensorManager != null) {
                handler.defaultContext.sensorManager!!.unregisterListener(handler.defaultContext.gyroListener)
                handler.defaultContext.gyroListener = null
                handler.defaultContext.gyroReportRateHz = 0
                // 清除我们设置的 sensorManager，避免泄漏给右摇杆模式
                handler.defaultContext.sensorManager = null
                LimeLog.info("Gyro unregistered from defaultContext")
            }
        }
    }

    fun setGyroToMouseEnabled(enabled: Boolean) {
        invalidatePendingControllerGyroFallback()
        handler.prefConfig.gyroToMouse = enabled
        if (enabled) {
            // 互斥：关闭右摇杆模式
            handler.prefConfig.gyroToRightStick = false
            updateAssistantDemand()
            // 重置累加器
            gyroMouseRemainX = 0f
            gyroMouseRemainY = 0f
            gyroMouseLastTimestamp = 0
            // 暂停 VirtualController 自己的传感器监听，避免与 defaultContext 双重注册
            virtualControllerGyroSuspendCallback?.run()
            // Keep the existing gyro-to-mouse source selection behavior.
            registerDeviceGyroForDefaultContext(
                enable = true,
                reportRateHz = controllerGyroDemand.effectiveReportRateHz
            )
            // 重新计算所有 context 的 hold 状态（含 ALWAYS 情况）
            recomputeGyroHoldForAllContexts()
        } else {
            updateAssistantDemand()
            gyroMouseRemainX = 0f
            gyroMouseRemainY = 0f
            gyroMouseLastTimestamp = 0
            restoreHostGyroAfterAssistantDisabled()
            clearAllGyroStates()
            // 恢复 VirtualController 自己的传感器监听
            virtualControllerGyroResumeCallback?.run()
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

    /**
     * 处理来自虚拟控制器的陀螺仪数据
     * 这个方法允许虚拟控制器报告陀螺仪数据到ControllerHandler
     */
    fun reportVirtualControllerGyro(gx: Float, gy: Float, gz: Float) {
        if (handler.prefConfig.gyroToMouse) {
            if (handler.defaultContext.gyroHoldActive) {
                // 虚拟控制器陀螺仪→鼠标：gx=pitch(X轴), gz=yaw(Z轴)，横屏下 gz→mouseX, gx→mouseY
                // 这里传入的已经是 deg/s，转回 rad/s 再交给 applyGyroToMouse
                applyGyroToMouse(gz / 57.2957795f, gx / 57.2957795f, System.nanoTime())
            }
            return
        }

        if (!handler.prefConfig.gyroToRightStick) {
            return
        }

        if (!handler.defaultContext.gyroHoldActive) {
            return
        }

        // 使用控制器0（虚拟控制器的默认控制器编号）
        applyGyroToRightStick(0.toShort(), gx, gy)

        // 对于虚拟控制器，如果陀螺仪激活，直接发送数据包
        if (handler.defaultContext.gyroHoldActive) {
            // 应用陀螺仪融合到右摇杆
            val px = ControllerHandler.denoisePhys(handler.defaultContext.physRightStickX)
            val py = ControllerHandler.denoisePhys(handler.defaultContext.physRightStickY)
            handler.defaultContext.rightStickX = ControllerHandler.clampShortToStickRange(px + handler.defaultContext.gyroRightStickX)
            handler.defaultContext.rightStickY = ControllerHandler.clampShortToStickRange(py + handler.defaultContext.gyroRightStickY)

            // 直接发送数据包，不依赖reportOscState
            handler.sendControllerInputPacket(handler.defaultContext)
        }
    }

    fun updateGyroHoldFromDigital(context: InputDeviceContext, keyCode: Int, isDown: Boolean) {
        if (!handler.prefConfig.gyroToRightStick && !handler.prefConfig.gyroToMouse) {
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
        if (!handler.prefConfig.gyroToRightStick && !handler.prefConfig.gyroToMouse) {
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
        if (handler.prefConfig.gyroToMouse) return
        // 恢复为纯物理值并立即发送
        context.rightStickX = context.physRightStickX
        context.rightStickY = context.physRightStickY
        handler.sendControllerInputPacket(context)
    }

    fun onGyroHoldDeactivatedInput(context: InputDeviceContext) {
        context.gyroRightStickX = 0
        context.gyroRightStickY = 0
        // In mouse mode there's no right-stick data to flush; skip the controller packet
        if (handler.prefConfig.gyroToMouse) return
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

                    if (handler.prefConfig.gyroToMouse && isGyroHoldActiveFor(controllerNumber)) {
                        // 使用已经过屏幕旋转修正的轴值（rad/s）
                        // 横屏下：gz(yaw) → mouseX，gx(pitch) → mouseY
                        val mouseX = sensorEvent.values[z] * zFactor
                        val mouseY = sensorEvent.values[x] * xFactor
                        applyGyroToMouse(mouseX, mouseY, sensorEvent.timestamp)
                        return
                    }

                    if (handler.prefConfig.gyroToRightStick && isGyroHoldActiveFor(controllerNumber)) {
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
        invalidatePendingControllerGyroFallback()
        controllerGyroUsesDeviceFallback = false
        controllerGyroDemand.clear()
    }
}
