package com.limelight.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);
    void onPerfUpdateWG(final PerformanceInfo performanceInfo);
    boolean isPerfOverlayVisible();
}
