package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import org.jcodec.codecs.h264.io.model.SeqParameterSet;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

public class MediaCodecDecoderRenderer extends VideoDecoderRenderer {

    private static final boolean USE_FRAME_RENDER_TIME = true;
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false;

    // Used on versions < 5.0
    private ByteBuffer[] legacyInputBuffers;

    private final MediaCodecInfo avcDecoder;
    private final MediaCodecInfo hevcDecoder;
    private final MediaCodecInfo av1Decoder;

    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();
    private boolean submittedCsd;
    private byte[] currentHdrMetadata;
    private int hdrDataSpace; // Configured DataSpace for HDR content, re-applied after format changes

    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;


    private final Context context;
    private final Activity activity;
    private MediaCodec videoDecoder;
    private Thread rendererThread;
    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, directSubmit, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private final byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;
    private int initialWidth, initialHeight;
    private int videoFormat;
    private SurfaceHolder renderTarget;
    private volatile boolean stopping;
    private final CrashListener crashListener;
    private boolean reportedCrash;
    private final int consecutiveCrashCount;
    private final String glRenderer;
    private boolean foreground = true;
    private volatile boolean isProcessingPaused = false;
    private boolean needsIdrOnResume = false;
    private final PerfOverlayListener perfListener;

    private static final int CR_MAX_TRIES = 10;
    private static final int CR_RECOVERY_TYPE_NONE = 0;
    private static final int CR_RECOVERY_TYPE_FLUSH = 1;
    private static final int CR_RECOVERY_TYPE_RESTART = 2;
    private static final int CR_RECOVERY_TYPE_RESET = 3;
    private final AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    private final Object codecRecoveryMonitor = new Object();

    // Each thread that touches the MediaCodec object or any associated buffers must have a flag
    // here and must call doCodecRecoveryIfRequired() on a regular basis.
    private static final int CR_FLAG_INPUT_THREAD = 0x1;
    private static final int CR_FLAG_RENDER_THREAD = 0x2;
    static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    private static final int CR_FLAG_ALL = CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;
    private int codecRecoveryThreadQuiescedFlags = 0;
    private int codecRecoveryAttempts = 0;

    private MediaFormat inputFormat;
    private MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private final VideoStats activeWindowVideoStats;
    private final VideoStats lastWindowVideoStats;
    private final VideoStats globalVideoStats;
    private final FrameIntervalTracker frameIntervalTracker = new FrameIntervalTracker(600);

    private long lastTimestampUs;
    private int lastFrameNumber;
    private int refreshRate;
    private final PreferenceConfiguration prefs;

    // Map to track enqueue time for each timestamp
    // Key: timestamp in microseconds (from enqueueTimeUs)
    // Value: enqueue time in milliseconds (from SystemClock.uptimeMillis())
    private final Map<Long, Long> timestampToEnqueueTime = new ConcurrentHashMap<>();

    // Frame pacing and performance management (extracted)
    private final FramePacingController framePacingController;
    private final PerformanceBoostManager perfBoostManager;

    // H.264 SPS patching (extracted)
    private SpsPatcher spsPatcher;

    // Async codec mode (API 30+): eliminates polling overhead for input/output buffers
    private boolean asyncModeEnabled;
    private LinkedBlockingQueue<Integer> availableInputBuffers;
    private HandlerThread codecCallbackThread;
    private Handler codecCallbackHandler;

    // LinearBlock zero-copy input (API 30+)
    private boolean linearBlockEnabled;
    private String[] codecNameArray;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    private boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(prefs.width, prefs.height, prefs.fps);
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
            if (perfPoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                    // If we find a performance point that covers our target, we're good to go
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true;
                    }
                }

                // We had performance point data but none met the specified streaming settings
                return false;
            }

            // Fall-through to try the Android M API if there's no performance point data
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // We'll ask the decoder what it can do for us at this resolution and see if our
                // requested frame rate falls below or inside the range of achievable frame rates.
                Range<Double> fpsRange = caps.getAchievableFrameRatesFor(prefs.width, prefs.height);
                if (fpsRange != null) {
                    return prefs.fps <= fpsRange.getUpper();
                }

                // Fall-through to try the Android L API if there's no performance point data
            } catch (IllegalArgumentException e) {
                // Video size not supported at any frame rate
                return false;
            }
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(prefs.width, prefs.height, prefs.fps);
    }

    private boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs);
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotHevc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo hevcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotAvc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    private MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs, boolean meteredNetwork, boolean requestedHdr) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // In auto mode, we should still prepare HEVC as a fallback even if AV1 is available
        // The server will negotiate the final codec choice based on what it supports
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.AUTO) {
            MediaCodecInfo av1DecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
            if (av1DecoderInfo != null && MediaCodecHelper.isDecoderWhitelistedForAv1(av1DecoderInfo)) {
                LimeLog.info("AV1 decoder available in auto mode, but still preparing HEVC as fallback");
                // Continue to prepare HEVC decoder instead of returning null
            }
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        MediaCodecInfo hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - " + hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (prefs.width > 4096 || prefs.height > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                } else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    private MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // Use AV1 if explicitly requested or in auto mode
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1 &&
                prefs.videoFormat != PreferenceConfiguration.FormatOption.AUTO) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - " + decoderInfo.getName());

                // Force AV1 enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
                }
                // Use AV1 if the HEVC decoder is unable to meet the performance point
                else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                } else {
                    return null;
                }
            }
        }

        return decoderInfo;
    }

    public void setRenderTarget(SurfaceHolder renderTarget) {
        this.renderTarget = renderTarget;
    }

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();

        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        this.perfBoostManager = new PerformanceBoostManager(activity);
        this.framePacingController = new FramePacingController(new FramePacingController.Callbacks() {
            @Override
            public void onFrameRendered() {
                activeWindowVideoStats.totalFramesRendered++;
                frameIntervalTracker.recordFrame();
            }

            @Override
            public boolean onDecoderException(IllegalStateException e) {
                return handleDecoderException(e);
            }

            @Override
            public boolean onCodecRecoveryCheck(int flag) {
                return doCodecRecoveryIfRequired(flag);
            }
        }, prefs, activity);

        avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: " + avcDecoder.getName());
        } else {
            LimeLog.warning("No AVC decoder found");
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: " + hevcDecoder.getName());
        } else {
            LimeLog.info("No HEVC decoder found");
        }

        av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: " + av1Decoder.getName());
        } else {
            LimeLog.info("No AV1 decoder found");
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.getName());
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), prefs.height);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (directSubmit) {
                LimeLog.info("Decoder " + avcDecoder.getName() + " will use direct submit");
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder " + avcDecoder.getName() + " will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder " + avcDecoder.getName() + " wants " + avcOptimalSlicesPerFrame + " slices per frame");
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder " + hevcDecoder.getName() + " will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder " + hevcDecoder.getName() + " wants " + hevcOptimalSlicesPerFrame + " slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder " + av1Decoder.getName() + " will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame = (byte) Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting " + optimalSlicesPerFrame + " slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    public boolean isHevcSupported() {
        return hevcDecoder != null;
    }

    public boolean isAvcSupported() {
        return avcDecoder != null;
    }

    public boolean isHevcMain10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                LimeLog.info("HEVC decoder " + hevcDecoder.getName() + " supports HEVC Main10");
                return true;
            }
        }
        return false;
    }

    public boolean isHevcMain10Hdr10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) {
                LimeLog.info("HEVC 解码器 " + hevcDecoder.getName() + " 支持 HEVC Main10 HDR10/HDR10+");
                return true;
            }
        }

        return false;
    }

    public boolean isAv1Supported() {
        return av1Decoder != null;
    }

    public boolean isAv1Main10Supported() {
        if (av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder " + av1Decoder.getName() + " supports AV1 Main 10 HDR10");
                return true;
            }
        }

        return false;
    }

    public int getPreferredColorSpace() {
        // Default to Rec 709 which is probably better supported on modern devices.
        //
        // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
        // to avoid possible regressions (and they are < 5% of installed devices). If we have
        // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
        // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
        // plenty modern enough to handle H.264 VUI colorspace info).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
            return MoonBridge.COLORSPACE_REC_709;
        } else {
            return MoonBridge.COLORSPACE_REC_601;
        }
    }

    public int getPreferredColorRange() {
        if (prefs.fullRange) {
            return MoonBridge.COLOR_RANGE_FULL;
        } else {
            return MoonBridge.COLOR_RANGE_LIMITED;
        }
    }

    public void notifyVideoForeground() {
        foreground = true;
    }

    public void notifyVideoBackground() {
        foreground = false;
    }

    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    public void pauseProcessing() {
        if (isProcessingPaused) {
            return;
        }

        LimeLog.info("Pausing video processing and releasing decoder");
        isProcessingPaused = true;

        // 停止渲染线程和相关的 handle
        prepareForStop();

        // 释放 MediaCodec 资源
        cleanup();

        // 标记下次恢复时需要 IDR 帧
        needsIdrOnResume = true;
    }

    public void resumeProcessing() {
        if (!isProcessingPaused) {
            return;
        }

        LimeLog.info("Resuming video processing");

        // 重置停止标志，允许渲染线程运行
        stopping = false;

        // 清理输出缓冲区队列，移除 prepareForStop 放入的 -1 信号
        framePacingController.clearBuffers();

        // 重置输入缓冲区索引，避免使用已释放解码器的旧索引
        nextInputBufferIndex = -1;
        nextInputBuffer = null;

        // 重新创建异步回调线程（如果需要）
        if (asyncModeEnabled && codecCallbackThread == null) {
            availableInputBuffers = new LinkedBlockingQueue<>();
            codecCallbackThread = new HandlerThread("Video - Codec", Process.THREAD_PRIORITY_DISPLAY);
            codecCallbackThread.start();
            codecCallbackHandler = new Handler(codecCallbackThread.getLooper());
            LimeLog.info("MediaCodec async mode re-enabled after resume");
        }

        // 重新初始化解码器
        // 注意：initialWidth, initialHeight 等变量依然保留着
        initializeDecoder(false);

        // 重新启动渲染线程等
        start();

        isProcessingPaused = false;
    }

    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
        }

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight);
        }

        // Android 7.0 adds color options to the MediaFormat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // HLG content from Sunshine uses FULL range. Using LIMITED causes dark images
            // because the HLG OETF/EOTF is applied to the wrong value range on most decoders.
            boolean useFullRange;
            if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0 &&
                    prefs.hdrMode == MoonBridge.HDR_MODE_HLG) {
                useFullRange = true;
            } else {
                useFullRange = (getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL);
            }
            videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                    useFullRange ? MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);

            if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                // HDR 10-bit: set BT.2020 color standard and transfer function.
                // Many decoders fail to auto-detect from VUI/SEI, causing dark/crushed colors.
                videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                if (prefs.hdrMode == MoonBridge.HDR_MODE_HLG) {
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
                    // Request pass-through to prevent internal tone-mapping on some decoders
                    videoFormat.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_HLG);
                } else {
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
                    videoFormat.setInteger("color-transfer-request", MediaFormat.COLOR_TRANSFER_ST2084);
                }
            } else {
                // SDR mode: set color format keys since they won't change
                videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                switch (getPreferredColorSpace()) {
                    case MoonBridge.COLORSPACE_REC_601:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                        break;
                    case MoonBridge.COLORSPACE_REC_709:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                        break;
                    case MoonBridge.COLORSPACE_REC_2020:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                        break;
                }
            }
        }

        return videoFormat;
    }

    private void configureAndStartDecoder(MediaFormat format) {
        // Set HDR metadata if present
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null) {
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

                // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
                hdrStaticInfo.put((byte) 0); // Metadata type
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White X
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White Y
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Min mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max content luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max frame average luminance

                hdrStaticInfo.rewind();
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
            } else if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                // HLG streams from Sunshine typically have no SMPTE 2086 static metadata.
                // Without metadata, the display pipeline doesn't know the content's target
                // luminance range, causing conservative tone mapping that makes HDR look dark.
                // Provide default BT.2020 metadata with typical HDR display parameters
                // (matching HarmonyOS behavior for consistent brightness across platforms).
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                hdrStaticInfo.put((byte) 0);       // Metadata type (HDMI Static Metadata Type 1)
                // BT.2020 color primaries (in 0.00002 units per CTA-861.3)
                hdrStaticInfo.putShort((short) 35400);  // RX: 0.708
                hdrStaticInfo.putShort((short) 14600);  // RY: 0.292
                hdrStaticInfo.putShort((short) 8500);   // GX: 0.170
                hdrStaticInfo.putShort((short) 39850);  // GY: 0.797
                hdrStaticInfo.putShort((short) 6550);   // BX: 0.131
                hdrStaticInfo.putShort((short) 2300);   // BY: 0.046
                hdrStaticInfo.putShort((short) 15635);  // White X: 0.3127 (D65)
                hdrStaticInfo.putShort((short) 16450);  // White Y: 0.3290 (D65)
                hdrStaticInfo.putShort((short) 1000);   // Max mastering luminance (cd/m²)
                hdrStaticInfo.putShort((short) 10);     // Min mastering luminance (0.0001 cd/m² units → 0.001 nits)
                hdrStaticInfo.putShort((short) 1000);   // Max content light level (cd/m²)
                hdrStaticInfo.putShort((short) 400);    // Max frame average light level (cd/m²)

                hdrStaticInfo.rewind();
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
                LimeLog.info("Using default BT.2020 HDR static metadata (no server metadata available)");
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
            }
        }

        LimeLog.info("Configuring with format: " + format);

        videoDecoder.configure(format, renderTarget.getSurface(), null, 0);

        // Set DataSpace on the output Surface for HDR content.
        // Equivalent to HarmonyOS OH_NativeWindow_SetColorSpace().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
            // HLG always uses FULL range (its OETF/EOTF requires it)
            boolean isFullRange = (prefs.hdrMode == MoonBridge.HDR_MODE_HLG) ||
                    (getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL);
            if (prefs.hdrMode == MoonBridge.HDR_MODE_HLG) {
                hdrDataSpace = isFullRange ?
                        MoonBridge.DATASPACE_BT2020_HLG_FULL :
                        MoonBridge.DATASPACE_BT2020_HLG_LIMITED;
            } else {
                hdrDataSpace = isFullRange ?
                        MoonBridge.DATASPACE_BT2020_PQ_FULL :
                        MoonBridge.DATASPACE_BT2020_PQ_LIMITED;
            }
            int result = MoonBridge.nativeSetSurfaceDataSpace(renderTarget.getSurface(), hdrDataSpace);
            LimeLog.info("Surface DataSpace: 0x" + Integer.toHexString(hdrDataSpace) +
                    " result=" + result);
        }

        configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false;
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();
        timestampToEnqueueTime.clear();

        // This will contain the actual accepted input format attributes
        inputFormat = videoDecoder.getInputFormat();
        LimeLog.info("Input format: " + inputFormat);

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();

    }

    private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
        boolean configured = false;
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName());

            // Async callback must be set before configure()
            setupAsyncCallback();

            configureAndStartDecoder(format);
            LimeLog.info("Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
            configured = true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw new RuntimeException(e);
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder.release();
                videoDecoder = null;
            }
        }
        return configured;
    }

    public int initializeDecoder(boolean throwOnCodecError) {
        String mimeType;
        MediaCodecInfo selectedDecoderInfo;

        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc";
            selectedDecoderInfo = avcDecoder;

            if (avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            isExynos4 = MediaCodecHelper.isExynos4Device();
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder " + selectedDecoderInfo.getName() + " needs SPS bitstream restrictions fixup");
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder " + selectedDecoderInfo.getName() + " needs baseline SPS hack");
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder " + selectedDecoderInfo.getName() + " needs constrained high profile");
            }
            if (isExynos4) {
                LimeLog.info("Decoder " + selectedDecoderInfo.getName() + " is on Exynos 4");
            }

            refFrameInvalidationActive = refFrameInvalidationAvc;

            spsPatcher = new SpsPatcher(constrainedHighProfile, needsSpsBitstreamFixup,
                    isExynos4, hevcDecoder != null, av1Decoder != null);
        } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = hevcDecoder;

            if (hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationHevc;
        } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = av1Decoder;

            if (av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationAv1;
        } else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }

        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

        for (int tryNumber = 0; ; tryNumber++) {
            LimeLog.info("Decoder configuration try: " + tryNumber);

            MediaFormat mediaFormat = createBaseMediaFormat(mimeType);

            // This will try low latency options until we find one that works (or we give up).
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo, tryNumber);

            // Throw the underlying codec exception on the last attempt if the caller requested it
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                // Success!

                // Check LinearBlock copy-free compatibility (API 30+)
                // Disabled: Many vendor HEVC/HDR decoders have LinearBlock bugs causing crashes
                linearBlockEnabled = false;

                break;
            }

            if (!newFormat) {
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }

        if (USE_FRAME_RENDER_TIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setOnFrameRenderedListener((mediaCodec, presentationTimeUs, renderTimeNanos) -> {
                // presentationTimeUs: 我们告诉系统这一帧应该在什么时间点显示
                // renderTimeNanos: 系统报告的这一帧实际显示在屏幕上的时间点
                long presentationTimeMs = presentationTimeUs / 1000;
                long renderTimeMs = renderTimeNanos / 1000000L;

                // 计算从“应该显示”到“实际显示”的延迟
                long delta = renderTimeMs - presentationTimeMs;

                // 过滤掉异常值
                if (delta >= 0 && delta < 1000) {
                    activeWindowVideoStats.renderingTimeMs += delta;
                    activeWindowVideoStats.totalTimeMs += delta;
                }
            }, null);
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.initialWidth = width;
        this.initialHeight = height;
        this.videoFormat = format;
        this.refreshRate = redrawRate;

        // Async codec mode (API 30+)
        this.asyncModeEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        if (asyncModeEnabled) {
            availableInputBuffers = new LinkedBlockingQueue<>();
            codecCallbackThread = new HandlerThread("Video - Codec", Process.THREAD_PRIORITY_DISPLAY);
            codecCallbackThread.start();
            codecCallbackHandler = new Handler(codecCallbackThread.getLooper());
            LimeLog.info("MediaCodec async mode enabled");
        }

        return initializeDecoder(false);
    }

    // All threads that interact with the MediaCodec instance must call this function regularly!
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        // NB: We cannot check 'stopping' here because we could end up bailing in a partially
        // quiesced state that will cause the quiesced threads to never wake up.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (codecRecoveryMonitor) {
            if (!framePacingController.hasActiveTimingThread()) {
                // If we have no frame pacing thread, mark it as quiesced right now.
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
            }

            if (asyncModeEnabled) {
                // In async mode there is no renderer thread; auto-quiesce its flag
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_RENDER_THREAD;
            }

            codecRecoveryThreadQuiescedFlags |= quiescenceFlag;

            // This is the final thread to quiesce, so let's perform the codec recovery now.
            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                // Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                framePacingController.clearBuffers();
                if (asyncModeEnabled && availableInputBuffers != null) {
                    availableInputBuffers.clear();
                }

                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();
                        if (asyncModeEnabled) {
                            videoDecoder.start(); // Resume async callbacks after flush
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART);
                    }
                }

                // We don't count flushes as codec recovery attempts
                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++;
                    LimeLog.info("Codec recovery attempt: " + codecRecoveryAttempts);
                }

                // For "recoverable" exceptions, we can just stop, reconfigure, and restart.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException");
                    try {
                        videoDecoder.stop();
                        setupAsyncCallback(); // Re-set callback after stop() for reliable async mode
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
                    }
                }

                // For "non-recoverable" exceptions on L+, we can call reset() to recover
                // without having to recreate the entire decoder again.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to reset decoder after CodecException");
                    try {
                        videoDecoder.reset();
                        setupAsyncCallback(); // reset() clears callback, must re-set before configure
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the reset, we'll have to resort to
                        // releasing and recreating the decoder now.
                    }
                }

                // If we _still_ haven't managed to recover, go for the nuclear option and just
                // throw away the old decoder and reinitialize a new one from scratch.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException");
                    videoDecoder.release();

                    try {
                        int err = initializeDecoder(true);
                        if (err != 0) {
                            throw new IllegalStateException("Decoder reset failed: " + err);
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        // If we failed to recover after all of these attempts, just crash
                        if (!reportedCrash) {
                            reportedCrash = true;
                            crashListener.notifyCrash(e);
                        }
                        throw new RendererException(createDiagnostics(), e);
                    }
                }

                // Update frame pacing controller with potentially new decoder reference
                framePacingController.updateDecoder(videoDecoder);

                // Wake all quiesced threads and allow them to begin work again
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();
            } else {
                // If we haven't quiesced all threads yet, wait to be signalled after recovery.
                // The final thread to be quiesced will handle the codec recovery.
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: " + codecRecoveryThreadQuiescedFlags);
                        codecRecoveryMonitor.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();

                        // InterruptedException clears the thread's interrupt status. Since we can't
                        // handle that here, we will re-interrupt the thread to set the interrupt
                        // status back to true.
                        Thread.currentThread().interrupt();

                        break;
                    }
                }
            }
        }

        return true;
    }

    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                // If the exception is non-recoverable or we already require a reset, perform a reset.
                // If we have no prior unrecoverable failure, we will try a restart instead.
                if (codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException");
                        e.printStackTrace();
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                        e.printStackTrace();
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                } else if (!codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                        e.printStackTrace();
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        } else {
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException");
                    e.printStackTrace();
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
                    e.printStackTrace();
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
                    e.printStackTrace();
                } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                }

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            //
            // There seems to be a race condition with decoder/surface teardown causing some
            // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
            // To workaround this while allowing real exceptions to propagate, we will eat the
            // first exception. If we are still receiving exceptions 3 seconds later, we will
            // throw the original exception again.
            //
            if (initialException != null) {
                // This isn't the first time we've had an exception processing video
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
                    if (!reportedCrash) {
                        reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw initialException;
                }
            } else {
                // This is the first exception we've hit
                initialException = new RendererException(createDiagnostics(), e);
                initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    /**
     * Delivers a decoded frame to the appropriate output path based on frame pacing mode.
     * Used by both async callbacks and the sync renderer thread.
     */
    private void deliverDecodedFrame(int bufferIndex) {
        if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED ||
                prefs.framePacing == PreferenceConfiguration.FRAME_PACING_EXPERIMENTAL_LOW_LATENCY ||
                prefs.framePacing == PreferenceConfiguration.FRAME_PACING_PRECISE_SYNC) {
            // Buffered modes - queue for frame pacing controller
            framePacingController.offerOutputBuffer(bufferIndex);
        } else {
            // Direct render modes (MIN_LATENCY, MAX_SMOOTHNESS, CAP_FPS)
            try {
                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                    videoDecoder.releaseOutputBuffer(bufferIndex, 0);
                } else {
                    videoDecoder.releaseOutputBuffer(bufferIndex, System.nanoTime());
                }
                activeWindowVideoStats.totalFramesRendered++;
            } catch (IllegalStateException e) {
                handleDecoderException(e);
            }
        }
    }

    /**
     * Sets up the async MediaCodec callback for event-driven input/output buffer handling.
     * Must be called before configure() on API 30+.
     */
    private void setupAsyncCallback() {
        if (!asyncModeEnabled || videoDecoder == null) return;

        availableInputBuffers.clear();

        videoDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                if (!stopping) {
                    availableInputBuffers.offer(index);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                                @NonNull BufferInfo info) {
                if (stopping) return;

                numFramesOut++;

                // Calculate decoder time
                long delta = calculateDecoderTime(info.presentationTimeUs);
                if (delta >= 0 && delta < 1000) {
                    activeWindowVideoStats.decoderTimeMs += delta;
                    if (!USE_FRAME_RENDER_TIME) {
                        activeWindowVideoStats.totalTimeMs += delta;
                    }
                }

                // Deliver to frame pacing
                deliverDecodedFrame(index);

                doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                handleDecoderException(e);
                doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                LimeLog.info("Output format changed (async)");
                outputFormat = format;
                LimeLog.info("New output format: " + outputFormat);

                // Re-apply DataSpace after format change
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        hdrDataSpace != 0 && renderTarget != null) {
                    int currentDataSpace = MoonBridge.nativeGetSurfaceDataSpace(
                            renderTarget.getSurface());
                    if (currentDataSpace != hdrDataSpace) {
                        MoonBridge.nativeSetSurfaceDataSpace(
                                renderTarget.getSurface(), hdrDataSpace);
                        LimeLog.info("Re-applied Surface DataSpace: 0x" +
                                Integer.toHexString(hdrDataSpace));
                    }
                }
            }
        }, codecCallbackHandler);
    }

    private void startRendererThread() {
        if (asyncModeEnabled) {
            // In async mode, output is handled by onOutputBufferAvailable callback.
            // No renderer thread needed.
            return;
        }

        rendererThread = new Thread() {
            @Override
            public void run() {
                // Create PerformanceHint session for this thread
                perfBoostManager.createHintSession(refreshRate);

                BufferInfo info = new BufferInfo();
                while (!stopping) {
                    try {
                        // Try to output a frame
                        int outIndex = videoDecoder.dequeueOutputBuffer(info, 50000);
                        if (outIndex >= 0) {
                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;

                            numFramesOut++;

                            // Render the latest frame now if frame pacing isn't in balanced mode or Surface Flinger mode
                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED &&
                                    prefs.framePacing != PreferenceConfiguration.FRAME_PACING_EXPERIMENTAL_LOW_LATENCY &&
                                    prefs.framePacing != PreferenceConfiguration.FRAME_PACING_PRECISE_SYNC) {
                                // Get the last output buffer in the queue
                                while ((outIndex = videoDecoder.dequeueOutputBuffer(info, 0)) >= 0) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);

                                    numFramesOut++;

                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                }

                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // In max smoothness or cap FPS mode, we want to never drop frames
                                    // Use a PTS that will cause this frame to never be dropped
                                    videoDecoder.releaseOutputBuffer(lastIndex, 0);
                                } else {
                                    // Use a PTS that will cause this frame to be dropped if another comes in within
                                    // the same V-sync period
                                    videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());
                                }

                                activeWindowVideoStats.totalFramesRendered++;
                            } else {
                                // Buffered modes: deliver to frame pacing controller
                                framePacingController.offerOutputBuffer(lastIndex);
                            }

                            // Add delta time to the totals (excluding probable outliers)
                            long delta = calculateDecoderTime(presentationTimeUs);
                            if (delta >= 0 && delta < 1000) {
                                activeWindowVideoStats.decoderTimeMs += delta;
                                if (!USE_FRAME_RENDER_TIME) {
                                    activeWindowVideoStats.totalTimeMs += delta;
                                }
                                // Report to PerformanceHintManager for DVFS optimization
                                perfBoostManager.reportActualWorkDuration(delta * 1000000L);
                            }
                        } else {
                            switch (outIndex) {
                                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                    LimeLog.info("Output format changed");
                                    outputFormat = videoDecoder.getOutputFormat();
                                    LimeLog.info("New output format: " + outputFormat);

                                    // Re-apply DataSpace after format change — some decoders reset it
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                            hdrDataSpace != 0 && renderTarget != null) {
                                        int currentDataSpace = MoonBridge.nativeGetSurfaceDataSpace(
                                                renderTarget.getSurface());
                                        if (currentDataSpace != hdrDataSpace) {
                                            MoonBridge.nativeSetSurfaceDataSpace(
                                                    renderTarget.getSurface(), hdrDataSpace);
                                            LimeLog.info("Re-applied Surface DataSpace: 0x" +
                                                    Integer.toHexString(hdrDataSpace));
                                        }
                                    }
                                    break;
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
                                default:
                                    break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
            }
        };
        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    private boolean fetchNextInputBuffer() {
        long startTime;
        boolean codecRecovered;

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        startTime = SystemClock.uptimeMillis();

        try {
            // If we don't have an input buffer index yet, fetch one now
            if (asyncModeEnabled) {
                // Async mode: input buffers arrive via onInputBufferAvailable callback
                while (nextInputBufferIndex < 0 && !stopping) {
                    try {
                        Integer index = availableInputBuffers.poll(1, TimeUnit.SECONDS);
                        if (index != null) {
                            nextInputBufferIndex = index;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } else {
                // Sync mode: poll the decoder directly
                while (nextInputBufferIndex < 0 && !stopping) {
                    nextInputBufferIndex = videoDecoder.dequeueInputBuffer(5000);
                }
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Using the new getInputBuffer() API on Lollipop allows
                // the framework to do some performance optimizations for us
                nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                if (nextInputBuffer == null) {
                    // According to the Android docs, getInputBuffer() can return null "if the
                    // index is not a dequeued input buffer". I don't think this ever should
                    // happen but if it does, let's try to get a new input buffer next time.
                    nextInputBufferIndex = -1;
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        int deltaMs = (int) (SystemClock.uptimeMillis() - startTime);

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: " + deltaMs + " ms");
        }

        if (nextInputBuffer == null) {
            // We've been hung for 5 seconds and no other exception was reported,
            // so generate a decoder hung exception
            if (deltaMs >= 5000 && initialException == null) {
                DecoderHungException decoderHungException = new DecoderHungException(deltaMs);
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(createDiagnostics(), decoderHungException);
            }

            return false;
        }

        return true;
    }

    @Override
    public void start() {
        startRendererThread();
        framePacingController.start(videoDecoder, refreshRate);

        // Start thermal monitoring to warn about throttling
        perfBoostManager.startThermalMonitoring(status ->
                LimeLog.warning("Severe thermal throttling (status " + status +
                        "), consider reducing stream quality"));
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;

        // Clear timestamp tracking map
        timestampToEnqueueTime.clear();

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }

        // Stop frame pacing threads (Choreographer, PreciseSync)
        framePacingController.prepareForStop();

        // Stop any active codec recovery operations
        synchronized (codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
            codecRecoveryMonitor.notifyAll();
        }
    }

    @Override
    public void stop() {
        // May be called already, but we'll call it now to be safe
        prepareForStop();

        // Wait for frame pacing threads (Choreographer, PreciseSync)
        framePacingController.joinThreads();

        // Wait for the renderer thread to shut down (sync mode only)
        if (rendererThread != null) {
            try {
                rendererThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        // Close performance management
        perfBoostManager.close();
    }

    @Override
    public void cleanup() {
        if (videoDecoder != null) {
            try {
                videoDecoder.release();
            } catch (Exception e) {
                // Ignore exceptions during shutdown
                LimeLog.warning("Exception during decoder release: " + e.getMessage());
            }
        }
        timestampToEnqueueTime.clear();

        // Stop codec callback thread (async mode)
        if (codecCallbackThread != null) {
            codecCallbackThread.quitSafely();
            codecCallbackThread = null;
        }
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        // HDR metadata is only supported in Android 7.0 and later, so don't bother
        // restarting the codec on anything earlier than that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null;
            } else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata;
            } else {
                // Nothing to do
                return;
            }

            // If we reach this point, we need to restart the MediaCodec instance to
            // pick up the HDR metadata change. This will happen on the next input
            // or output buffer.

            // HACK: Reset codec recovery attempt counter, since this is an expected "recovery"
            codecRecoveryAttempts = 0;

            // Promote None/Flush to Restart and leave Reset alone
            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
            }
        }
    }

    @Override
    public void onResolutionChanged(int width, int height) {
        // Skip if resolution hasn't actually changed
        if (width == initialWidth && height == initialHeight) {
            return;
        }
        
        LimeLog.info("Decoder notified of resolution change: " + initialWidth + "x" + initialHeight + " -> " + width + "x" + height);
        
        // Check if new resolution exceeds current decoder configuration
        boolean needsRestart = width > initialWidth || height > initialHeight;
        
        // Update tracked resolution
        initialWidth = width;
        initialHeight = height;
        
        if (needsRestart) {
            LimeLog.info("New resolution exceeds decoder config, triggering codec restart");
            
            // Reset recovery counter since this is an expected restart
            codecRecoveryAttempts = 0;
            
            // Promote to restart: None->Restart or Flush->Restart
            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
            }
        }
    }

    private boolean queueNextInputBuffer(long timestampUs, int codecFlags) {
        boolean codecRecovered;

        try {
            // Record the enqueue time for this timestamp
            timestampToEnqueueTime.put(timestampUs, SystemClock.uptimeMillis());

            if (linearBlockEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Modern QueueRequest API with LinearBlock for potential copy-free path
                try {
                    int dataSize = nextInputBuffer.position();
                    MediaCodec.LinearBlock block = MediaCodec.LinearBlock.obtain(
                            Math.max(dataSize, 4096), codecNameArray);
                    try {
                        ByteBuffer mapped = block.map();
                        nextInputBuffer.flip();
                        mapped.put(nextInputBuffer);

                        videoDecoder.getQueueRequest(nextInputBufferIndex)
                                .setLinearBlock(block, 0, dataSize)
                                .setPresentationTimeUs(timestampUs)
                                .setFlags(codecFlags)
                                .queue();
                    } finally {
                        block.recycle();
                    }
                } catch (Exception e) {
                    // Fall back to standard path on any failure
                    LimeLog.warning("LinearBlock failed, falling back: " + e.getMessage());
                    linearBlockEnabled = false;
                    nextInputBuffer.flip();
                    videoDecoder.queueInputBuffer(nextInputBufferIndex,
                            0, nextInputBuffer.limit(), timestampUs, codecFlags);
                }
            } else {
                videoDecoder.queueInputBuffer(nextInputBufferIndex,
                        0, nextInputBuffer.position(),
                        timestampUs, codecFlags);
            }

            // We need a new buffer now
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } catch (IllegalStateException e) {
            if (handleDecoderException(e)) {
                // We encountered a transient error. In this case, just hold onto the buffer
                // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
                // false to trigger an IDR frame to recover.
                nextInputBuffer.clear();
            } else {
                // We encountered a non-transient error. In this case, we will simply leak the
                // buffer because we cannot be sure we will ever succeed in queuing it.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        // Fetch a new input buffer now while we have some time between frames
        // to have it ready immediately when the next frame arrives.
        //
        // We must propagate the return value here in order to properly handle
        // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
        // never get an IDR frame to complete the recovery process.
        return fetchNextInputBuffer();
    }

    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        if (stopping || isProcessingPaused) {
            // Don't bother if we're stopping or paused
            return MoonBridge.DR_OK;
        }

        if (needsIdrOnResume) {
            if (frameType != MoonBridge.FRAME_TYPE_IDR) {
                // Request an IDR frame to recover after resume
                return MoonBridge.DR_NEED_IDR;
            }
            // We got our IDR frame
            needsIdrOnResume = false;
        }

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            // We can receive the same "frame" multiple times if it's an IDR frame.
            // In that case, each frame start NALU is submitted independently.
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.frameLossEvents++;
        }

        // Reset CSD data for each IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear();
            spsBuffers.clear();
            ppsBuffers.clear();
        }

        lastFrameNumber = frameNumber;

        // Flip stats windows roughly every second
        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            VideoStats lastTwo = new VideoStats();
            lastTwo.add(lastWindowVideoStats);
            lastTwo.add(activeWindowVideoStats);
            VideoStatsFps fps = lastTwo.getFps();
            String decoder;

            if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                decoder = avcDecoder.getName();
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                decoder = hevcDecoder.getName();
            } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                decoder = av1Decoder.getName();
            } else {
                decoder = "(unknown)";
            }
            float decodeTimeMs = (float) lastTwo.decoderTimeMs / lastTwo.totalFramesReceived;
            long rttInfo = MoonBridge.getEstimatedRttInfo();
            float lostFrameRate = (float) lastTwo.framesLost / lastTwo.totalFrames * 100;
            float minHostProcessingLatency = (float) lastTwo.minHostProcessingLatency / 10;
            float maxHostProcessingLatency = (float) lastTwo.minHostProcessingLatency / 10;
            float aveHostProcessingLatency = (float) lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency;

            // 计算平均“解码+渲染”总时间
            float aveTotalProcessingTimeMs = 0;
            if (lastTwo.totalFramesRendered > 0) {
                aveTotalProcessingTimeMs = (float) lastTwo.totalTimeMs / lastTwo.totalFramesRendered;
            }

            // 计算平均"纯渲染延迟"
            // 注意：这里用总处理时间减去解码时间。如果结果为负，说明数据有抖动，取0即可。
            float avePureRenderingLatencyMs = Math.max(0, aveTotalProcessingTimeMs - decodeTimeMs);

            PerformanceInfo performanceInfo = new PerformanceInfo();
            performanceInfo.context = context;
            performanceInfo.initialWidth = initialWidth;
            performanceInfo.initialHeight = initialHeight;
            performanceInfo.decoder = decoder;
            performanceInfo.totalFps = fps.totalFps;
            performanceInfo.receivedFps = fps.receivedFps;
            performanceInfo.renderedFps = fps.renderedFps;
            performanceInfo.lostFrameRate = lostFrameRate;
            performanceInfo.rttInfo = rttInfo;
            performanceInfo.framesWithHostProcessingLatency = frameHostProcessingLatency;
            performanceInfo.isHdrActive = (currentHdrMetadata != null); // 基于实际HDR元数据状态
            performanceInfo.minHostProcessingLatency = minHostProcessingLatency;
            performanceInfo.maxHostProcessingLatency = maxHostProcessingLatency;
            performanceInfo.aveHostProcessingLatency = aveHostProcessingLatency;
            performanceInfo.decodeTimeMs = decodeTimeMs;
            performanceInfo.renderingLatencyMs = avePureRenderingLatencyMs;
            performanceInfo.totalTimeMs = aveTotalProcessingTimeMs;
            performanceInfo.onePercentLowFps = frameIntervalTracker.getOnePercentLowFps();

            perfListener.onPerfUpdateV(performanceInfo);
            perfListener.onPerfUpdateWG(performanceInfo);

            globalVideoStats.add(activeWindowVideoStats);
            lastWindowVideoStats.copy(activeWindowVideoStats);
            activeWindowVideoStats.clear();
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        }

        boolean csdSubmittedForThisFrame = false;

        // IDR frames require special handling for CSD buffer submission
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            // H264 SPS
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++;

                SpsPatcher.PatchResult result = spsPatcher.patchSps(
                        decodeUnitData, decodeUnitLength,
                        refFrameInvalidationActive, initialWidth, initialHeight,
                        refreshRate, needsBaselineSpsHack);

                if (result.getSavedSps() != null) {
                    savedSps = result.getSavedSps();
                }

                spsBuffers.add(result.getPatchedNalu());
                return MoonBridge.DR_OK;
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            } else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            } else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : vpsBuffers) {
                        nextInputBuffer.put(vpsBuffer);
                    }
                    for (byte[] spsBuffer : spsBuffers) {
                        nextInputBuffer.put(spsBuffer);
                    }
                    for (byte[] ppsBuffer : ppsBuffers) {
                        nextInputBuffer.put(ppsBuffer);
                    }

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Remember that we already submitted CSD for this frame, so we don't do it
                    // again in the fused IDR case below.
                    csdSubmittedForThisFrame = true;

                    // Remember that we submitted CSD globally for this MediaCodec instance
                    submittedCsd = true;

                    if (needsBaselineSpsHack) {
                        needsBaselineSpsHack = false;

                        if (!replaySps()) {
                            return MoonBridge.DR_NEED_IDR;
                        }

                        LimeLog.info("SPS replay complete");
                    }
                }
            }
        }

        if (frameHostProcessingLatency != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency != 0) {
                activeWindowVideoStats.minHostProcessingLatency = (char) Math.min(activeWindowVideoStats.minHostProcessingLatency, frameHostProcessingLatency);
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency;
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1;
        }
        activeWindowVideoStats.maxHostProcessingLatency = (char) Math.max(activeWindowVideoStats.maxHostProcessingLatency, frameHostProcessingLatency);
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency;

        activeWindowVideoStats.totalFramesReceived++;
        activeWindowVideoStats.totalFrames++;

        if (!FRAME_RENDER_TIME_ONLY) {
            // Count time from first packet received to enqueue time as receive time
            // We will count DU queue time as part of decoding, because it is directly
            // caused by a slow decoder.
            // receiveTimeUs and enqueueTimeUs are in microseconds, convert to milliseconds
            activeWindowVideoStats.totalTimeMs += (enqueueTimeUs - receiveTimeUs) / 1000;
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR;
        }

        int codecFlags = 0;

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;

            // If we are using fused IDR frames, submit the CSD with each IDR frame
            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (byte[] vpsBuffer : vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : ppsBuffers) {
                    nextInputBuffer.put(ppsBuffer);
                }
            }
        }

        long timestampUs = enqueueTimeUs;
        if (timestampUs <= lastTimestampUs) {
            // We can't submit multiple buffers with the same timestamp
            // so bump it up by one before queuing
            timestampUs = lastTimestampUs + 1;
        }
        lastTimestampUs = timestampUs;

        numFramesIn++;

        if (decodeUnitLength > nextInputBuffer.limit() - nextInputBuffer.position()) {
            IllegalArgumentException exception = new IllegalArgumentException(
                    "Decode unit length " + decodeUnitLength + " too large for input buffer " + nextInputBuffer.limit());
            if (!reportedCrash) {
                reportedCrash = true;
                crashListener.notifyCrash(exception);
            }
            throw new RendererException(createDiagnostics(), exception);
        }

        // Copy data from our buffer list into the input buffer
        nextInputBuffer.put(decodeUnitData, 0, decodeUnitLength);

        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    private boolean replaySps() {
        if (!fetchNextInputBuffer()) {
            return false;
        }

        byte[] replayNalu = spsPatcher.buildReplaySps(savedSps);
        nextInputBuffer.put(replayNalu);

        // No need for the SPS anymore
        savedSps = null;

        // Queue the new SPS
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (directSubmit) {
            capabilities |= MoonBridge.CAPABILITY_DIRECT_SUBMIT;
        }

        return capabilities;
    }

    RendererDiagnostics createDiagnostics() {
        return new RendererDiagnostics(
                numVpsIn, numSpsIn, numPpsIn, numFramesIn, numFramesOut,
                videoFormat, initialWidth, initialHeight, refreshRate,
                prefs.bitrate, prefs.framePacing, consecutiveCrashCount,
                adaptivePlayback, refFrameInvalidationActive, fusedIdrFrame,
                glRenderer, avcDecoder, hevcDecoder, av1Decoder,
                configuredFormat, inputFormat, outputFormat,
                globalVideoStats.totalFramesReceived, globalVideoStats.totalFramesRendered,
                globalVideoStats.framesLost, globalVideoStats.frameLossEvents,
                getAverageEndToEndLatency(), getAverageDecoderLatency());
    }

    public int getAverageEndToEndLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int) (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived);
    }

    public int getAverageDecoderLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int) (globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived);
    }

    @SuppressLint("DefaultLocale")
    public String getSurfaceFlingerStats() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_PRECISE_SYNC) {
            return null;
        }

        if (globalVideoStats.totalFramesReceived == 0) {
            return null;
        }

        // 计算跳帧率
        // surfaceFlingerSkippedFrames: Surface Flinger线程因缓冲区为空而跳过的帧
        // 总跳帧 = SF线程跳帧 + 网络丢帧
        long totalFramesExpected = framePacingController.getSurfaceFlingerFrameCount()
                + framePacingController.getSurfaceFlingerSkippedFrames();
        float skipRate = 0f;

        if (totalFramesExpected > 0) {
            skipRate = (float) framePacingController.getSurfaceFlingerSkippedFrames()
                    / totalFramesExpected * 100f;
        }

        return String.format("[精确同步: %d渲染/%d接收, 跳帧率: %.1f%%]",
                globalVideoStats.totalFramesRendered,
                globalVideoStats.totalFramesReceived,
                skipRate);
    }



    // Calculate decoder time using the enqueue time we recorded
    // presentationTimeUs: presentation timestamp in microseconds (from MediaCodec)
    // Returns: decoder time in milliseconds
    private long calculateDecoderTime(long presentationTimeUs) {
        // Look up the enqueue time for this timestamp (stored in milliseconds)
        Long enqueueTimeMs = timestampToEnqueueTime.remove(presentationTimeUs);
        if (enqueueTimeMs != null) {
            long delta = SystemClock.uptimeMillis() - enqueueTimeMs;
            return delta > 0 && delta < 1000 ? delta : 0;
        }
        // If we can't find the enqueue time, return 0
        return 0;
    }
}
