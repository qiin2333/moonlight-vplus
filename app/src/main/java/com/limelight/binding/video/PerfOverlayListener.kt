package com.limelight.binding.video

interface PerfOverlayListener {
//    fun onPerfUpdate(text: String)
    fun onPerfUpdateV(performanceInfo: PerformanceInfo)
    fun onPerfUpdateWG(performanceInfo: PerformanceInfo)
    fun onVideoFrameLoss(framesLost: Int, frameNumber: Int)
    fun isPerfOverlayVisible(): Boolean
}
