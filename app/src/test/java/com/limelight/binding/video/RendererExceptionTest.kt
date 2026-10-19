package com.limelight.binding.video

import com.google.firebase.crashlytics.internal.stacktrace.TrimmedThrowableData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter

class RendererExceptionTest {
    @Test
    fun crashlyticsCaptureRetainsDiagnosticMessageAndCauseStack() {
        val original = IllegalStateException("codec reset failed")
        original.stackTrace = arrayOf(
            StackTraceElement("android.media.MediaCodec", "reset", "MediaCodec.java", 123),
        )
        val error = RendererException(diagnostics(), original)
        val captured = TrimmedThrowableData.makeTrimmedThrowableData(error) { it }

        assertEquals(error.message, captured.localizedMessage)
        assertTrue(captured.localizedMessage.contains("Video dimensions: 1920x1080"))
        assertEquals(original.javaClass.name, captured.cause!!.className)
        assertEquals("codec reset failed", captured.cause!!.localizedMessage)
        assertArrayEquals(original.stackTrace, captured.cause!!.stacktrace)
    }

    @Test
    fun crashReporterMessageIncludesRendererContextAndOriginalFailure() {
        val original = IllegalStateException("Decoder reset failed: -5")
        val error = RendererException(diagnostics(), original)

        val message = error.localizedMessage!!
        assertEquals(error.message, message)
        assertEquals(error.toString(), message)
        assertTrue(message.contains("Video dimensions: 1920x1080"))
        assertTrue(message.contains("FPS target: 60"))
        assertTrue(message.contains("Bitrate: 20000 Kbps"))
        assertTrue(message.contains("Configured format:"))
        assertTrue(message.contains("Frames in-out: 120, 100"))
        assertTrue(message.contains(original.toString()))
    }

    @Test
    fun originalExceptionAndNestedCauseRetainTheirTypesAndStacks() {
        val codecFailure = IllegalArgumentException("unsupported codec configuration")
        val original = IllegalStateException("Decoder configuration failed", codecFailure)
        val error = RendererException(diagnostics(), original)

        assertSame(original, error.cause)
        assertSame(codecFailure, error.cause!!.cause)
        assertSame(original.stackTrace[0], error.cause!!.stackTrace[0])
    }

    @Test
    fun printedStackTraceIncludesTheOriginalFailureLocation() {
        val original = IllegalStateException("codec reset failed")
        original.stackTrace = arrayOf(
            StackTraceElement("android.media.MediaCodec", "reset", "MediaCodec.java", 123),
        )
        val error = RendererException(diagnostics(), original)
        val output = StringWriter()
        error.printStackTrace(PrintWriter(output))

        assertTrue(output.toString().contains("Caused by: java.lang.IllegalStateException: codec reset failed"))
        assertTrue(output.toString().contains("android.media.MediaCodec.reset(MediaCodec.java:123)"))
    }

    @Test
    fun failuresWithoutTheirOwnMessageStillExposeDiagnosticsAndCause() {
        val original = IllegalStateException()
        val error = RendererException(diagnostics(), original)

        assertSame(original, error.cause)
        assertTrue(error.localizedMessage!!.contains("Video dimensions: 1920x1080"))
        assertTrue(error.localizedMessage!!.endsWith(original.toString()))
    }

    private fun diagnostics() = RendererDiagnostics(
        numVpsIn = 0,
        numSpsIn = 1,
        numPpsIn = 1,
        numFramesIn = 120,
        numFramesOut = 100,
        videoFormat = 1,
        initialWidth = 1920,
        initialHeight = 1080,
        refreshRate = 60,
        bitrate = 20000,
        framePacing = 1,
        consecutiveCrashCount = 0,
        adaptivePlayback = false,
        refFrameInvalidationActive = false,
        fusedIdrFrame = false,
        hdr10PlusEligible = false,
        hdr10PlusConfigured = false,
        hdr10PlusOutputFramesQueried = 0,
        hdr10PlusMetadataFrames = 0,
        hdr10PlusMetadataChanges = 0,
        hdr10PlusLastMetadataSize = 0,
        hdr10PlusLastPresentationTimeUs = 0,
        hdr10PlusMetadataQueryFailures = 0,
        glRenderer = null,
        avcDecoder = null,
        hevcDecoder = null,
        av1Decoder = null,
        configuredFormat = null,
        inputFormat = null,
        outputFormat = null,
        totalFramesReceived = 125,
        totalFramesRendered = 100,
        framesLost = 5,
        frameLossEvents = 1,
        averageEndToEndLatency = 10,
        averageDecoderLatency = 5,
    )
}
