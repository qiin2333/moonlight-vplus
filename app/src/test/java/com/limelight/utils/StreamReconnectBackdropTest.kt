package com.limelight.utils

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamReconnectBackdropTest {
    @Test
    fun missingBackdropLeavesTheAppPosterUntouched() {
        assertNull(StreamReconnectBackdrop.consume())
    }

    @Test
    fun stagedBackdropIsConsumedOnce() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        StreamReconnectBackdrop.stage(bitmap)

        assertSame(bitmap, StreamReconnectBackdrop.consume())
        assertNull(StreamReconnectBackdrop.consume())
    }
}
