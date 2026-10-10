package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.limelight.R
import com.limelight.gamemenu.SensitivityPresetButton

/** Settings choice grid using the same wrapped capsules as the in-game display menu. */
class ChoiceGridPreference(
    context: Context,
    attrs: AttributeSet?
) : Preference(context, attrs) {
    private var labels by mutableStateOf(emptyList<String>())
    private var values by mutableStateOf(emptyList<String>())
    private var selected by mutableStateOf("")
    private var focusedChoice: String? = null
    private var focusRequest by mutableStateOf<Pair<String, Int>?>(null)
    private var focusRequestGeneration = 0

    fun isChoiceFocused(value: String): Boolean = focusedChoice == value

    fun restoreChoiceFocus(value: String) {
        focusRequest = value to ++focusRequestGeneration
    }

    init {
        layoutResource = R.layout.preference_choice_grid
    }

    fun replaceChoices(labels: List<String>, values: List<String>, selected: String) {
        this.labels = labels
        this.values = values
        this.selected = selected
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false
        val host = holder.findViewById(R.id.choice_grid_host) as ComposeView
        host.isFocusable = false
        host.isFocusableInTouchMode = false
        host.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        host.setContent {
            ChoiceGrid(
                labels = labels,
                values = values,
                selected = selected,
                focusRequest = focusRequest,
                onFocusRestored = { focusRequest = null },
                onChoiceFocusChanged = { value, focused ->
                    if (focused) focusedChoice = value
                    else if (focusedChoice == value) focusedChoice = null
                },
                onSelect = { value ->
                    if (callChangeListener(value)) {
                        persistString(value)
                        this.selected = value
                    }
                }
            )
        }
    }
}

@Composable
private fun ChoiceGrid(
    labels: List<String>,
    values: List<String>,
    selected: String,
    focusRequest: Pair<String, Int>?,
    onFocusRestored: () -> Unit,
    onChoiceFocusChanged: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    val requesters = androidx.compose.runtime.remember(values) {
        List(values.size) { FocusRequester() }
    }
    LaunchedEffect(focusRequest) {
        val value = focusRequest?.first ?: return@LaunchedEffect
        withFrameNanos { }
        requesters.getOrNull(values.indexOf(value))?.requestFocus()
        onFocusRestored()
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val longestText = labels.maxByOrNull(String::length).orEmpty()
    val measuredText = textMeasurer.measure(
        longestText,
        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)
    )
    val naturalWidth = with(density) { measuredText.size.width.toDp() } + 24.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val columns = ((maxWidth + 8.dp) / (naturalWidth + 8.dp)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { value ->
                        val index = values.indexOf(value)
                        SensitivityPresetButton(
                            name = labels.getOrElse(index) { value },
                            selected = value == selected,
                            onClick = { onSelect(value) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(requesters[index])
                                .onFocusChanged { onChoiceFocusChanged(value, it.isFocused) }
                                .onPreviewDirection(index, values, columns, requesters, view, onSelect)
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.onPreviewDirection(
    index: Int,
    values: List<String>,
    columns: Int,
    requesters: List<FocusRequester>,
    view: android.view.View,
    onSelect: (String) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val nativeEvent = event.nativeKeyEvent
    if (nativeEvent.keyCode in setOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_BUTTON_A
        )
    ) {
        if (nativeEvent.action == android.view.KeyEvent.ACTION_UP) onSelect(values[index])
        return@onPreviewKeyEvent true
    }
    val backward = nativeEvent.isShiftPressed
    val target = when (nativeEvent.keyCode) {
        android.view.KeyEvent.KEYCODE_TAB -> if (backward) index - 1 else index + 1
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> index - 1
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> index + 1
        android.view.KeyEvent.KEYCODE_DPAD_UP -> index - columns
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> index + columns
        else -> return@onPreviewKeyEvent false
    }
    if (nativeEvent.action != android.view.KeyEvent.ACTION_DOWN || target !in values.indices) {
        return@onPreviewKeyEvent false
    }
    view.isFocusableInTouchMode = false
    requesters[target].requestFocus()
    true
}
