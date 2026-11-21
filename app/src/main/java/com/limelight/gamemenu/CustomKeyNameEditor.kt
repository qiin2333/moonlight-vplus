package com.limelight.gamemenu

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Keeps the name field in navigation mode until the user explicitly edits it. */
@SuppressLint("ClickableViewAccessibility") // Normal touch and accessibility clicks still reach EditText.
internal class CustomKeyNameEditor(private val nameInput: EditText) {
    var isEditing = false
        private set

    init {
        nameInput.showSoftInputOnFocus = false
        nameInput.setOnClickListener { enter() }
        val touchSlop = ViewConfiguration.get(nameInput.context).scaledTouchSlop
        var tapX = 0f
        var tapY = 0f
        var pendingTap = false
        nameInput.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapX = event.x
                    tapY = event.y
                    pendingTap = !isEditing
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - tapX) > touchSlop ||
                        kotlin.math.abs(event.y - tapY) > touchSlop
                    ) pendingTap = false
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> pendingTap = false
                MotionEvent.ACTION_UP -> {
                    if (pendingTap && kotlin.math.abs(event.x - tapX) <= touchSlop &&
                        kotlin.math.abs(event.y - tapY) <= touchSlop
                    ) enter()
                    pendingTap = false
                }
            }
            false
        }
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) leave()
        }
        nameInput.setOnEditorActionListener { _, actionId, event ->
            when {
                !isEditing -> false
                event == null && actionId == EditorInfo.IME_ACTION_DONE -> {
                    leave()
                    true
                }
                event != null && (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) -> when (event.action) {
                    KeyEvent.ACTION_DOWN -> true
                    KeyEvent.ACTION_UP -> {
                        leave()
                        true
                    }
                    else -> false
                }
                else -> false
            }
        }
    }

    fun enter() {
        if (isEditing) return
        isEditing = true
        nameInput.showSoftInputOnFocus = true
        nameInput.requestFocus()
        nameInput.setSelection(nameInput.text.length)
        nameInput.post {
            if (isEditing && nameInput.hasFocus() && nameInput.isAttachedToWindow) {
                inputMethodManager()?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun leave() {
        if (!isEditing) return
        isEditing = false
        nameInput.showSoftInputOnFocus = false
        inputMethodManager()?.hideSoftInputFromWindow(nameInput.windowToken, 0)
    }

    private fun inputMethodManager(): InputMethodManager? =
        nameInput.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}
