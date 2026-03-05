package com.limelight.binding.input.advance_setting.superpage

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.widget.EditText

class ElementEditText : EditText {

    fun interface OnTextChangedListener {
        fun textChanged(text: String)
    }

    private var onTextChangedListener: OnTextChangedListener = OnTextChangedListener { }
    private lateinit var textWatcher: TextWatcher

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    private fun init() {
        textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onTextChangedListener.textChanged(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        }
        addTextChangedListener(textWatcher)
    }

    fun setTextWithNoTextChangedCallBack(text: String) {
        removeTextChangedListener(textWatcher)
        setText(text)
        addTextChangedListener(textWatcher)
    }

    fun setOnTextChangedListener(listener: OnTextChangedListener) {
        this.onTextChangedListener = listener
    }
}
