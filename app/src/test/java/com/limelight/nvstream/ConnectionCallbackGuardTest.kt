package com.limelight.nvstream

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ConnectionCallbackGuardTest {
    private class UiQueue {
        val work = ArrayDeque<() -> Unit>()
        fun enqueue(action: () -> Unit) { work.addLast(action) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst()() }
    }

    private fun listener(events: MutableList<String>): NvConnectionListener =
        Proxy.newProxyInstance(NvConnectionListener::class.java.classLoader,
            arrayOf(NvConnectionListener::class.java)) { _, method, _ ->
            events += method.name
            null
        } as NvConnectionListener

    private class Lifecycle(private val events: MutableList<String>) : ConnectionLifecycleCallbacks {
        override fun stageStarting(stage: String) { events += "starting:$stage" }
        override fun stageComplete(stage: String) { events += "complete:$stage" }
        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) { events += "failed:$stage:$portFlags:$errorCode" }
        override fun connectionStarted() { events += "started" }
        override fun connectionTerminated(errorCode: Int) { events += "terminated:$errorCode" }
        override fun connectionStatusUpdate(connectionStatus: Int) { events += "status:$connectionStatus" }
    }

    @Test fun nativeLifecycleRoutesToTheCapturedHandlerAndNeverToTheMutableDelegate() {
        val captured = mutableListOf<String>()
        val delegate = mutableListOf<String>()
        val scoped = ScopedConnectionListener(ConnectionCallbackGuard { true }, listener(delegate), Lifecycle(captured), { it() })
        scoped.stageStarting("video"); scoped.stageComplete("audio"); scoped.stageFailed("control", 3, -4)
        scoped.connectionStarted(); scoped.connectionTerminated(-5); scoped.connectionStatusUpdate(6)
        assertEquals(listOf("starting:video", "complete:audio", "failed:control:3:-4", "started", "terminated:-5", "status:6"), captured)
        assertTrue(delegate.isEmpty())
    }

    @Test fun everyLateNativeLifecycleCallbackIsDiscardedAfterReplacement() {
        val owner = AtomicReference("old")
        val events = mutableListOf<String>()
        val scoped = ScopedConnectionListener(ConnectionCallbackGuard { owner.get() == "old" }, listener(events), Lifecycle(events), { it() })
        owner.set("new")
        scoped.stageStarting("video"); scoped.stageComplete("audio"); scoped.stageFailed("control", 3, -4)
        scoped.connectionStarted(); scoped.connectionTerminated(-5); scoped.connectionStatusUpdate(6)
        assertTrue(events.isEmpty())
    }

    @Test fun alreadyQueuedStartedTerminatedAndStatusWorkCannotMutateTheNewConnection() {
        val owner = AtomicReference("old")
        val guard = ConnectionCallbackGuard { owner.get() == "old" }
        val ui = UiQueue()
        val state = mutableListOf<String>()
        for (event in listOf("started", "terminated", "poor network")) guard.post(ui::enqueue) { state += event }
        assertEquals(3, ui.work.size)
        owner.set("new")
        ui.drain()
        assertTrue(state.isEmpty())
        ConnectionCallbackGuard { owner.get() == "new" }.post(ui::enqueue) { state += "new started" }
        ui.drain()
        assertEquals(listOf("new started"), state)
    }

    @Test fun explicitStopRevokesQueuedWorkEvenWhenTheActivityRetainsItsConnection() {
        val guard = ConnectionCallbackGuard { true }
        val ui = UiQueue()
        val events = mutableListOf<String>()
        guard.post(ui::enqueue) { events += "old started" }
        assertTrue(guard.revoke())
        assertFalse(guard.revoke())
        guard.post(ui::enqueue) { events += "after stop" }
        guard.dispatch { events += "native after stop" }
        ui.drain()
        assertTrue(events.isEmpty())
    }

    @Test fun connectivityCheckFinishingAfterReplacementDoesNotPublishItsFailure() {
        val owner = AtomicReference("old")
        val guard = ConnectionCallbackGuard { owner.get() == "old" }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val publications = mutableListOf<String>()
        val error = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                guard.dispatch {
                    entered.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                    guard.post({ it() }) { publications += "old failure" }
                }
            } catch (e: Throwable) { error.set(e) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.set("new")
        } finally { finish.countDown(); worker.join(2000) }
        assertFalse(worker.isAlive)
        assertNull(error.get())
        assertTrue(publications.isEmpty())
    }

    @Test fun firstFrameAndPointerWorkRecheckOwnershipAtTheDelayedExecution() {
        val owner = AtomicReference("old")
        val guard = ConnectionCallbackGuard { owner.get() == "old" }
        val ui = UiQueue()
        val delayed = UiQueue()
        val state = mutableListOf<String>()
        guard.post(ui::enqueue) {
            delayed.enqueue { guard.post(ui::enqueue) { state += "dismiss loading" } }
            delayed.enqueue { guard.post(ui::enqueue) { state += "grab pointer" } }
        }
        ui.drain()
        assertEquals(2, delayed.work.size)
        owner.set("new")
        delayed.drain(); ui.drain()
        assertTrue(state.isEmpty())
    }

    @Test fun queuedStopCannotStopTheReplacementAndCurrentStopHappensOnlyOnce() {
        val owner = AtomicReference("old")
        val ui = UiQueue()
        val stops = mutableListOf<String>()
        val old = ConnectionCallbackGuard { owner.get() == "old" }
        old.post(ui::enqueue) { if (old.revoke()) stops += "old" }
        owner.set("new")
        ui.drain()
        assertTrue(stops.isEmpty())
        val current = ConnectionCallbackGuard { owner.get() == "new" }
        repeat(2) { current.post(ui::enqueue) { if (current.revoke()) stops += "new" } }
        ui.drain()
        assertEquals(listOf("new"), stops)
    }

    private fun argument(type: Class<*>): Any = when (type) {
        String::class.java -> "stage"
        Int::class.javaPrimitiveType -> 7
        Short::class.javaPrimitiveType -> 1.toShort()
        Byte::class.javaPrimitiveType -> 1.toByte()
        Boolean::class.javaPrimitiveType -> true
        ByteArray::class.java -> byteArrayOf(1, 2)
        Ds5HapticsPcmFrame::class.java -> Ds5HapticsPcmFrame(0, 0, 1, 100, 48000, 1, 2, 16, byteArrayOf(1, 2, 3, 4))
        RemoteTextContext::class.java -> RemoteTextContext(1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1920, 1080)
        else -> error("Add coverage for new listener argument ${type.name}")
    }

    @Test fun everyListenerChannelIsScopedAndQueuedUiChannelsAreDiscardedAfterReplacement() {
        val owner = AtomicReference("old")
        val ui = UiQueue()
        val immediate = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val delegate = listener(deferred)
        val scoped = ScopedConnectionListener(ConnectionCallbackGuard { owner.get() == "old" },
            delegate, Lifecycle(immediate), ui::enqueue)
        val methods = NvConnectionListener::class.java.declaredMethods
        assertEquals("Every native listener channel must be part of this test", 18, methods.size)
        for (method in methods) method.invoke(scoped, *method.parameterTypes.map(::argument).toTypedArray())
        assertEquals(6, immediate.size)
        assertEquals(6, deferred.size) // controller channels stay on their calling thread
        assertEquals(6, ui.work.size)
        owner.set("new")
        ui.drain()
        assertEquals(6, deferred.size)
        immediate.clear(); deferred.clear()
        for (method in methods) method.invoke(scoped, *method.parameterTypes.map(::argument).toTypedArray())
        ui.drain()
        assertTrue(immediate.isEmpty())
        assertTrue(deferred.isEmpty())
        assertTrue(ui.work.isEmpty())
    }

    @Test fun hdrAndCursorQueuedPayloadsCannotBeChangedByTheCallingThread() {
        val ui = UiQueue()
        val received = mutableMapOf<String, ByteArray>()
        val delegate = Proxy.newProxyInstance(NvConnectionListener::class.java.classLoader,
            arrayOf(NvConnectionListener::class.java)) { _, method, args ->
            if (method.name == "setHdrMode") received["hdr"] = args!![1] as ByteArray
            if (method.name == "onCursorUpdate") received["cursor"] = args!![6] as ByteArray
            null
        } as NvConnectionListener
        val scoped = ScopedConnectionListener(ConnectionCallbackGuard { true }, delegate,
            Lifecycle(mutableListOf()), ui::enqueue)
        val hdr = byteArrayOf(1, 2)
        val pixels = byteArrayOf(3, 4)
        scoped.setHdrMode(true, hdr)
        scoped.onCursorUpdate(1, 1, 1, 1, 0, 0, pixels)
        hdr.fill(9); pixels.fill(9)
        ui.drain()
        assertArrayEquals(byteArrayOf(1, 2), received["hdr"])
        assertArrayEquals(byteArrayOf(3, 4), received["cursor"])
    }

    @Test fun hapticsIsDeliveredImmediatelyToTheCapturedDelegateAndRevokedBeforeAnyLaterFrame() {
        val ui = UiQueue()
        val guard = ConnectionCallbackGuard { true }
        val events = mutableListOf<String>()
        val callingThread = Thread.currentThread()
        var observedThread: Thread? = null
        val delegate = Proxy.newProxyInstance(NvConnectionListener::class.java.classLoader,
            arrayOf(NvConnectionListener::class.java)) { _, method, _ ->
            events += method.name; observedThread = Thread.currentThread(); null
        } as NvConnectionListener
        val scoped = ScopedConnectionListener(guard, delegate, Lifecycle(mutableListOf()), ui::enqueue)
        val frame = argument(Ds5HapticsPcmFrame::class.java) as Ds5HapticsPcmFrame
        scoped.ds5HapticsPcm(frame)
        assertEquals(listOf("ds5HapticsPcm"), events)
        assertSame(callingThread, observedThread)
        assertTrue(ui.work.isEmpty())
        guard.revoke()
        scoped.ds5HapticsPcm(frame)
        assertEquals(listOf("ds5HapticsPcm"), events)
    }

    @Test fun cleanupFailureStillStopsTheOriginalAttemptExactlyOnceAndRevokesQueuedWork() {
        val guard = ConnectionCallbackGuard { true }
        val ui = UiQueue()
        val events = mutableListOf<String>()
        guard.post(ui::enqueue) { events += "late started" }
        val failure = IllegalStateException("cleanup failed")
        try {
            guard.stop({ events += "cleanup"; throw failure }, { events += "stop original" })
            fail("Cleanup failure must remain visible")
        } catch (e: IllegalStateException) { assertSame(failure, e) }
        guard.stop({ events += "cleanup twice" }, { events += "stop twice" })
        ui.drain()
        assertEquals(listOf("cleanup", "stop original"), events)
    }
}
