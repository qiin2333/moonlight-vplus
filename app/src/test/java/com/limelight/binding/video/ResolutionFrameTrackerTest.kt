package com.limelight.binding.video

import org.junit.Assert.*
import org.junit.Test

class ResolutionFrameTrackerTest {
    @Test fun notificationOrDecodedFrameAloneDoesNotMeanNewPictureWasPresented() {
        val tracker = ResolutionFrameTracker(2560, 1440)
        val codec = Any()
        assertFalse(tracker.presented(codec, 100))
        tracker.decoded(codec, 100, 1920, 1080)
        assertFalse(tracker.presented(codec, 100))
        tracker.decoded(codec, 200, 2560, 1440)
        assertFalse(tracker.presented(codec, 100))
        assertTrue(tracker.presented(codec, 200))
        assertFalse(tracker.presented(codec, 200))
    }

    @Test fun aReplacedDecoderCannotConfirmAFrameWithTheSameTimestamp() {
        val tracker = ResolutionFrameTracker(1280, 720)
        val oldCodec = Any()
        val newCodec = Any()
        tracker.decoded(oldCodec, 42, 1280, 720)
        assertFalse(tracker.presented(newCodec, 42))
        tracker.decoded(newCodec, 42, 1280, 720)
        assertTrue(tracker.presented(newCodec, 42))
    }

    @Test fun droppedFramesCannotGrowTrackingWithoutBound() {
        val tracker = ResolutionFrameTracker(1920, 1080)
        val codec = Any()
        repeat(100) { tracker.decoded(codec, it.toLong(), 1920, 1080) }
        assertFalse(tracker.presented(codec, 0))
        assertTrue(tracker.presented(codec, 99))
    }
}
