package com.limelight.binding.video;

import android.content.Context;

public class PerformanceInfo {

    public Context context;
    public String decoder;
    public int initialWidth;
    public int initialHeight;
    public float totalFps;
    public float receivedFps;
    public float renderedFps;
    public float lostFrameRate;
    public long rttInfo;
    public int framesWithHostProcessingLatency;
    public float minHostProcessingLatency;
    public float maxHostProcessingLatency;
    public float aveHostProcessingLatency;
    public float decodeTimeMs;
    public float totalTimeMs;
    public String bandWidth;
    public boolean isHdrActive; // 实际HDR激活状态
    public float renderingLatencyMs; // 渲染时间
}
