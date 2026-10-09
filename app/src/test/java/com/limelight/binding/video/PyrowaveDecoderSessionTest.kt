package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PyrowaveDecoderSessionTest {
    @Test
    fun createFailureDoesNotExposeAHandle() {
        val native = FakeNative(createResult = 0L)
        val failures = AtomicInteger()
        val session = PyrowaveDecoderSession(native, onFatalFailure = { failures.incrementAndGet() })

        assertFalse(session.create(1920, 1080))
        assertFalse(session.isActive)
        assertEquals(0, native.destroyed.size)
        assertEquals(0, failures.get())
    }

    @Test
    fun submitFailuresDoNotResetUntilAFrameSucceeds() {
        val native = FakeNative(submitResult = -1)
        val failures = AtomicInteger()
        val session = PyrowaveDecoderSession(native, onFatalFailure = { failures.incrementAndGet() })
        val frame = byteArrayOf(1, 2, 3)

        assertTrue(session.create(1920, 1080))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))
        assertEquals(PyrowaveDecoderSession.SubmitResult.FATAL, session.submit(frame, frame.size))
        assertEquals(1, failures.get())
    }

    @Test
    fun successfulFrameClearsPreviousRecoveryAttempts() {
        val native = FakeNative(submitResult = -1)
        val session = PyrowaveDecoderSession(native)
        val frame = byteArrayOf(7)

        assertTrue(session.create(1280, 720))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))

        native.submitResult = 0
        assertEquals(PyrowaveDecoderSession.SubmitResult.SUCCESS, session.submit(frame, frame.size))

        native.submitResult = -1
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))
        assertEquals(PyrowaveDecoderSession.SubmitResult.FATAL, session.submit(frame, frame.size))
    }

    @Test
    fun incompleteFrameKeepsTheExistingDecoderAndPresentedSurface() {
        val native = FakeNative(
            submitResult = PyrowaveDecoderSession.NATIVE_SUBMIT_FRAME_DROPPED,
        )
        val session = PyrowaveDecoderSession(native)
        val frame = byteArrayOf(1, 2, 3)

        assertTrue(session.create(1920, 1080))
        assertEquals(
            PyrowaveDecoderSession.SubmitResult.FRAME_DROPPED,
            session.submit(frame, frame.size),
        )
        assertTrue(session.isActive)
        assertEquals(1, native.createProfiles.size)
        assertTrue(native.destroyed.isEmpty())
    }

    @Test
    fun failedResizeLeavesOldHandleUntilCallerEndsTheSession() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native)

        assertTrue(session.create(1280, 720))
        native.createResult = 0L
        assertFalse(session.create(1920, 1080))
        assertTrue(session.isActive)

        session.destroy()
        assertFalse(session.isActive)
        assertEquals(1, native.destroyed.size)
    }

    @Test
    fun hdrNeverFallsBackToTheCpuBridge() {
        val cpu = FakeNative()
        val gpu = FakeNative(createResult = 0L)
        val session = PyrowaveDecoderSession(native = cpu, gpuNative = gpu)

        assertFalse(session.create(1920, 1080, hdrMode = 2))
        assertTrue(cpu.createProfiles.isEmpty())
        assertEquals(listOf(2 to true), gpu.createProfiles)
    }

    @Test
    fun hdrLimitedRangeUsesTheGpuBridgeWithoutChangingThePreference() {
        val gpu = FakeNative()
        val session = PyrowaveDecoderSession(native = FakeNative(), gpuNative = gpu)

        assertTrue(session.create(1920, 1080, hdrMode = 1, fullRange = false))
        assertEquals(listOf(1 to false), gpu.createProfiles)
    }

    @Test
    fun canCreateProbesAndCleansTheCandidateHandle() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native)

        assertTrue(session.canCreate(1280, 720, hdrMode = 0, fullRange = true))
        assertFalse(session.isActive)
        assertEquals(listOf(1L), native.destroyed)
    }

    @Test
    fun hdrMetadataIsForwardedAndSurvivesSessionRecreation() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native)
        val metadata = byteArrayOf(1, 2, 3)

        assertTrue(session.create(1280, 720, hdrMode = 0))
        assertTrue(session.setHdrMetadata(enabled = true, metadata = metadata))
        assertEquals(metadata.toList(), native.hdrMetadata.last().second?.toList())

        assertTrue(session.create(1920, 1080, hdrMode = 0))
        assertEquals(metadata.toList(), native.hdrMetadata.last().second?.toList())
    }

    @Test
    fun hdrMetadataFailureDoesNotExposeAnIncompleteSession() {
        val native = FakeNative().also { it.acceptHdrMetadata = false }
        val session = PyrowaveDecoderSession(native)

        assertFalse(session.create(1920, 1080, hdrMode = 1, hdrMetadata = byteArrayOf(1)))
        assertFalse(session.isActive)
    }

    @Test
    fun hdr10WithoutStaticMetadataKeepsTheHdrSessionUsable() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native, gpuNative = native)

        assertTrue(session.create(1920, 1080, hdrMode = 1, hdrMetadata = null))
        assertTrue(session.isActive)
    }

    @Test
    fun recoveryUsesTheLastCreatedSignalProfile() {
        val gpu = FakeNative(submitResult = -1)
        val session = PyrowaveDecoderSession(gpuNative = gpu)
        val frame = byteArrayOf(1)

        assertTrue(session.create(1920, 1080, hdrMode = 2))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(frame, frame.size))
        assertEquals(listOf(2 to true, 2 to true), gpu.createProfiles)

        assertTrue(session.create(1280, 720, hdrMode = 0))
        assertEquals(0 to true, gpu.createProfiles.last())
    }

    @Test
    fun failedGpuSubmissionDestroysTheHandleBeforeTheNextFrame() {
        val gpu = FakeNative(submitResult = -1)
        val session = PyrowaveDecoderSession(gpuNative = gpu)
        val frame = byteArrayOf(1)
        val metadata = byteArrayOf(2)

        assertTrue(session.create(1280, 720, hdrMode = 1, dynamicHdrFormat = 1))
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED,
                     session.submit(frame, frame.size, metadata))
        assertEquals(listOf(1L), gpu.submittedHandles)
        assertEquals(listOf(1L), gpu.destroyed)
        assertEquals(0, session.appliedDynamicHdrFormat)

        gpu.submitResult = 0
        assertEquals(PyrowaveDecoderSession.SubmitResult.SUCCESS,
                     session.submit(frame, frame.size, metadata))
        assertEquals(listOf(1L, 2L), gpu.submittedHandles)
        assertEquals(1, session.appliedDynamicHdrFormat)
    }

    @Test
    fun surfaceDetachIsDeferredToTheDecoderWorker() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native)

        assertTrue(session.create(1280, 720))
        session.setSurface(null)
        // Surface callbacks only publish state; native detach is performed by
        // the worker that owns decoder operations.
        assertEquals(1, native.surfaces.size)
        assertEquals(
            PyrowaveDecoderSession.SubmitResult.SURFACE_UNAVAILABLE,
            session.submit(byteArrayOf(1), 1),
        )
        assertEquals(2, native.surfaces.size)
        assertEquals(null, native.surfaces.last())
    }

    @Test
    fun stopWaitsForAnInFlightSubmitAndDoesNotLeaveAReplacementHandle() {
        val submitStarted = CountDownLatch(1)
        val releaseSubmit = CountDownLatch(1)
        val native = FakeNative(submitResult = 0).also {
            it.onSubmit = {
                submitStarted.countDown()
                releaseSubmit.await(2, TimeUnit.SECONDS)
                0
            }
        }
        val session = PyrowaveDecoderSession(native)
        val frame = byteArrayOf(9)
        assertTrue(session.create(1280, 720))

        val submitThread = Thread { session.submit(frame, frame.size) }
        submitThread.start()
        assertTrue(submitStarted.await(2, TimeUnit.SECONDS))

        val destroyThread = Thread { session.destroy() }
        destroyThread.start()
        releaseSubmit.countDown()
        submitThread.join(2_000)
        destroyThread.join(2_000)

        assertFalse(submitThread.isAlive)
        assertFalse(destroyThread.isAlive)
        assertFalse(session.isActive)
    }

    @Test
    fun dynamicMetadataFollowsTheFrameAndConfigurationSurvivesRecoveryAndResize() {
        val gpu = FakeNative()
        val session = PyrowaveDecoderSession(FakeNative(), gpu)
        val metadata = byteArrayOf(4, 5, 6)
        assertTrue(session.create(1920, 1080, hdrMode = 1, dynamicHdrFormat = 4, targetPeakNits = 600f))
        assertEquals(4 to 600f, gpu.dynamicConfigurations.last())
        assertEquals(0, session.appliedDynamicHdrFormat)
        assertEquals(PyrowaveDecoderSession.SubmitResult.SUCCESS, session.submit(byteArrayOf(9), 1, metadata))
        assertTrue(gpu.frameMetadata.last() === metadata)
        assertEquals(4, session.appliedDynamicHdrFormat)

        gpu.submitResult = -1
        assertEquals(PyrowaveDecoderSession.SubmitResult.RECOVERED, session.submit(byteArrayOf(9), 1, metadata))
        assertEquals(4 to 600f, gpu.dynamicConfigurations.last())
        assertEquals(0, session.appliedDynamicHdrFormat)
        assertTrue(session.create(1280, 720, hdrMode = 1, dynamicHdrFormat = 4, targetPeakNits = 600f))
        assertEquals(4 to 600f, gpu.dynamicConfigurations.last())
        gpu.submitResult = 0
        assertEquals(PyrowaveDecoderSession.SubmitResult.SUCCESS, session.submit(byteArrayOf(9), 1, metadata))
        session.setSurface(null)
        assertEquals(PyrowaveDecoderSession.SubmitResult.SURFACE_UNAVAILABLE, session.submit(byteArrayOf(9), 1, metadata))
        assertEquals(0, session.appliedDynamicHdrFormat)
        session.setHdrMetadata(false, null)
        assertEquals(0, session.appliedDynamicHdrFormat)
    }

    @Test
    fun dynamicPreflightRefusesMissingConsumerAndDoesNotUseCpu() {
        val gpu = FakeNative().also { it.acceptDynamicHdr = false }
        val cpu = FakeNative()
        val session = PyrowaveDecoderSession(cpu, gpu)
        assertFalse(session.canCreate(1920, 1080, 1, false, dynamicHdrFormat = 1, targetPeakNits = 500f))
        assertTrue(cpu.createProfiles.isEmpty())
        assertFalse(session.isActive)
    }

    @Test
    fun staticAndSdrSubmissionKeepTheExistingNativeSignature() {
        val native = FakeNative()
        val session = PyrowaveDecoderSession(native)
        assertTrue(session.create(1280, 720))
        assertEquals(PyrowaveDecoderSession.SubmitResult.SUCCESS,
                     session.submit(byteArrayOf(1), 1, byteArrayOf(0, 1)))
        assertEquals(null, native.frameMetadata.last())
        assertEquals(0, session.appliedDynamicHdrFormat)
    }

    private class FakeNative(
        var createResult: Long = 1L,
        var submitResult: Int = 0,
    ) : PyrowaveNativeApi {
        private var nextHandle = 1L
        val destroyed = mutableListOf<Long>()
        val surfaces = mutableListOf<android.view.Surface?>()
        val createProfiles = mutableListOf<Pair<Int, Boolean>>()
        val hdrMetadata = mutableListOf<Pair<Boolean, ByteArray?>>()
        var acceptHdrMetadata = true
        var acceptDynamicHdr = true
        val dynamicConfigurations = mutableListOf<Pair<Int, Float>>()
        val frameMetadata = mutableListOf<ByteArray?>()
        val submittedHandles = mutableListOf<Long>()
        var onSubmit: (() -> Int)? = null

        override fun create(width: Int, height: Int, hdrMode: Int, fullRange: Boolean): Long {
            createProfiles += hdrMode to fullRange
            if (createResult == 0L) return 0L
            return nextHandle++
        }

        override fun setSurface(handle: Long, surface: android.view.Surface?): Boolean {
            surfaces += surface
            return true
        }

        override fun submit(handle: Long, data: ByteArray, length: Int): Int {
            submittedHandles += handle
            return onSubmit?.invoke() ?: submitResult
        }

        override fun getTimings(handle: Long): Long = 0L

        override fun setDynamicHdr(handle: Long, format: Int, targetPeakNits: Float): Boolean {
            dynamicConfigurations += format to targetPeakNits
            return acceptDynamicHdr
        }

        override fun submitFrame(handle: Long, data: ByteArray, length: Int, metadata: ByteArray?): Int {
            frameMetadata += metadata
            return submit(handle, data, length)
        }

        override fun setHdrMetadata(handle: Long, enabled: Boolean, metadata: ByteArray?): Boolean {
            hdrMetadata += enabled to metadata?.copyOf()
            return acceptHdrMetadata
        }

        override fun destroy(handle: Long) {
            destroyed += handle
        }
    }
}
