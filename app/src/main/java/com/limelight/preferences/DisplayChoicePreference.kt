package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

/** A single settings row whose choices are presented by the shared action sheet. */
class DisplayChoicePreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    var enabledValues: Set<String> = emptySet()
        private set
    private var descriptions: Map<String, String> = emptyMap()
    var actionLabel: String? = null
        private set

    val searchLabels: List<CharSequence>
        get() = entries.orEmpty().toList() + descriptions.values + listOfNotNull(actionLabel)

    val currentLabel: String
        get() {
            val label = entry?.toString() ?: value.orEmpty()
            val description = descriptionFor(value)
            return if (description.isNullOrBlank()) label else "$label · $description"
        }

    fun descriptionFor(value: String?): String? = descriptions[value]

    fun setAction(label: String?) {
        actionLabel = label
    }

    fun replaceChoices(
        labels: List<String>,
        values: List<String>,
        selected: String,
        enabledValues: Set<String> = values.toSet(),
        subLabels: List<String?> = emptyList()
    ) {
        require(labels.size == values.size)
        this.enabledValues = enabledValues
        descriptions = values.mapIndexedNotNull { index, value ->
            subLabels.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { value to it }
        }.toMap()
        entries = labels.toTypedArray()
        entryValues = values.toTypedArray()
        value = selected
        notifyChanged()
    }

    /** All input paths share validation; selecting the current value has no side effects. */
    fun selectValue(selected: String): Boolean {
        if (selected !in enabledValues) return false
        if (selected == value) return true
        if (!callChangeListener(selected)) return false
        value = selected
        return true
    }
}
