package com.limelight

import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener

/**
 * Binds connection lifecycle callbacks to one [NvConnection].
 *
 * Native callbacks can arrive after a reconnect has replaced the Activity's
 * current connection. Lifecycle callbacks are posted through the UI boundary
 * and discarded when their originating connection is no longer current. The
 * remaining stream callbacks are delegated unchanged; MoonBridge detaches this
 * listener before replacing it.
 */
internal class StreamConnectionListenerAdapter(
    private val connection: NvConnection,
    private val delegate: NvConnectionListener,
    private val postToUi: (() -> Unit) -> Unit,
    private val isCurrent: (NvConnection) -> Boolean,
) : NvConnectionListener by delegate {

    private fun dispatchLifecycle(action: () -> Unit) {
        postToUi {
            if (isCurrent(connection)) action()
        }
    }

    override fun stageStarting(stage: String) =
        dispatchLifecycle { delegate.stageStarting(stage) }

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) =
        dispatchLifecycle { delegate.stageFailed(stage, portFlags, errorCode) }

    override fun connectionStarted() =
        dispatchLifecycle { delegate.connectionStarted() }

    override fun connectionTerminated(errorCode: Int) =
        dispatchLifecycle { delegate.connectionTerminated(errorCode) }

    override fun connectionStatusUpdate(connectionStatus: Int) =
        dispatchLifecycle { delegate.connectionStatusUpdate(connectionStatus) }
}
