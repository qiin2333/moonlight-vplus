package com.limelight.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.InputType

class StreamView : SurfaceView {
    private var desiredAspectRatio = 0.0
    private var inputCallbacks: InputCallbacks? = null
    private var remotePassword = false
    private var remoteMultiline = false
    private var textInputEnabled = false

    fun setDesiredAspectRatio(aspectRatio: Double) {
        this.desiredAspectRatio = aspectRatio
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        this.inputCallbacks = callbacks
    }

    fun setRemoteTextInputOptions(password: Boolean, multiline: Boolean) {
        remotePassword = password
        remoteMultiline = multiline
    }

    fun setTextInputEnabled(enabled: Boolean) {
        if (textInputEnabled == enabled) return
        textInputEnabled = enabled
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.restartInput(this)
    }

    fun isTextInputEnabled(): Boolean = textInputEnabled

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        // The input connection forwards soft-keyboard text, but strokes on the video
        // belong to the remote host. Do not let the IME take over those pen gestures.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setAutoHandwritingEnabled(false)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // If no fixed aspect ratio has been provided, simply use the default onMeasure() behavior
        if (desiredAspectRatio == 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Based on code from: https://www.buzzingandroid.com/2012/11/easy-measuring-of-custom-views-with-specific-aspect-ratio/
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val measuredHeight: Int
        val measuredWidth: Int
        if (widthSize > heightSize * desiredAspectRatio) {
            measuredHeight = heightSize
            measuredWidth = (measuredHeight * desiredAspectRatio).toInt()
        } else {
            measuredWidth = widthSize
            measuredHeight = (measuredWidth / desiredAspectRatio).toInt()
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks!!.handleKeyDown(event)) {
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (inputCallbacks!!.handleKeyUp(event)) {
                    return true
                }
            }
        }

        return super.onKeyPreIme(keyCode, event)
    }

    override fun onCheckIsTextEditor() = textInputEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!textInputEnabled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            (if (remotePassword) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else 0) or
            (if (remoteMultiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            (if (remoteMultiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE)
        return object : BaseInputConnection(this, false) {
            private var composingText: String? = null

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!textInputEnabled) {
                    composingText = null
                    return true
                }
                composingText = null
                if (!text.isNullOrEmpty()) inputCallbacks?.handleText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!textInputEnabled) {
                    composingText = null
                    return true
                }
                composingText = text?.toString()?.takeIf { it.isNotEmpty() }
                return true
            }

            override fun finishComposingText(): Boolean {
                if (!textInputEnabled) {
                    composingText = null
                    return true
                }
                composingText?.let { inputCallbacks?.handleText(it) }
                composingText = null
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                composingText = null
                if (!textInputEnabled) return true
                repeat(beforeLength.coerceIn(0, 32)) { inputCallbacks?.handleDelete() }
                repeat(afterLength.coerceIn(0, 32)) { inputCallbacks?.handleForwardDelete() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!textInputEnabled) return false
                val callbacks = inputCallbacks ?: return false
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    callbacks.handleKeyDown(event)
                } else {
                    callbacks.handleKeyUp(event)
                }
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (!textInputEnabled) return false
                inputCallbacks?.handleEnter()
                return true
            }
        }
    }

    interface InputCallbacks {
        fun handleKeyUp(event: KeyEvent): Boolean
        fun handleKeyDown(event: KeyEvent): Boolean
        fun handleText(text: String)
        fun handleDelete()
        fun handleForwardDelete()
        fun handleEnter()
    }
}
