package com.limelight.gamemenu

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Keeps the name field in navigation mode until the user explicitly edits it. */
internal class CustomKeyNameEditor(private val nameInput: EditText) {
    var isEditing = false
        private set

    init {
        nameInput.showSoftInputOnFocus = false
        nameInput.setOnClickListener { enter() }
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) leave()
        }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && isEditing) {
                leave()
                true
            } else {
                false
            }
        }
    }

    fun enter() {
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
