package com.limelight.binding.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Exercises the actual format writer; this does not emulate vendor decoder output. */
@RunWith(AndroidJUnit4::class)
class HevcCompatibilityOptionsTest {
    @Before
    fun initializeCodecHelper() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MediaCodecHelper.initialize(context, GlPreferences.readPreferences(context).glRenderer)
    }

    @Test
    fun firstCompatibilityAttemptOnlyAddsRealtimePriority() {
        val format = format("video/hevc")
        val hasAnotherAttempt = configureOptions(format, "video/hevc", 0)
        assertNoLowLatencyOptions(format)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            assertTrue(hasAnotherAttempt)
            assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        } else {
            assertFalse(hasAnotherAttempt)
            assertFalse(format.containsKey("priority"))
        }
    }

    @Test
    fun freshFallbackFormatHasNoPriorityAndEndsOptionSweep() {
        val first = format("video/hevc")
        configureOptions(first, "video/hevc", 0)
        val fallback = format("video/hevc")
        assertFalse(configureOptions(fallback, "video/hevc", 1))
        assertNoLowLatencyOptions(fallback)
        assertFalse(fallback.containsKey("priority"))
    }

    @Test
    fun compatibilityOptionsPreserveHdrProfileAndMetadata() {
        for (attempt in 0..1) {
            val format = format("video/hevc")
            val metadata = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3))
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            format.setInteger("color-transfer", 6)
            format.setByteBuffer("hdr-static-info", metadata)
            configureOptions(format, "video/hevc", attempt)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, format.getInteger(MediaFormat.KEY_PROFILE))
            assertEquals(6, format.getInteger("color-transfer"))
            assertSame(metadata, format.getByteBuffer("hdr-static-info"))
            assertNoLowLatencyOptions(format)
        }
    }

    @Test
    fun forcedOffAlsoKeepsDolbyVisionSignalingAndBoundedFallback() {
        // OFF reaches the format writer before any codec capability query; the existing
        // HEVC descriptor is sufficient to verify this MIME-specific option path.
        val decoder = decoderFor("video/hevc")
        for (attempt in 0..1) {
            val format = format("video/dolby-vision")
            val signaling = ByteBuffer.wrap(byteArrayOf(1, 0, 16, 0, 16))
            format.setByteBuffer("csd-0", signaling)
            val more = MediaCodecHelper.setDecoderLowLatencyOptions(
                format, decoder, attempt, false, "video/dolby-vision",
                PreferenceConfiguration.HEVC_LOW_LATENCY_OFF, false
            )
            assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && attempt == 0, more)
            assertSame(signaling, format.getByteBuffer("csd-0"))
            assertNoLowLatencyOptions(format)
        }
    }

    @Test
    fun forcedOnPreservesHevcLowLatencyOption() {
        val format = format("video/hevc")
        assertTrue(configureOptions(format, "video/hevc", 0, PreferenceConfiguration.HEVC_LOW_LATENCY_ON))
        assertEquals(1, format.getInteger("low-latency"))
    }

    @Test
    fun hevcPreferenceDoesNotDisableAvcOrAv1Tuning() {
        for (mime in listOf("video/avc", "video/av01")) {
            val format = format(mime)
            assertTrue(configureOptions(format, mime, 0))
            assertEquals(1, format.getInteger("low-latency"))
        }
    }

    private fun configureOptions(
        format: MediaFormat,
        mime: String,
        attempt: Int,
        mode: Int = PreferenceConfiguration.HEVC_LOW_LATENCY_OFF
    ) = MediaCodecHelper.setDecoderLowLatencyOptions(format, decoderFor(mime), attempt, false, mime, mode, false)

    private fun decoderFor(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
        !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
    }

    private fun format(mime: String) = MediaFormat.createVideoFormat(mime, 1920, 1080)

    private fun assertNoLowLatencyOptions(format: MediaFormat) {
        for (key in listOf(
            "low-latency", "vdec-lowlatency", "vendor.low-latency.enable",
            "vendor.amlogic.lowlatency.mode", "vendor.amlogic.tunnel.mode",
            "vendor.amlogic.frame-skip.enable", "operating-rate"
        )) {
            assertFalse("Unexpected decoder option: $key", format.containsKey(key))
        }
    }
}
