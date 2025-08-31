package com.limelight;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.CompoundButton;

import com.limelight.binding.input.ControllerHandler;

/**
 * Encapsulates the gyro-to-right-stick control card logic.
 */
public class GyroCardController {
    private final Game game;

    public GyroCardController(Game game) {
        this.game = game;
    }

    public void setup(View customView, AlertDialog dialog) {
        View container = customView.findViewById(R.id.gyroAdjustmentContainer);
        if (container == null) return;

        TextView statusText = customView.findViewById(R.id.gyroStatusText);
        CompoundButton toggleSwitch = customView.findViewById(R.id.gyroToggleSwitch);
        View activationKeyContainer = customView.findViewById(R.id.gyroActivationKeyContainer);
        TextView activationKeyText = customView.findViewById(R.id.gyroActivationKeyText);
        SeekBar sensSeek = customView.findViewById(R.id.gyroSensitivitySeekBar);
        TextView sensVal = customView.findViewById(R.id.gyroSensitivityValueText);

        if (statusText != null) {
            statusText.setText(game.prefConfig.gyroToRightStick ? "ON" : "OFF");
        }

        if (toggleSwitch != null) {
            toggleSwitch.setChecked(game.prefConfig.gyroToRightStick);
            toggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                ControllerHandler ch = game.getControllerHandler();
                if (ch == null) {
                    Toast.makeText(game, "Failed to access controller", Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(!isChecked);
                    return;
                }
                ch.setGyroToRightStickEnabled(isChecked);
                if (statusText != null) statusText.setText(isChecked ? "ON" : "OFF");
            });
        }

        // 更新显示
        if (activationKeyText != null) {
            updateActivationKeyText(activationKeyText);
        }
        if (activationKeyContainer != null) {
            activationKeyContainer.setOnClickListener(v -> showActivationKeyDialog(activationKeyText));
        }

        if (sensSeek != null && sensVal != null) {
            // Map progress [6..36] -> [60..360] deg/s
            int current = (int) Math.max(60, Math.min(360, game.prefConfig.gyroFullDeflectionDps));
            int progress = Math.round(current / 10.0f);
            sensSeek.setMax(36);
            sensSeek.setProgress(progress);
            sensVal.setText(current + "°/s");

            sensSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                    int dps = Math.max(60, Math.min(360, p * 10));
                    game.prefConfig.gyroFullDeflectionDps = dps;
                    sensVal.setText(dps + "°/s");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    private void showActivationKeyDialog(TextView activationKeyText) {
        final CharSequence[] items = new CharSequence[] {"LT (L2)", "RT (R2)"};
        int checked = game.prefConfig.gyroActivationKeyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2 ? 1 : 0;
        new AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setTitle("Choose activation key")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        game.prefConfig.gyroActivationKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_L2;
                    } else {
                        game.prefConfig.gyroActivationKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_R2;
                    }
                    if (activationKeyText != null) {
                        updateActivationKeyText(activationKeyText);
                    }
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateActivationKeyText(TextView activationKeyText) {
        String label = game.prefConfig.gyroActivationKeyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2 ? "RT (R2)" : "LT (L2)";
        activationKeyText.setText(label);
    }
}


