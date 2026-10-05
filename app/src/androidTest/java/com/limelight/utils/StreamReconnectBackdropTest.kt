package com.limelight.utils

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
