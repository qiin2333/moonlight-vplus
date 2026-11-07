package com.limelight.preferences

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SettingsResetViewModel(application: Application) : AndroidViewModel(application) {
    enum class State { CONFIRM, RUNNING, COMPLETE, FAILED }

    private val mutableState = MutableStateFlow(State.CONFIRM)
    val state = mutableState.asStateFlow()
    private var failurePending = false
    var languageChangedByReset = false
        private set

    /** Starts one confirmed reset and retains its progress or result across configuration changes. */
    fun reset() {
        if (state.value == State.RUNNING || state.value == State.COMPLETE) return
        failurePending = false
        languageChangedByReset = false
        mutableState.value = State.RUNNING
        viewModelScope.launch {
            val preferences = PreferenceManager.getDefaultSharedPreferences(getApplication())
            var languageBefore: String? = null
            try {
                SettingsResetManager.reset(getApplication()) {
                    languageBefore = preferences.getString(
                        PreferenceConfiguration.LANGUAGE_PREF_STRING, PreferenceConfiguration.DEFAULT_LANGUAGE
                    ) ?: PreferenceConfiguration.DEFAULT_LANGUAGE
                }
                mutableState.value = State.COMPLETE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("SettingsReset", "Failed to restore default settings", error)
                languageChangedByReset = languageBefore != null && languageBefore != preferences.getString(
                    PreferenceConfiguration.LANGUAGE_PREF_STRING, PreferenceConfiguration.DEFAULT_LANGUAGE
                )
                failurePending = true
                mutableState.value = State.FAILED
            }
        }
    }

    fun consumeCompletion() {
        mutableState.value = State.CONFIRM
    }

    /** Reconciles each failed attempt once while retaining its message and retry state. */
    fun consumeFailure(): Boolean {
        if (state.value != State.FAILED || !failurePending) return false
        failurePending = false
        return true
    }
}
