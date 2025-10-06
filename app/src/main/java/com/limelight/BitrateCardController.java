package com.limelight;

import android.app.AlertDialog;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.nvstream.NvConnection;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Encapsulates the bitrate adjustment card logic shown in the Game Menu dialog.
 */
public class BitrateCardController {
    private final Game game;
    private final NvConnection conn;

    public BitrateCardController(Game game, NvConnection conn) {
        this.game = game;
        this.conn = conn;
    }

    public void setup(View customView, AlertDialog dialog) {
        View bitrateContainer = customView.findViewById(R.id.bitrateAdjustmentContainer);
        SeekBar bitrateSeekBar = customView.findViewById(R.id.bitrateSeekBar);
        TextView currentBitrateText = customView.findViewById(R.id.currentBitrateText);
        TextView bitrateValueText = customView.findViewById(R.id.bitrateValueText);
        ImageView bitrateTipIcon = customView.findViewById(R.id.bitrateTipIcon);

        if (bitrateContainer == null || bitrateSeekBar == null ||
                currentBitrateText == null || bitrateValueText == null || bitrateTipIcon == null) {
            return;
        }

        int currentBitrate = conn.getCurrentBitrate();
        int currentBitrateMbps = currentBitrate / 1000;

        currentBitrateText.setText(String.format(game.getResources().getString(R.string.game_menu_bitrate_current), currentBitrateMbps));

        // Configure seekbar range: 500 kbps .. 200000 kbps (step 100)
        bitrateSeekBar.setMax(1995);
        bitrateSeekBar.setProgress((currentBitrate - 500) / 100);

        bitrateValueText.setText(String.format("%d Mbps", currentBitrateMbps));

        bitrateTipIcon.setOnClickListener(v -> {
            new AlertDialog.Builder(game, R.style.AppDialogStyle)
                    .setMessage(game.getResources().getString(R.string.game_menu_bitrate_tip))
                    .setPositiveButton("懂了", null)
                    .show();
        });

        // Debounced apply
        final Handler bitrateHandler = new Handler();
        final Runnable bitrateApplyRunnable = new Runnable() {
            @Override
            public void run() {
                int newBitrate = (bitrateSeekBar.getProgress() * 100) + 500;
                adjustBitrate(newBitrate);
            }
        };

        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int newBitrate = (progress * 100) + 500;
                    int newBitrateMbps = newBitrate / 1000;
                    bitrateValueText.setText(String.format("%d Mbps", newBitrateMbps));

                    bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 500);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                bitrateHandler.removeCallbacks(bitrateApplyRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                int newBitrate = (seekBar.getProgress() * 100) + 500;
                adjustBitrate(newBitrate);
            }
        });

        bitrateSeekBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    bitrateHandler.removeCallbacks(bitrateApplyRunnable);
                    bitrateHandler.postDelayed(bitrateApplyRunnable, 300);
                    return false;
                }
            }
            return false;
        });
    }

    private void adjustBitrate(int bitrateKbps) {
        try {
            Toast.makeText(game, "正在调整码率...", Toast.LENGTH_SHORT).show();

            conn.setBitrate(bitrateKbps, new NvConnection.BitrateAdjustmentCallback() {
                @Override
                public void onSuccess(int newBitrate) {
                    game.runOnUiThread(() -> {
                        try {
                            // Update prefConfig with the new bitrate so it gets saved when streaming ends
                            game.prefConfig.bitrate = newBitrate;
                            
                            String successMessage = String.format(game.getResources().getString(R.string.game_menu_bitrate_adjustment_success), newBitrate / 1000);
                            Toast.makeText(game, successMessage, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show success toast: " + e.getMessage());
                        }
                    });
                }

                @Override
                public void onFailure(String errorMessage) {
                    game.runOnUiThread(() -> {
                        try {
                            String errorMsg = game.getResources().getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + errorMessage;
                            Toast.makeText(game, errorMsg, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            LimeLog.warning("Failed to show error toast: " + e.getMessage());
                        }
                    });
                }
            });

        } catch (Exception e) {
            game.runOnUiThread(() -> {
                try {
                    Toast.makeText(game, game.getResources().getString(R.string.game_menu_bitrate_adjustment_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                } catch (Exception toastException) {
                    LimeLog.warning("Failed to show error toast: " + toastException.getMessage());
                }
            });
        }
    }
}


