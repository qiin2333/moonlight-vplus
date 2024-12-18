package com.limelight.binding.video;

public interface PerfOverlayListener {
//    void onPerfUpdate(final String text);
    void onPerfUpdateV(final PerformanceInfo performanceInfo);
    void onPerfUpdateWG(final PerformanceInfo performanceInfo);
    boolean isPerfOverlayVisible();
}
