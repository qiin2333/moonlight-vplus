package com.limelight.nvstream.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AdaptiveBitrateServiceTest {
    @Test
    fun stoppingDuringSamplingCannotIssueALegacyWriteAfterCleanup() {
        val http = FakeTransport(false)
        val executor = ManualExecutor()
        lateinit var service: AdaptiveBitrateService
        service = AdaptiveBitrateService({ http }, {
            service.stop()
            AdaptiveBitrateService.AbrStats(10f,10,60f,0)
        }, { _, _ -> fail("Stopped sample published") }, executor)
        service.start(20_000, AdaptiveBitrateService.MODE_BALANCED)
        executor.tick()
        assertFalse(service.enabled)
        assertTrue(http.bitrateWrites.isEmpty())
        assertTrue(http.modeWrites.isEmpty())
    }

    @Test
    fun stoppedServiceCannotPublishALateFeedbackTarget() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(14_000, "late", true)
            fixture.http.onFeedback = { fixture.service.stop() }
            repeat(5) { fixture.executor.tick() }
            assertFalse(fixture.service.enabled)
            assertEquals(20_000, fixture.service.currentBitrate)
            assertTrue(fixture.mirrors.isEmpty())
            assertFalse(fixture.service.serverSupported)
        } finally { fixture.service.stop() }
    }

    @Test
    fun stopDuringDiscoveryDoesNotResurrectAuthorityOrScheduleMoreTicks() {
        val http = FakeTransport(true)
        val executor = ManualExecutor()
        lateinit var service: AdaptiveBitrateService
        service = AdaptiveBitrateService({ http }, { null }, { _, _ -> fail("Stopped discovery published") }, executor)
        http.onCapabilities = { service.stop() }
        service.start(20_000, AdaptiveBitrateService.MODE_BALANCED)
        assertFalse(service.enabled)
        assertFalse(service.serverSupported)
        assertTrue(executor.isShutdown)
        service.start(30_000, AdaptiveBitrateService.MODE_BALANCED)
        assertEquals(20_000, service.currentBitrate)
        assertTrue(http.modeWrites.isEmpty())
    }

    @Test
    fun stopWaitsForTheActualHttpWorkerAndSuppressesItsLateDiscovery() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val http = FakeTransport(true).apply {
            onCapabilities = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        }
        val executor = ScheduledThreadPoolExecutor(1)
        val service = AdaptiveBitrateService({ http }, { null }, { _, _ -> fail("Late discovery published") }, executor)
        try {
            service.start(20_000, AdaptiveBitrateService.MODE_BALANCED)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            service.stop()
            assertFalse(service.awaitStopped(20, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(service.awaitStopped(2, TimeUnit.SECONDS))
            assertFalse(service.serverSupported)
            assertFalse(service.enabled)
            assertTrue(http.modeWrites.isEmpty())
        } finally { release.countDown(); service.stop(); assertTrue(service.awaitStopped(2, TimeUnit.SECONDS)) }
    }

    @Test
    fun serverFeedbackCommitsOnceWithoutASecondBitrateRequest() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(14_000, "loss", true)
            fixture.enableServer()
            assertEquals(1, fixture.http.serverCommits)
            assertTrue(fixture.http.bitrateWrites.isEmpty())
            assertEquals(14_000, fixture.service.currentBitrate)
            assertEquals(listOf(14_000), fixture.mirrors)
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun mirrorPreservesHostCapBelowClientMinimum() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(1500, "host cap", true)
            fixture.enableServer()
            assertEquals(1500, fixture.service.currentBitrate)
            assertEquals(listOf(1500), fixture.mirrors)
            assertTrue(fixture.http.bitrateWrites.isEmpty())
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun explicitSubmissionFailureDoesNotMirrorOrRetryTheWrite() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(14_000, "failed", false)
            fixture.enableServer()
            assertEquals(20_000, fixture.service.currentBitrate)
            assertTrue(fixture.mirrors.isEmpty())
            assertTrue(fixture.http.bitrateWrites.isEmpty())
            assertEquals(0, fixture.http.serverCommits)
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun oldHostWithoutSubmissionFlagKeepsTargetCompatibility() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(18_000, "old host")
            fixture.enableServer()
            assertEquals(18_000, fixture.service.currentBitrate)
            assertTrue(fixture.http.bitrateWrites.isEmpty())
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun unchangedAndInvalidServerTargetsProduceNoMirrorEvent() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(20_000, "unchanged", true)
            fixture.enableServer()
            fixture.http.action = AbrAction(0, "invalid", true)
            fixture.executor.tick()
            fixture.http.action = AbrAction(-1, "invalid", true)
            fixture.executor.tick()
            fixture.http.action = AbrAction(null, "no change", true)
            fixture.executor.tick()
            assertEquals(20_000, fixture.service.currentBitrate)
            assertTrue(fixture.mirrors.isEmpty())
            assertTrue(fixture.http.bitrateWrites.isEmpty())
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun duplicateServerTargetNotifiesOnlyOnce() {
        val fixture = Fixture()
        try {
            fixture.http.action = AbrAction(14_000, "loss", true)
            fixture.enableServer()
            fixture.executor.tick()
            assertEquals(listOf(14_000), fixture.mirrors)
            assertTrue(fixture.http.bitrateWrites.isEmpty())
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun unsupportedHostStillAppliesLocalFallbackOnce() {
        val fixture = Fixture(serverSupported = false, packetLoss = 10f)
        try {
            fixture.executor.tick()
            assertEquals(listOf(14_000), fixture.http.bitrateWrites)
            assertEquals(listOf(14_000), fixture.mirrors)
            assertEquals(14_000, fixture.service.currentBitrate)
            assertEquals(0, fixture.http.feedbackCalls)
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun failedLocalWriteKeepsPreviousMirror() {
        val fixture = Fixture(serverSupported = false, packetLoss = 10f)
        try {
            fixture.http.writeSucceeds = false
            fixture.executor.tick()
            assertEquals(listOf(14_000), fixture.http.bitrateWrites)
            assertEquals(20_000, fixture.service.currentBitrate)
            assertTrue(fixture.mirrors.isEmpty())
        } finally {
            fixture.service.stop()
        }
    }

    @Test
    fun manualOverrideAndStopRetainLegacyRestoreBehavior() {
        val fixture = Fixture(serverSupported = false)
        fixture.service.notifyManualOverride(30_000)
        assertEquals(30_000, fixture.service.currentBitrate)
        assertTrue(fixture.http.bitrateWrites.isEmpty())
        fixture.service.stop()
        assertFalse(fixture.service.enabled)
        assertEquals(listOf(20_000), fixture.http.bitrateWrites)
        assertEquals(20_000, fixture.service.currentBitrate)
        fixture.executor.tick()
        assertEquals(listOf(20_000), fixture.http.bitrateWrites)
    }

    @Test
    fun actionParserPreservesExplicitFalseAndLegacyAbsence() {
        val failed = AbrAction.fromJson(JSONObject("{\"newBitrate\":14000,\"bitrateApplied\":false}"))
        assertEquals(false, failed.bitrateApplied)
        val legacy = AbrAction.fromJson(JSONObject("{\"newBitrate\":14000}"))
        assertNull(legacy.bitrateApplied)
        val submitted = AbrAction.fromJson(JSONObject("{\"newBitrate\":14000,\"bitrateApplied\":true}"))
        assertEquals(true, submitted.bitrateApplied)
    }

    @Test
    fun malformedSubmissionFlagCannotConfirmATarget() {
        val malformed = AbrAction.fromJson(JSONObject("{\"newBitrate\":14000,\"bitrateApplied\":17}"))
        assertEquals(false, malformed.bitrateApplied)
        val nullFlag = AbrAction.fromJson(JSONObject("{\"newBitrate\":14000,\"bitrateApplied\":null}"))
        assertEquals(false, nullFlag.bitrateApplied)
    }

    private class Fixture(serverSupported: Boolean = true, packetLoss: Float = 0f) {
        val http = FakeTransport(serverSupported)
        val executor = ManualExecutor()
        val mirrors = mutableListOf<Int>()
        val service = AdaptiveBitrateService(
            transportFactory = { http },
            statsProvider = { AdaptiveBitrateService.AbrStats(packetLoss, 10, 60f, 0) },
            onBitrateChanged = { bitrate, _ -> mirrors += bitrate },
            executor = executor
        )

        init {
            service.start(20_000, AdaptiveBitrateService.MODE_BALANCED)
        }

        fun enableServer() {
            repeat(5) { executor.tick() }
            assertEquals(listOf(true), http.modeWrites)
            assertEquals(1, http.feedbackCalls)
        }
    }

    private class FakeTransport(private val serverSupported: Boolean) : AbrTransport {
        val bitrateWrites = mutableListOf<Int>()
        val modeWrites = mutableListOf<Boolean>()
        var action: AbrAction? = null
        var writeSucceeds = true
        var feedbackCalls = 0
        var serverCommits = 0
        var onFeedback: (() -> Unit)? = null
        var onCapabilities: (() -> Unit)? = null

        override fun getAbrCapabilities(): AbrCapabilities {
            onCapabilities?.invoke()
            return AbrCapabilities(serverSupported, 1, emptyList())
        }
        override fun setAbrMode(config: AbrConfig): Boolean {
            modeWrites += config.enabled
            return true
        }

        override fun reportNetworkFeedback(feedback: NetworkFeedback): AbrAction? {
            feedbackCalls++
            onFeedback?.invoke()
            if ((action?.newBitrate ?: 0) > 0 && action?.bitrateApplied != false) serverCommits++
            return action
        }

        override fun setBitrate(kbps: Int): Boolean {
            bitrateWrites += kbps
            return writeSucceeds
        }
    }

    /** Runs discovery and scheduled ticks explicitly, without wall-clock waits. */
    private class ManualExecutor : ScheduledThreadPoolExecutor(1) {
        private var scheduledTick: Runnable? = null
        override fun execute(command: Runnable) = command.run()
        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit
        ): ScheduledFuture<*> {
            scheduledTick = command
            return object : ScheduledFuture<Unit> {
                private var cancelled = false
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                    cancelled = true
                    return true
                }
                override fun isCancelled() = cancelled
                override fun isDone() = cancelled
                override fun get() = Unit
                override fun get(timeout: Long, unit: TimeUnit) = Unit
                override fun getDelay(unit: TimeUnit) = 0L
                override fun compareTo(other: Delayed) = 0
            }
        }

        fun tick() = checkNotNull(scheduledTick).run()
    }
}
