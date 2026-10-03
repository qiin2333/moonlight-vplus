package com.limelight.nvstream

interface ConnectionLifecycleCallbacks {
    fun stageStarting(stage: String)
    fun stageComplete(stage: String)
    fun stageFailed(stage: String, portFlags: Int, errorCode: Int)
    fun connectionStarted()
    fun connectionTerminated(errorCode: Int)
    fun connectionStatusUpdate(connectionStatus: Int)
}

/** Freeze all listener routing at start; UI callbacks recheck ownership when executed. */
internal class ScopedConnectionListener(
    private val guard: ConnectionCallbackGuard,
    private val delegate: NvConnectionListener,
    private val lifecycle: ConnectionLifecycleCallbacks,
    private val enqueueUi: (() -> Unit) -> Unit
) : NvConnectionListener {
    override fun stageStarting(stage: String) = guard.dispatch { lifecycle.stageStarting(stage) }
    override fun stageComplete(stage: String) = guard.dispatch { lifecycle.stageComplete(stage) }
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) =
        guard.dispatch { lifecycle.stageFailed(stage, portFlags, errorCode) }
    override fun connectionStarted() = guard.dispatch { lifecycle.connectionStarted() }
    override fun connectionTerminated(errorCode: Int) = guard.dispatch { lifecycle.connectionTerminated(errorCode) }
    override fun connectionStatusUpdate(connectionStatus: Int) = guard.dispatch { lifecycle.connectionStatusUpdate(connectionStatus) }

    override fun displayMessage(message: String) = guard.post(enqueueUi) { delegate.displayMessage(message) }
    override fun displayTransientMessage(message: String) = guard.post(enqueueUi) { delegate.displayTransientMessage(message) }

    // These callbacks stay on the native thread; the delegate captures this connection's controller.
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) =
        guard.dispatch { delegate.rumble(controllerNumber, lowFreqMotor, highFreqMotor) }
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) =
        guard.dispatch { delegate.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger) }
    override fun setAdaptiveTriggers(controllerNumber: Short, eventFlags: Byte, typeLeft: Byte, typeRight: Byte,
        left: ByteArray, right: ByteArray) =
        guard.dispatch { delegate.setAdaptiveTriggers(controllerNumber, eventFlags, typeLeft, typeRight, left, right) }
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) =
        guard.dispatch { delegate.setMotionEventState(controllerNumber, motionType, reportRateHz) }
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) =
        guard.dispatch { delegate.setControllerLED(controllerNumber, r, g, b) }
    override fun ds5HapticsPcm(frame: Ds5HapticsPcmFrame) = guard.dispatch { delegate.ds5HapticsPcm(frame) }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        val metadata = hdrMetadata?.copyOf()
        guard.post(enqueueUi) { delegate.setHdrMode(enabled, metadata) }
    }
    override fun onResolutionChanged(width: Int, height: Int) =
        guard.post(enqueueUi) { delegate.onResolutionChanged(width, height) }
    override fun onCursorUpdate(flags: Int, shapeId: Int, width: Int, height: Int,
        hotspotX: Int, hotspotY: Int, bgraPixels: ByteArray?) {
        val pixels = bgraPixels?.copyOf()
        guard.post(enqueueUi) { delegate.onCursorUpdate(flags, shapeId, width, height, hotspotX, hotspotY, pixels) }
    }
    override fun onRemoteTextContext(context: RemoteTextContext) =
        guard.post(enqueueUi) { delegate.onRemoteTextContext(context) }
}
