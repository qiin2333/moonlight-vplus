package com.limelight.binding.video

import com.google.firebase.crashlytics.internal.stacktrace.TrimmedThrowableData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
