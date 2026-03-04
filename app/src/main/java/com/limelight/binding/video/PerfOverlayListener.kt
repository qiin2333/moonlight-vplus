package com.limelight.binding.video

interface PerfOverlayListener {
//    fun onPerfUpdate(text: String)
    fun onPerfUpdateV(performanceInfo: PerformanceInfo)
    fun onPerfUpdateWG(performanceInfo: PerformanceInfo)
    fun isPerfOverlayVisible(): Boolean
}
