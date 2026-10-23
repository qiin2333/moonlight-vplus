package com.limelight.binding.audio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.util.concurrent.Callable;

/** Debug-only, explicit file selection and Play. Never starts from a URI extra or network session. */
public final class MatReplayActivity extends Activity {
    private TextView status;
    private MatRecording recording;
    private Thread task;
    private volatile boolean destroyed;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("MAT offline diagnostic: HDMI/eARC only. Select the verified 3.56s recording, then Play.\n" +
                "A successful byte hash does NOT prove Soundbar Atmos recognition.");
        Button select = new Button(this); select.setText("Select and verify MAT recording");
        select.setOnClickListener(v -> {
            if (task != null && task.isAlive()) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(intent, 1); }
            catch (android.content.ActivityNotFoundException e) {
                report("This TV has no document picker. Use the standalone diagnostic APK's built-in verified clip.");
            }
        });
        Button play = new Button(this); play.setText("Play verified MAT over HDMI");
        play.setOnClickListener(v -> play());
        Button stop = new Button(this); stop.setText("Stop"); stop.setOnClickListener(v -> cancel());
        layout.addView(status); layout.addView(select); layout.addView(play); layout.addView(stop);
        try {
            for (String asset : getAssets().list("")) if ("verified-mat.raw".equals(asset)) {
                Button builtIn = new Button(this); builtIn.setText("Verify built-in 3.56s MAT recording");
                builtIn.setOnClickListener(v -> verify(() -> getAssets().open("verified-mat.raw")));
                layout.addView(builtIn);
                break;
            }
        } catch (java.io.IOException ignored) { /* Full Moonlight builds need not bundle a recording. */ }
        setContentView(layout);
    }
    private void report(String text) { runOnUiThread(() -> { if (!destroyed) status.setText(text); }); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        verify(() -> getContentResolver().openInputStream(data.getData()));
    }
    private void verify(Callable<InputStream> source) {
        if (task != null && task.isAlive()) return;
        recording = null;
        task = new Thread(() -> {
            try (InputStream input = source.call()) {
                if (input == null) throw new IllegalStateException("Cannot read selected file");
                MatRecording verified = MatRecording.readVerified(input);
                runOnUiThread(() -> { if (!destroyed) {
                    recording = verified;
                    status.setText("Verified 178 cycles / 3.56 seconds. No output opened.\nSHA256: " +
                            MatRecording.VERIFIED_SHA256 + "\nPress Play to test HDMI MAT.");
                }});
            } catch (Exception e) { report("Recording rejected: " + e); }
        }, "mat-file-verifier");
        task.start();
    }
    private void play() {
        if (recording == null || (task != null && task.isAlive())) return;
        final MatRecording source = recording;
        task = new Thread(() -> {
            MatAudioTrackSink sink = new MatAudioTrackSink(this);
            try (MatCarrierPlayer player = new MatCarrierPlayer(sink)) {
                player.start();
                if (!player.awaitReady(3000)) throw new IllegalStateException("MAT output setup failed: " + player.error());
                try (MatNativeReceiver receiver = new MatNativeReceiver(player)) {
                    source.replay(receiver);
                    if (!player.awaitCompletion(5000))
                        throw new IllegalStateException("MAT output did not complete: " + player.state() + " " + player.error());
                    if (!MatRecording.VERIFIED_SHA256.equals(player.submittedSha256()))
                        throw new IllegalStateException("AudioTrack submitted-byte SHA256 mismatch");
                    report("Submitted " + player.writtenCycles() + " cycles / " + player.submittedBytes() +
                            " bytes; playhead drained.\nSHA256: " + player.submittedSha256() + "\n" +
                            sink.diagnostics() + "\nCheck Soundbar input format and object movement separately.");
                }
            } catch (Throwable e) { report("MAT replay stopped: " + e); }
        }, "mat-recording-replay");
        task.start();
    }
    private void cancel() { if (task != null) task.interrupt(); }
    @Override protected void onPause() { super.onPause(); cancel(); }
    @Override protected void onDestroy() { destroyed = true; cancel(); recording = null; super.onDestroy(); }
}
