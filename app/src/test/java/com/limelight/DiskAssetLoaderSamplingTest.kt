package com.limelight

import com.limelight.grid.assets.DiskAssetLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class DiskAssetLoaderSamplingTest {
    @Test
    fun capsHugeCustomBoxArt() {
        // 崩溃样本：约 4900×4900 的自定义封面，divider 为 1 时按比例采样不生效，
        // 原尺寸解码需要 96MB（Crashlytics 致命 OOM）
        assertEquals(8, DiskAssetLoader.capInSampleSize(1, 4900, 4900, 1024))
        assertEquals(8, DiskAssetLoader.capInSampleSize(1, 8000, 3000, 1024))
    }

    @Test
    fun leavesNormalArtUntouched() {
        // GFE 3.0 标准封面 628×888，无需下采样
        assertEquals(1, DiskAssetLoader.capInSampleSize(1, 628, 888, 1024))
        assertEquals(2, DiskAssetLoader.capInSampleSize(2, 628, 888, 1024))
    }

    @Test
    fun onlyEverIncreasesSampleSize() {
        assertEquals(16, DiskAssetLoader.capInSampleSize(16, 4900, 4900, 1024))
        assertEquals(4, DiskAssetLoader.capInSampleSize(4, 1200, 1200, 1024))
    }

    @Test
    fun usesCeilingDivisionAtTheLimitBoundary() {
        // 解码器按向上取整采样：4097/4 实际解出 1025，须再放大一档；4096/4 = 1024 恰好达标
        assertEquals(8, DiskAssetLoader.capInSampleSize(1, 4097, 4097, 1024))
        assertEquals(4, DiskAssetLoader.capInSampleSize(1, 4096, 4096, 1024))
    }

    @Test
    fun ignoresInvalidInputs() {
        assertEquals(1, DiskAssetLoader.capInSampleSize(1, 4900, 4900, 0))
        assertEquals(3, DiskAssetLoader.capInSampleSize(3, 4900, 4900, -1))
        // 尺寸未知（读头失败）时保持原样，避免死循环；base 非法时收敛为 1 再正常封顶
        assertEquals(1, DiskAssetLoader.capInSampleSize(1, 0, 0, 1024))
        assertEquals(8, DiskAssetLoader.capInSampleSize(0, 4900, 4900, 1024))
    }
}
