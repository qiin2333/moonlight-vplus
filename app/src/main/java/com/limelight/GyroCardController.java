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
        CompoundButton invertXSwitch = customView.findViewById(R.id.gyroInvertXSwitch);
        CompoundButton invertYSwitch = customView.findViewById(R.id.gyroInvertYSwitch);

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
            // 改为“灵敏度倍数”，越高越快。映射范围：0.5x .. 3.0x（步进 0.1）
            int max = 25; // 0..25 -> +0..2.5  => 0.5..3.0
            sensSeek.setMax(max);
            // 反推当前 multiplier 到 progress
            float mult = Math.max(0.5f, Math.min(3.0f, game.prefConfig.gyroSensitivityMultiplier > 0 ? game.prefConfig.gyroSensitivityMultiplier : 1.0f));
            int progress = Math.round((mult - 0.5f) / 0.1f);
            sensSeek.setProgress(progress);
            sensVal.setText(String.format("%.1fx", mult));

            sensSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                    float m = 0.5f + p * 0.1f;
                    game.prefConfig.gyroSensitivityMultiplier = m;
                    sensVal.setText(String.format("%.1fx", m));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override 
                public void onStopTrackingTouch(SeekBar seekBar) {
                    game.prefConfig.writePreferences(game);
                }
            });
        }

        if (invertXSwitch != null) {
            invertXSwitch.setChecked(game.prefConfig.gyroInvertXAxis);
            invertXSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                game.prefConfig.gyroInvertXAxis = isChecked;
                game.prefConfig.writePreferences(game);
            });
        }

        if (invertYSwitch != null) {
            invertYSwitch.setChecked(game.prefConfig.gyroInvertYAxis);
            invertYSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                game.prefConfig.gyroInvertYAxis = isChecked;
                game.prefConfig.writePreferences(game);
            });
        }
    }

    private void showActivationKeyDialog(TextView activationKeyText) {
        final CharSequence[] items = new CharSequence[]{
            game.getString(R.string.gyro_activation_always), 
            "LT (L2)", 
            "RT (R2)"
        };
        int checked;
        if (game.prefConfig.gyroActivationKeyCode == ControllerHandler.GYRO_ACTIVATION_ALWAYS) {
            checked = 0;
        } else if (game.prefConfig.gyroActivationKeyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            checked = 2;
        } else {
            checked = 1;
        }
        new AlertDialog.Builder(game, R.style.AppDialogStyle)
                .setTitle(R.string.gyro_activation_method)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        game.prefConfig.gyroActivationKeyCode = ControllerHandler.GYRO_ACTIVATION_ALWAYS;
                    } else if (which == 1) {
                        game.prefConfig.gyroActivationKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_L2;
                    } else {
                        game.prefConfig.gyroActivationKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_R2;
                    }
                    game.prefConfig.writePreferences(game);
                    if (activationKeyText != null) {
                        activationKeyText.setText(items[which]);
                    }
                    d.dismiss();
                })
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .show();
    }

    private void updateActivationKeyText(TextView activationKeyText) {
        String label;
        if (game.prefConfig.gyroActivationKeyCode == ControllerHandler.GYRO_ACTIVATION_ALWAYS) {
            label = game.getString(R.string.gyro_activation_always);
        } else if (game.prefConfig.gyroActivationKeyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            label = "RT (R2)";
        } else {
            label = "LT (L2)";
        }
        activationKeyText.setText(label);
    }
}
