package com.limelight.grid.assets

import android.graphics.Bitmap

class ScaledBitmap @JvmOverloads constructor(
    @JvmField var originalWidth: Int = 0,
    @JvmField var originalHeight: Int = 0,
    @JvmField var bitmap: Bitmap? = null
)
