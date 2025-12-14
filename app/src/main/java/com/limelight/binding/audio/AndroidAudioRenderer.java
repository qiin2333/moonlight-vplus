package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Spatializer;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;
    private final boolean enableSpatializer;

    private AudioTrack track;
    private Spatializer spatializer;

    // 保存当前的静音状态
    private boolean isMuted = false;
    // 保存目标音量增益。默认 1.0f (100%)
    private float mTargetVolume = 1.0f;
    // 标记是否处于暂停丢包状态
    private volatile boolean isProcessingPaused = false;

    // 保存初始化的配置参数，用于重建
    private MoonBridge.AudioConfiguration savedAudioConfig;
    private int savedSampleRate;
    private int savedSamplesPerFrame;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx, boolean enableSpatializer) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.enableSpatializer = enableSpatializer;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        
        // Enable spatialization attribute if supported and requested
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enableSpatializer) {
            attributesBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO);
        }
        
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }


    private int initializeAudioTrackInternal(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        // Initialize Spatializer if supported and enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enableSpatializer) {
            try {
                AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                spatializer = audioManager.getSpatializer();
                
                if (spatializer != null && spatializer.isAvailable()) {
                    // Check if the track can be spatialized
                    AudioAttributes attributes = track.getAudioAttributes();
                    AudioFormat trackFormat = track.getFormat();
                    
                    if (spatializer.canBeSpatialized(attributes, trackFormat)) {
                        LimeLog.info("Spatializer is available and track can be spatialized");
                        LimeLog.info("Spatializer enabled: " + spatializer.isEnabled());
                        LimeLog.info("Spatializer level: " + spatializer.getImmersiveAudioLevel());
                    } else {
                        LimeLog.warning("Spatializer is available but track cannot be spatialized");
                        spatializer = null;
                    }
                } else {
                    LimeLog.info("Spatializer is not available on this device");
                    spatializer = null;
                }
            } catch (Exception e) {
                LimeLog.warning("Failed to initialize Spatializer: " + e.getMessage());
                e.printStackTrace();
                spatializer = null;
            }
        }

        return 0;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        // 保存配置，供 resume 时使用
        this.savedAudioConfig = audioConfiguration;
        this.savedSampleRate = sampleRate;
        this.savedSamplesPerFrame = samplesPerFrame;

        return initializeAudioTrackInternal(audioConfiguration, sampleRate, samplesPerFrame);
    }

    // --- 暂停处理 ---
    public void pauseProcessing() {
        LimeLog.info("Audio: Pausing processing (releasing AudioTrack)");
        isProcessingPaused = true;

        // 释放 Spatializer
        spatializer = null;

        // 释放 AudioTrack
        if (track != null) {
            try {
                // 如果开启了 AudioFx，先关闭 session
                if (enableAudioFx) {
                    Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                    i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
                    i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
                    context.sendBroadcast(i);
                }

                track.pause();
                track.flush();
                track.release();
            } catch (Exception e) {
                LimeLog.warning("Error releasing audio track: " + e.getMessage());
            }
            track = null;
        }
    }

    // --- 恢复处理 ---
    public void resumeProcessing() {
        if (savedAudioConfig == null) {
            LimeLog.warning("Cannot resume audio: no saved configuration");
            return;
        }

        LimeLog.info("Audio: Resuming processing...");

        // 1. 重建 AudioTrack
        // 如果之前 initializeAudioTrackInternal 是 private 的，确保它现在能被访问
        int res = initializeAudioTrackInternal(savedAudioConfig, savedSampleRate, savedSamplesPerFrame);
        if (res != 0) {
            LimeLog.severe("Failed to recreate AudioTrack: " + res);
            return; // 重建失败就没法继续了
        }

        // 2. 恢复 AudioFx (如果开启)
        if (track != null && enableAudioFx) {
            try {
                Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
                i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
                i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
                i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
                context.sendBroadcast(i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 3. 恢复音量
        // 注意：这里强制确保 track 处于播放状态
        if (track != null) {
            try {
                track.play(); // 确保开始播放

                // 恢复之前的音量设置
                float vol = isMuted ? 0.0f : mTargetVolume;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(vol);
                } else {
                    track.setStereoVolume(vol, vol);
                }
            } catch (Exception e) {
                LimeLog.warning("Error restoring audio state: " + e.getMessage());
            }
        }

        // 4. 最后一步：解除暂停标志，允许数据写入
        isProcessingPaused = false;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {

        if (isProcessingPaused) {
            return; // 丢弃数据
        }

        if (track == null) return; // 防止未初始化导致的空指针

        if (MoonBridge.getPendingAudioDuration() < 40) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency at 40 ms in that situation.
            track.write(audioData, 0, audioData.length);
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }
    }

    @Override
    public void start() {
        if (track != null && enableAudioFx) {
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (track != null && enableAudioFx) {
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        if (track != null) {
            track.pause();
            track.flush();
            track.release();
        }
    }

    /**
     * 设置是否静音
     * @param muted true=静音 (增益设为0), false=恢复 (恢复到 mTargetVolume)
     */
    public void setMuted(boolean muted) {
        if (this.isMuted == muted) return; // 状态未变
        this.isMuted = muted;

        // 如果正处于暂停状态，只更新标记，不操作 AudioTrack（因为它不存在）
        if (isProcessingPaused || track == null) {
            return;
        }

        try {
            float vol = muted ? 0.0f : mTargetVolume;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.setVolume(vol);
            } else {
                track.setStereoVolume(vol, vol);
            }
        } catch (Exception e) {
            LimeLog.warning("Failed to set volume: " + e.getMessage());
        }
    }
}