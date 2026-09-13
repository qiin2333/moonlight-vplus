package com.limelight.binding.input.driver

abstract class AbstractController(
    protected val deviceId: Int,
    protected val listener: ControllerDriverListener,
    private val vendorId: Int,
    private val productId: Int
) {
    protected var buttonFlags = 0
    var supportedButtonFlags = 0
        protected set
    protected var leftTrigger = 0f
    protected var rightTrigger = 0f
    protected var rightStickX = 0f
    protected var rightStickY = 0f
    protected var leftStickX = 0f
    protected var leftStickY = 0f
    var capabilities: Short = 0
        protected set
    var type: Byte = 0
        protected set

    fun getControllerId(): Int = deviceId

    fun getVendorId(): Int = vendorId

    fun getProductId(): Int = productId

    protected fun setButtonFlag(buttonFlag: Int, data: Int) {
        if (data != 0) {
            buttonFlags = buttonFlags or buttonFlag
        } else {
            buttonFlags = buttonFlags and buttonFlag.inv()
        }
    }

    protected fun reportInput() {
        listener.reportControllerState(
            deviceId, buttonFlags, leftStickX, leftStickY,
            rightStickX, rightStickY, leftTrigger, rightTrigger
        )
    }

    @Volatile private var transportReleaseFailure: Throwable? = null

    /** Keep teardown best-effort while retaining failures for exclusive USB handoff. */
    protected fun releaseUsbResource(release: () -> Unit) {
        try { release() } catch (error: Exception) {
            synchronized(this) {
                if (transportReleaseFailure == null) transportReleaseFailure = error
            }
        }
    }

    fun stopWithResult(onStopped: (Result<Unit>) -> Unit) {
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        fun complete(result: Result<Unit>) {
            if (delivered.compareAndSet(false, true)) onStopped(result)
        }
        try {
            stopAndThen {
                val error = transportReleaseFailure
                complete(if (error == null) Result.success(Unit) else Result.failure(error))
            }
        } catch (error: Exception) {
            complete(Result.failure(error))
        }
    }

    abstract fun start(): Boolean
    abstract fun stop()

    /** Runs [onStopped] after transport teardown finishes. Use stopWithResult to verify release succeeded. */
    open fun stopAndThen(onStopped: () -> Unit) {
        stop()
        onStopped()
    }

    abstract fun rumble(lowFreqMotor: Short, highFreqMotor: Short)

    abstract fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short)

    open val supportsAdaptiveTriggers: Boolean = false

    open fun setAdaptiveTriggers(
        eventFlags: Byte,
        typeLeft: Byte,
        typeRight: Byte,
        left: ByteArray,
        right: ByteArray
    ) = Unit

    open fun setControllerLED(r: Byte, g: Byte, b: Byte) = Unit

    protected fun notifyDeviceRemoved() {
        listener.deviceRemoved(this)
    }

    protected fun notifyDeviceAdded() {
        listener.deviceAdded(this)
    }

    protected fun notifyControllerMotion(motionType: Byte, x: Float, y: Float, z: Float) {
        listener.reportControllerMotion(deviceId, motionType, x, y, z)
    }

    protected fun notifyBatteryState(batteryState: Byte, batteryPercentage: Byte) {
        listener.reportControllerBattery(deviceId, batteryState, batteryPercentage)
    }

    protected fun isControllerReady(): Boolean = listener.isControllerReady(deviceId)

    protected fun notifyControllerTouch(eventType: Byte, pointerId: Int, x: Float, y: Float) {
        listener.reportControllerTouch(deviceId, eventType, pointerId, x, y)
    }

    /** Reset driver-side touch state after the host has received a cancellation. */
    open fun resetTouchState() {}
}
