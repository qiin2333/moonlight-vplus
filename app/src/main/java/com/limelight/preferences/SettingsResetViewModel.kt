package com.limelight.preferences

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SettingsResetViewModel(application: Application) : AndroidViewModel(application) {
    enum class State { CONFIRM, RUNNING, COMPLETE, FAILED }

    private val mutableState = MutableStateFlow(State.CONFIRM)
    val state = mutableState.asStateFlow()

    /** Starts one confirmed reset and retains its progress or result across configuration changes. */
    fun reset() {
        if (state.value == State.RUNNING || state.value == State.COMPLETE) return
        mutableState.value = State.RUNNING
        viewModelScope.launch {
            try {
                SettingsResetManager.reset(getApplication())
                mutableState.value = State.COMPLETE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("SettingsReset", "Failed to restore default settings", error)
                mutableState.value = State.FAILED
            }
        }
    }

    fun consumeCompletion() {
        mutableState.value = State.CONFIRM
    }
}
