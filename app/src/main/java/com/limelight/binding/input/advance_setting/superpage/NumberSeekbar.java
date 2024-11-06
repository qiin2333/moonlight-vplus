package com.limelight.binding.input.advance_setting.superpage;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.R;

public class NumberSeekbar extends LinearLayout {

    private TextView numberSeekbarTitle;
    private View numberSeekbarMinus;
    private TextView numberSeekbarNumber;
    private View numberSeekbarAdd;
    private SeekBar numberSeekbarSeekbar;
    private OnNumberSeekbarChangeListener onNumberSeekbarChangeListener;

    public interface OnNumberSeekbarChangeListener{
        void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser);
        void onStartTrackingTouch(SeekBar seekBar);
        void onStopTrackingTouch(SeekBar seekBar);
    }



    public NumberSeekbar(Context context) {
        super(context);
        init(context,null);
    }

    public NumberSeekbar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context,attrs);
    }

    public NumberSeekbar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context,attrs);
    }

    public NumberSeekbar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context,attrs);
    }

    private void init(Context context,AttributeSet attrs) {
        LayoutInflater.from(context).inflate(R.layout.number_seekbar, this, true);

        numberSeekbarTitle = findViewById(R.id.number_seekbar_title);
        numberSeekbarMinus = findViewById(R.id.number_seekbar_minus);
        numberSeekbarNumber = findViewById(R.id.number_seekbar_number);
        numberSeekbarAdd = findViewById(R.id.number_seekbar_add);
        numberSeekbarSeekbar = findViewById(R.id.number_seekbar_seekbar);
        numberSeekbarNumber.setText(String.valueOf(numberSeekbarSeekbar.getProgress()));

        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.NumberSeekbar,
                    0, 0);

            try {
                int maxValue = a.getInt(R.styleable.NumberSeekbar_max, 100);
                int minValue = a.getInt(R.styleable.NumberSeekbar_min, 0);
                int progressValue = a.getInt(R.styleable.NumberSeekbar_progress, 0);
                numberSeekbarSeekbar.setMax(maxValue);
                numberSeekbarSeekbar.setMin(minValue);
                numberSeekbarSeekbar.setProgress(progressValue);
                numberSeekbarNumber.setText(String.valueOf(progressValue));


                String title = a.getString(R.styleable.NumberSeekbar_text);
                numberSeekbarTitle.setText(title);
            } finally {
                a.recycle();
            }
        }

        numberSeekbarSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                numberSeekbarNumber.setText(String.valueOf(progress));
                if (onNumberSeekbarChangeListener != null){
                    onNumberSeekbarChangeListener.onProgressChanged(seekBar, progress, fromUser);
                }

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (onNumberSeekbarChangeListener != null){
                    onNumberSeekbarChangeListener.onStartTrackingTouch(seekBar);
                }

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (onNumberSeekbarChangeListener != null){
                    onNumberSeekbarChangeListener.onStopTrackingTouch(seekBar);
                }
            }
        });

        numberSeekbarMinus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int progress = numberSeekbarSeekbar.getProgress();
                if (progress > numberSeekbarSeekbar.getMin()) {
                    if (onNumberSeekbarChangeListener != null){
                        onNumberSeekbarChangeListener.onStartTrackingTouch(numberSeekbarSeekbar);
                        numberSeekbarSeekbar.setProgress(progress - 1);
                        onNumberSeekbarChangeListener.onStopTrackingTouch(numberSeekbarSeekbar);
                    }

                }
            }
        });

        numberSeekbarAdd.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int progress = numberSeekbarSeekbar.getProgress();
                if (progress < numberSeekbarSeekbar.getMax()) {
                    numberSeekbarSeekbar.setProgress(progress + 1);
                    if (onNumberSeekbarChangeListener != null){
                        onNumberSeekbarChangeListener.onStopTrackingTouch(numberSeekbarSeekbar);
                    }
                }
            }
        });
    }

    public int getValue() {
        return numberSeekbarSeekbar.getProgress();
    }

    public void setValueWithNoCallBack(int value) {
        OnNumberSeekbarChangeListener onNumberSeekbarChangeListenerTemp = onNumberSeekbarChangeListener;
        onNumberSeekbarChangeListener = null;
        numberSeekbarSeekbar.setProgress(value);
        numberSeekbarNumber.setText(String.valueOf(value));
        onNumberSeekbarChangeListener = onNumberSeekbarChangeListenerTemp;
    }

    public void setTitle(String title){
        numberSeekbarTitle.setText(title);
    }

    public void setProgressMax(int max){
        numberSeekbarSeekbar.setMax(max);
    }

    public void setProgressMin(int min){
        numberSeekbarSeekbar.setMin(min);
    }

    public void setOnNumberSeekbarChangeListener(OnNumberSeekbarChangeListener onNumberSeekbarChangeListener){
        this.onNumberSeekbarChangeListener = onNumberSeekbarChangeListener;
    }


}
