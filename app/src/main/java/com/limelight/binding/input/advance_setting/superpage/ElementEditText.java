package com.limelight.binding.input.advance_setting.superpage;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

public class ElementEditText extends EditText {

    public interface OnTextChangedListener{
        void textChanged(String text);
    }

    private OnTextChangedListener onTextChangedListener = new OnTextChangedListener() {
        @Override
        public void textChanged(String text) {

        }
    };
    private TextWatcher textWatcher;

    public ElementEditText(Context context) {
        super(context);
        init();
    }

    public ElementEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ElementEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ElementEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init(){
        textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChangedListener.textChanged(String.valueOf(s));
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        };
        addTextChangedListener(textWatcher);
    }

    public void setTextWithNoTextChangedCallBack(String text){
        removeTextChangedListener(textWatcher);
        setText(text);
        addTextChangedListener(textWatcher);
    }

    public void setOnTextChangedListener(OnTextChangedListener onTextChangedListener){
        this.onTextChangedListener = onTextChangedListener;
    }

}
