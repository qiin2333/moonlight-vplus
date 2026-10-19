package com.limelight

import com.limelight.DynamicResolutionManager.Companion.DEBOUNCE_MS
import com.limelight.DynamicResolutionManager.Companion.IN_FLIGHT_TIMEOUT_MS
import com.limelight.DynamicResolutionManager.Companion.mapToStreamSize
import com.limelight.DynamicResolutionManager.StreamSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicResolutionManagerTest {
    private val scheduler = FakeScheduler()
    private val sent = mutableListOf<StreamSize>()
    private var sendResult = 0
    private val manager = DynamicResolutionManager({ w, h ->
        sent += StreamSize(w, h)
        sendResult
    }, scheduler)

    /** Starts a stream; the host's stream-start report says its display is [displayWidth]x[displayHeight]. */
    private fun connect(
        width: Int, height: Int, scale: Int = 100,
        displayWidth: Int = width * scale / 100, displayHeight: Int = height * scale / 100,
    ) {
        manager.onConnected(width, height, scale)
        report(displayWidth, displayHeight)
    }

    private fun report(width: Int, height: Int) {
        manager.onHostResolutionChanged(width, height)
        scheduler.advance(0)
    }

    @Test
    fun mapsScaledEvenSizes() {
        assertEquals(StreamSize(2400, 1080), mapToStreamSize(2400, 1080, 100))
        assertEquals(StreamSize(1080, 2400), mapToStreamSize(1081, 2401, 100))
        assertEquals(StreamSize(1200, 540), mapToStreamSize(2400, 1080, 50))
    }

    @Test
    fun rejectsSizesBelowMinimumInsteadOfStretchingThem() {
        assertNull(mapToStreamSize(400, 200, 100))
        assertNull(mapToStreamSize(600, 300, 50))
        assertNull(mapToStreamSize(0, 0, 100))
    }

    @Test
    fun shrinksOversizedRequestsKeepingAspectRatio() {
        assertEquals(StreamSize(7680, 4320), mapToStreamSize(15360, 8640, 100))
        assertEquals(StreamSize(2160, 7680), mapToStreamSize(4320, 15360, 100))
        assertEquals(StreamSize(7680, 3456), mapToStreamSize(2400, 1080, 400))
    }

    @Test
    fun sendsOnlyTheSettledSizeOfABurst() {
        connect(1920, 1080)
        manager.request(1000, 800)
        scheduler.advance(DEBOUNCE_MS - 1)
        manager.request(1200, 800)
        scheduler.advance(DEBOUNCE_MS - 1)
        manager.request(1400, 800)
        scheduler.advance(DEBOUNCE_MS - 1)
        assertEquals(emptyList<StreamSize>(), sent)

        scheduler.advance(1)
        assertEquals(listOf(StreamSize(1400, 800)), sent)
    }

    @Test
    fun appliesResolutionScaleToRequests() {
        connect(1920, 1080, scale = 50)
        manager.request(2400, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1200, 540)), sent)
    }

    @Test
    fun ignoresRequestsWhileDisconnected() {
        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)

        connect(1920, 1080)
        manager.request(1280, 720)
        manager.onDisconnected()
        scheduler.advance(DEBOUNCE_MS)

        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun skipsSizeTheHostIsAlreadyStreaming() {
        connect(1920, 1080, scale = 50)
        manager.request(1920, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun holdsNewerSizeUntilHostReportsTheChange() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        manager.request(2400, 1080)
        manager.request(1600, 1200)
        scheduler.advance(IN_FLIGHT_TIMEOUT_MS - 1)
        assertEquals(listOf(StreamSize(1080, 2400)), sent)

        manager.onHostResolutionChanged(1080, 2400)
        // Re-debounced after the change so the host's 250 ms rate limit can't drop it.
        scheduler.advance(DEBOUNCE_MS - 1)
        assertEquals(1, sent.size)
        scheduler.advance(1)
        assertEquals(listOf(StreamSize(1080, 2400), StreamSize(1600, 1200)), sent)
    }

    @Test
    fun stopsWaitingForTheHostAfterTimeout() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        manager.request(2400, 1080)
        scheduler.advance(IN_FLIGHT_TIMEOUT_MS)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400), StreamSize(2400, 1080)), sent)
    }

    @Test
    fun neverResendsAnUnansweredSize() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS + IN_FLIGHT_TIMEOUT_MS)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400)), sent)

        manager.request(2400, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400), StreamSize(2400, 1080)), sent)
    }

    @Test
    fun resendsASizeOnceTheStreamHasMovedOn() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        report(1080, 2400)
        manager.requestLaunchSize()
        scheduler.advance(DEBOUNCE_MS)
        report(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(
            listOf(StreamSize(1080, 2400), StreamSize(1920, 1080), StreamSize(1080, 2400)),
            sent,
        )
    }

    @Test
    fun scaledSessionHoldsRequestsUntilHostStreamsItsDisplay1To1() {
        // The stream-start report is the host display size, not the stream size.
        connect(1920, 1080, displayWidth = 2560, displayHeight = 1440)
        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)

        // The stream doesn't follow a display it wasn't streaming 1:1; it stays 1920x1080.
        report(1920, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1280, 720)), sent)
    }

    @Test
    fun displayChangeInScaledSessionDoesNotChangeStreamSize() {
        connect(1920, 1080, displayWidth = 2560, displayHeight = 1440)
        // Stream stays 1920x1080, so the host isn't 1:1 with its new 1280x720 display.
        report(1280, 720)
        manager.request(1600, 900)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun rotatedDisplayCountsAs1To1() {
        connect(1920, 1080, displayWidth = 1080, displayHeight = 1920)
        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1280, 720)), sent)
    }

    @Test
    fun reportBeforeOnConnectedIsTheStreamStartReport() {
        report(2560, 1440)
        manager.onConnected(1920, 1080, 100)
        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun reportQueuedBeforeDisconnectDoesNotLeakIntoNextConnection() {
        connect(1920, 1080)
        manager.onHostResolutionChanged(1920, 1080)
        manager.onDisconnected()
        manager.onConnected(1920, 1080, 100)
        manager.onHostResolutionChanged(2560, 1440)
        scheduler.advance(0)

        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun unsupportedResultTurnsRequestsOffForTheConnection() {
        connect(1920, 1080)
        sendResult = -2
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        manager.request(2400, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400)), sent)
    }

    @Test
    fun failedSendDoesNotBlockLaterRequests() {
        connect(1920, 1080)
        sendResult = -4
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        sendResult = 0
        manager.request(2400, 1080)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400), StreamSize(2400, 1080)), sent)
    }

    @Test
    fun restoresLaunchSize() {
        connect(1920, 1080)
        manager.request(3840, 2160)
        scheduler.advance(DEBOUNCE_MS)
        report(3840, 2160)

        manager.requestLaunchSize()
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(3840, 2160), StreamSize(1920, 1080)), sent)
    }

    @Test
    fun reconnectDropsQueuedSizeFromPreviousStream() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        manager.request(2400, 1080)
        manager.onDisconnected()
        connect(1920, 1080)
        scheduler.advance(IN_FLIGHT_TIMEOUT_MS + DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400)), sent)
    }

    @Test
    fun rejectedLatestSizeCancelsEarlierPendingRequest() {
        connect(1920, 1080)
        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS / 2)
        manager.request(200, 112)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun cancelPendingDropsUnsentRequestOnly() {
        connect(1920, 1080)
        manager.request(1080, 2400)
        scheduler.advance(DEBOUNCE_MS)
        manager.request(1280, 720)
        manager.cancelPending()
        scheduler.advance(IN_FLIGHT_TIMEOUT_MS + DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400)), sent)

        manager.request(1280, 720)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1080, 2400), StreamSize(1280, 720)), sent)
    }

    private class FakeScheduler : DynamicResolutionManager.Scheduler {
        private class Task(val runnable: Runnable, val at: Long)

        private val tasks = mutableListOf<Task>()
        private var now = 0L

        override fun post(task: Runnable) = postDelayed(task, 0)

        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += Task(task, now + delayMs)
        }

        override fun cancel(task: Runnable) {
            tasks.removeAll { it.runnable === task }
        }

        /** Runs due tasks in time order; ties run in posting order, like a Handler. */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                next.runnable.run()
            }
            now = end
        }
    }
}
