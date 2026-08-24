package com.limelight.binding.input.driver

import com.limelight.binding.input.haptics.DualSenseNativeHapticsSink
import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseNativeHapticsOwnerTest {
    @Test
    fun closeDetachesRoutingBeforeStoppingTransport() {
        val events = mutableListOf<String>()
        val sink = RecordingSink(events)
        val owner = DualSenseNativeHapticsOwner(
            onAvailable = { events += "available" },
            onGone = { events += "gone" }
        )

        owner.install(sink)
        owner.announce()
        owner.close()

        assertEquals(listOf("available", "gone", "stop"), events)
    }

    @Test
    fun closeBeforeAnnouncementStillStopsTransportWithoutGoneCallback() {
        val events = mutableListOf<String>()
        val owner = DualSenseNativeHapticsOwner(
            onAvailable = { events += "available" },
            onGone = { events += "gone" }
        )

        owner.install(RecordingSink(events))
        owner.close()

        assertEquals(listOf("stop"), events)
    }

    @Test
    fun repeatedCloseAndLateInstallStopEachSinkOnce() {
        val events = mutableListOf<String>()
        val owner = DualSenseNativeHapticsOwner(
            onAvailable = { events += "available" },
            onGone = { events += "gone" }
        )

        owner.install(RecordingSink(events, "first"))
        owner.announce()
        owner.close()
        owner.close()
        owner.install(RecordingSink(events, "late"))

        assertEquals(listOf("available", "gone", "first", "late"), events)
    }

    @Test
    fun goneCallbackFailureStillStopsTransport() {
        val events = mutableListOf<String>()
        val owner = DualSenseNativeHapticsOwner(
            onAvailable = { events += "available" },
            onGone = {
                events += "gone"
                error("callback failure")
            }
        )

        owner.install(RecordingSink(events))
        owner.announce()
        val result = runCatching { owner.close() }

        assertTrue(result.isFailure)
        assertEquals(listOf("available", "gone", "stop"), events)
    }

    private class RecordingSink(
        private val events: MutableList<String>,
        private val stopEvent: String = "stop"
    ) : DualSenseNativeHapticsSink {
        override fun start(): Boolean = true

        override fun submit(frame: Ds5HapticsPcmFrame) = Unit

        override fun stop() {
            events += stopEvent
        }
    }
}
