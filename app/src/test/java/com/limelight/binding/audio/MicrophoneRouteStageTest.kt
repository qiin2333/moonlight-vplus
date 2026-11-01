package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MicrophoneRouteStageTest {
    @Test fun waitingDoesNotImplySuccessfulConnection() {
        val stage = MicrophoneRouteStage()
        assertNull(stage.result)
        stage.complete(true)
        assertEquals(true, stage.result)
    }

    @Test fun duplicateCallbackCannotTurnFailureIntoSuccess() {
        val stage = MicrophoneRouteStage()
        stage.complete(false)
        stage.complete(true)
        assertEquals(false, stage.result)
    }

    @Test fun cancelledRequestRejectsLateConnectionCallback() {
        val old = MicrophoneRouteStage()
        old.cancel()
        val next = MicrophoneRouteStage()
        old.complete(true)
        assertEquals(false, old.result)
        assertNull(next.result)
        next.complete(true)
        assertEquals(true, next.result)
    }

    @Test fun cancellationOverridesSuccessBeforeItIsConsumed() {
        val stage = MicrophoneRouteStage()
        stage.complete(true)
        stage.cancel()
        assertEquals(false, stage.result)
    }

    @Test fun releasesTheRecorderBeforeRequestingTheRouteWithoutWaitingForCallback() {
        val stage = MicrophoneRouteStage()
        val events = mutableListOf<String>()
        var callback: ((Boolean) -> Unit)? = null
        stage.start({ true },
            beforeRequest = { events.add("release") },
            request = {
                events.add("request")
                callback = it
            })

        assertEquals(listOf("release", "request"), events)
        assertNotNull(callback)
        assertNull(stage.result)
        callback!!(true)
        assertEquals(true, stage.result)
    }

    @Test fun synchronousRouteCompletionIsAlsoAccepted() {
        val stage = MicrophoneRouteStage()
        var released = false
        stage.start({ true }, { released = true }) { done ->
            assertTrue(released)
            done(true)
        }
        assertEquals(true, stage.result)
    }

    @Test fun alreadyCancelledStageDoesNotTouchTheRecorderOrRoute() {
        val stage = MicrophoneRouteStage()
        stage.cancel()
        stage.start({ true }, { fail("Must not release") }) { fail("Must not request") }
        assertEquals(false, stage.result)
    }

    @Test fun staleRequestDoesNotTouchTheRecorderOrRoute() {
        val stage = MicrophoneRouteStage()
        stage.start({ false }, { fail("Must not release") }) { fail("Must not request") }
        assertEquals(false, stage.result)
    }

    @Test fun stopDuringReleasePreventsTheRouteRequest() {
        val stage = MicrophoneRouteStage()
        var current = true
        stage.start({ current }, { current = false }) { fail("Must not request after stop") }
        assertEquals(false, stage.result)
    }

    @Test fun releaseFailureDoesNotStartTheRoute() {
        val stage = MicrophoneRouteStage()
        stage.start({ true }, { throw IllegalStateException("release failed") }) {
            fail("Must not request before release succeeds")
        }
        assertEquals(false, stage.result)
    }

    @Test fun requestFailureCompletesTheStageForRecovery() {
        val stage = MicrophoneRouteStage()
        stage.start({ true }, {}) { throw IllegalStateException("route failed") }
        assertEquals(false, stage.result)
    }

    @Test fun callbackFromAStoppedCaptureCannotReadyTheStage() {
        val stage = MicrophoneRouteStage()
        var current = true
        lateinit var callback: (Boolean) -> Unit
        stage.start({ current }, {}) { callback = it }
        current = false
        callback(true)
        assertEquals(false, stage.result)
    }

    @Test fun repeatedStartDoesNotReleaseOrRequestAgain() {
        val stage = MicrophoneRouteStage()
        var releases = 0
        var requests = 0
        stage.start({ true }, { releases++ }) { requests++ }
        stage.start({ true }, { releases++ }) { requests++ }
        assertEquals(1, releases)
        assertEquals(1, requests)
        assertNull(stage.result)
    }

    @Test fun rollbackReleasesTheFailedRecorderBeforeRestoringThePreviousRoute() {
        val target = MicrophoneRouteStage()
        val restore = MicrophoneRouteStage()
        val events = mutableListOf<String>()
        target.start({ true }, { events.add("release-old") }) { done ->
            events.add("target-route")
            done(true)
        }
        assertEquals(true, target.result)
        events.add("create-target")

        lateinit var restored: (Boolean) -> Unit
        restore.start({ true }, { events.add("release-target") }) {
            events.add("restore-route")
            restored = it
        }
        assertNull(restore.result)
        assertEquals(listOf("release-old", "target-route", "create-target",
            "release-target", "restore-route"), events)
        restored(true)
        assertEquals(true, restore.result)
    }

    @Test fun initialBluetoothOpensOnTheCaptureOwnerOnlyAfterTheAsyncRouteCallback() {
        val stage = MicrophoneRouteStage()
        val requested = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        lateinit var connected: (Boolean) -> Unit
        var owner: Thread? = null
        var openingThread: Thread? = null
        val worker = Executors.newSingleThreadExecutor()
        try {
            val completion = worker.submit<Boolean> {
                owner = Thread.currentThread()
                stage.start({ true }, {}) {
                    connected = it
                    requested.countDown()
                }
                stage.openWhenReady({ true }) {
                    openingThread = Thread.currentThread()
                    opened.set(true)
                }
            }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            assertFalse(opened.get())
            assertFalse(completion.isDone)
            connected(true)
            assertTrue(completion.get(2, TimeUnit.SECONDS))
            assertTrue(opened.get())
            assertSame(owner, openingThread)
            assertNotSame(Thread.currentThread(), openingThread)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun failedInitialRouteNeverOpensADefaultRecorder() {
        val stage = MicrophoneRouteStage()
        stage.start({ true }, {}) { it(false) }
        assertFalse(stage.openWhenReady({ true }) { fail("No default recorder is allowed") })
    }

    @Test fun stoppingDuringInitialRouteWaitPreventsRecorderCreationAndLateReopening() {
        val stage = MicrophoneRouteStage()
        val current = AtomicBoolean(true)
        val requested = CountDownLatch(1)
        lateinit var connected: (Boolean) -> Unit
        val worker = Executors.newSingleThreadExecutor()
        try {
            val completion = worker.submit<Boolean> {
                stage.start(current::get, {}) {
                    connected = it
                    requested.countDown()
                }
                stage.openWhenReady(current::get) { fail("Stopped capture cannot open") }
            }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            current.set(false)
            stage.cancel()
            assertFalse(completion.get(2, TimeUnit.SECONDS))
            connected(true)
            assertFalse(stage.openWhenReady(current::get) { fail("Late callback cannot reopen") })
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun duplicateInitialConsumptionCannotCreateAnotherRecorder() {
        val stage = MicrophoneRouteStage()
        stage.start({ true }, {}) { it(true) }
        var opened = 0
        assertTrue(stage.openWhenReady({ true }) { opened++ })
        assertFalse(stage.openWhenReady({ true }) { opened++ })
        assertEquals(1, opened)
    }

    @Test fun initializationExceptionCannotRetryAnAlreadyConsumedRouteStage() {
        val stage = MicrophoneRouteStage()
        stage.start({ true }, {}) { it(true) }
        try {
            stage.openWhenReady({ true }) { throw IllegalStateException("Recorder failed") }
            fail("Recorder failure must reach the capture owner's cleanup")
        } catch (_: IllegalStateException) { }
        assertFalse(stage.openWhenReady({ true }) { fail("Must not initialize again") })
    }
}
