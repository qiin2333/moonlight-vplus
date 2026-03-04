package com.limelight.binding

import android.content.Context

import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.LimelightCryptoProvider

object PlatformBinding {
    @JvmStatic
    fun getCryptoProvider(c: Context): LimelightCryptoProvider {
        return AndroidCryptoProvider(c)
    }
}
