package com.limelight.preferences;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceDialogFragmentCompat;

import com.limelight.R;

public class SeekBarPreferenceDialogFragment extends PreferenceDialogFragmentCompat {

    private static final int LONG_PRESS_DELAY = 400;
    private static final int LONG_PRESS_INTERVAL = 80;

    private SeekBar seekBar;
    private TextView valueText;

    public static SeekBarPreferenceDialogFragment newInstance(String key) {
        SeekBarPreferenceDialogFragment fragment = new SeekBarPreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private SeekBarPreference getPref() {
        return (SeekBarPreference) getPreference();
    }

    @Override
    protected void onBindDialogView(View layout) {
        super.onBindDialogView(layout);

        SeekBarPreference pref = getPref();
        // 确保从持久化存储加载最新值
        pref.refreshCurrentValue();

        // Message text
        TextView messageView = layout.findViewById(R.id.pref_seekbar_message);
        if (pref.dialogMessageText != null) {
            messageView.setText(pref.dialogMessageText);
            messageView.setVisibility(View.VISIBLE);
        }

        // Value display
        valueText = layout.findViewById(R.id.pref_seekbar_value);

        // +/- buttons (logarithmic mode only)
        ImageView btnMinus = layout.findViewById(R.id.pref_seekbar_btn_minus);
        ImageView btnPlus = layout.findViewById(R.id.pref_seekbar_btn_plus);
        if (pref.isLogarithmic) {
            btnMinus.setImageResource(R.drawable.ic_pref_minus);
            btnPlus.setImageResource(R.drawable.ic_pref_plus);
            btnMinus.setVisibility(View.VISIBLE);
            btnPlus.setVisibility(View.VISIBLE);
            setupLongPressView(btnMinus, -1);
            setupLongPressView(btnPlus, 1);
        }

        // SeekBar
        seekBar = layout.findViewById(R.id.pref_seekbar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (value < pref.minValue) {
                    seekBar.setProgress(pref.minValue);
                    return;
                }

                if (!pref.isLogarithmic) {
                    int roundedValue = Math.max(pref.minValue, Math.round((float) value / pref.stepSize) * pref.stepSize);
                    if (roundedValue != value) {
                        seekBar.setProgress(roundedValue);
                        return;
                    }
                }

                updateValueText(pref.isLogarithmic ? pref.linearToLog(value) : value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Tick marks
        if (pref.showTickMarks && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Drawable tickDrawable = requireContext().getDrawable(R.drawable.pref_seekbar_tick);
            seekBar.setTickMark(tickDrawable);
        }

        // Range labels
        TextView minLabel = layout.findViewById(R.id.pref_seekbar_min_label);
        TextView maxLabel = layout.findViewById(R.id.pref_seekbar_max_label);
        minLabel.setText(pref.formatDisplayValue(pref.minValue));
        maxLabel.setText(pref.formatDisplayValue(pref.maxValue));

        // Initialize seekbar
        seekBar.setMax(pref.maxValue);
        if (pref.keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(pref.keyStepSize);
        }
        seekBar.setProgress(pref.isLogarithmic && pref.currentValue > 0
                ? pref.logToLinear(pref.currentValue) : pref.currentValue);

        seekBar.post(seekBar::requestFocus);
    }

    private void updateValueText(int displayValue) {
        SeekBarPreference pref = getPref();
        String text = pref.formatDisplayValue(displayValue);
        if (pref.suffix != null) {
            text = text.concat(pref.suffix.length() > 1 ? " " + pref.suffix : pref.suffix);
        }
        valueText.setText(text);
    }

    private void adjustValue(int direction) {
        if (seekBar == null) return;
        SeekBarPreference pref = getPref();

        int currentProgress = seekBar.getProgress();
        int newProgress;

        if (pref.isLogarithmic) {
            int currentBitrate = pref.linearToLog(currentProgress);
            int adjustStep = currentBitrate > 50000 ? pref.stepSize * 2 : pref.stepSize;
            int newBitrate = Math.max(pref.minValue, Math.min(pref.maxValue, currentBitrate + direction * adjustStep));
            newProgress = pref.logToLinear(newBitrate);
        } else {
            newProgress = Math.max(pref.minValue, Math.min(pref.maxValue, currentProgress + direction * pref.stepSize));
        }

        seekBar.setProgress(newProgress);
    }

    private void setupLongPressView(View view, int direction) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] isLongPressing = {false};

        final Runnable repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isLongPressing[0]) {
                    adjustValue(direction);
                    handler.postDelayed(this, LONG_PRESS_INTERVAL);
                }
            }
        };

        view.setOnClickListener(v -> {
            if (!isLongPressing[0]) {
                adjustValue(direction);
            }
        });

        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressing[0] = false;
                    handler.postDelayed(() -> {
                        isLongPressing[0] = true;
                        adjustValue(direction);
                        handler.postDelayed(repeatRunnable, LONG_PRESS_INTERVAL);
                    }, LONG_PRESS_DELAY);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacksAndMessages(null);
                    isLongPressing[0] = false;
                    break;
            }
            return false;
        });
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult && seekBar != null) {
            SeekBarPreference pref = getPref();
            int valueToSave = pref.isLogarithmic ? pref.linearToLog(seekBar.getProgress()) : seekBar.getProgress();
            if (pref.callChangeListener(valueToSave)) {
                pref.setProgress(valueToSave);
            }
        }
    }
}
