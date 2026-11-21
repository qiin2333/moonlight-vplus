package com.limelight.binding.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import com.limelight.nvstream.jni.MoonBridge;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class MatOutputDeviceTest {
    @Test public void fullNativeLibraryLoadsWithMatCallbackSignature() {
        // Class initialization loads the full app .so and resolves JNI callback methods.
        assertNull(MoonBridge.bridgeArGetMatConsumer());
    }

    @Test public void noHdmiFailsBeforeWritingAnyCarrier() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean hdmi = false;
        if (Build.VERSION.SDK_INT >= 23) for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            hdmi |= d.getType() == AudioDeviceInfo.TYPE_HDMI || d.getType() == AudioDeviceInfo.TYPE_HDMI_ARC ||
                    (Build.VERSION.SDK_INT >= 31 && d.getType() == AudioDeviceInfo.TYPE_HDMI_EARC);
        }
        assumeFalse("Negative control needs a device without HDMI", hdmi);
        for (int attempt = 0; attempt < 2; attempt++) {
            MatAudioTrackSink sink = new MatAudioTrackSink(context);
            try {
                new MatPlaybackSession(sink).close();
                fail("MAT opened on a device without HDMI");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("No HDMI") ||
                        expected.getMessage().contains("Android 7+"));
            } finally { sink.close(); }
        }
    }
}
