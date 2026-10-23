package com.limelight.binding.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/** IEC only. No legacy PCM fallback, DSP, resampler, spatializer or TrueHD repacking. */
public final class MatAudioTrackSink implements MatCarrierPlayer.Sink {
    private final Context context;
    private AudioTrack track;
    private long wraps, previousLow;
    private int routeId = -1;
    private long nextRouteCheck;
    private boolean closed;
    private volatile String diagnostics = "not opened";
    public MatAudioTrackSink(Context context) { this.context = context.getApplicationContext(); }
    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }
    private static AudioFormat format() {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_IEC61937)
                .setSampleRate(MatCarrierPlayer.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND).build();
    }
    @Override public void start() {
        if (closed || track != null) throw new IllegalStateException("MAT sink cannot be reused");
        if (Build.VERSION.SDK_INT < 24) throw new IllegalStateException("IEC MAT needs Android 7+");
        int min = AudioTrack.getMinBufferSize(MatCarrierPlayer.RATE,
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937);
        if (min <= 0) throw new IllegalStateException("192kHz/7.1/IEC61937 unsupported: " + min);
        int direct = -1;
        if (Build.VERSION.SDK_INT >= 33) {
            direct = AudioManager.getDirectPlaybackSupport(format(), attributes());
            if ((direct & AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) == 0)
                throw new IllegalStateException("Route does not advertise IEC bitstream playback: " + direct);
        }
        long cycles = Math.max(2L, (min + (long)MatCarrierPlayer.CYCLE_BYTES - 1) / MatCarrierPlayer.CYCLE_BYTES);
        // A huge vendor minimum is not silently accepted as a low-latency route.
        if (cycles > 16) throw new IllegalStateException("IEC minimum exceeds 320ms diagnostic limit");
        int buffer = (int) cycles * MatCarrierPlayer.CYCLE_BYTES;
        track = new AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(format())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("IEC track not initialized");
        track.pause(); track.flush();
        wraps = previousLow = 0;
        // Focus ownership is local to this standalone replay, not the game's existing audio session.
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        focusLost = false;
        if (manager == null || manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("MAT replay audio focus denied");
        track.play();
        diagnostics = "IEC61937 192000Hz 7.1; requestedBuffer=" + buffer +
                "; actualBufferFrames=" + track.getBufferSizeInFrames() + "; direct=" + direct +
                "; receiver Atmos recognition NOT verified";
    }
    private volatile boolean focusLost;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) focusLost = true; // Do not duck/multiply a bitstream.
    };
    private void checkRoute() {
        if (focusLost) throw new IllegalStateException("MAT audio focus lost");
        long now = System.nanoTime();
        if (now < nextRouteCheck) return;
        nextRouteCheck = now + 50_000_000;
        AudioDeviceInfo device = track.getRoutedDevice();
        // Routing can be unknown until the first write; wait for a real route.
        if (device == null) return;
        int type = device.getType();
        boolean hdmi = type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC);
        if (!hdmi) throw new IllegalStateException("Refusing MAT on non-HDMI output: " + type);
        if (routeId != -1 && routeId != device.getId()) throw new IllegalStateException("MAT HDMI route changed; reconnect");
        routeId = device.getId();
    }
    @Override public int write(short[] words, int offset, int count) {
        checkRoute();
        return track.write(words, offset, count, AudioTrack.WRITE_NON_BLOCKING);
    }
    @Override public long playedFrames() {
        checkRoute();
        long low = track.getPlaybackHeadPosition() & 0xffffffffL;
        if (low < previousLow) {
            if (previousLow < 0xf0000000L || low > 0x10000000L)
                throw new IllegalStateException("IEC playhead reset unexpectedly");
            wraps += 1L << 32;
        }
        previousLow = low;
        return wraps + low;
    }
    public String diagnostics() { return diagnostics; }
    @Override public void close() {
        closed = true;
        if (track != null) {
            try { track.pause(); track.flush(); } finally { track.release(); track = null; }
        }
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) manager.abandonAudioFocus(focusListener);
    }
}
