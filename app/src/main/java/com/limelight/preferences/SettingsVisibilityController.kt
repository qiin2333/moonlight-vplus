package com.limelight.preferences

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import java.util.IdentityHashMap
import java.util.Locale

/** Coordinates runtime eligibility with temporary search filtering. */
internal class SettingsVisibilityController(
    private val screenProvider: () -> PreferenceScreen?,
    private val onCategoryEligibilityChanged: () -> Unit,
) {
    private val collapseCounts = mutableMapOf<String, Int>()
    private val runtimeVisibility = IdentityHashMap<Preference, Boolean>()
    private var activeQuery = ""

    fun isRuntimeVisible(preference: Preference): Boolean =
        runtimeVisibility[preference] ?: preference.isVisible

    fun setRuntimeVisible(preference: Preference?, visible: Boolean) {
        preference ?: return
        if (runtimeVisibility.containsKey(preference)) {
            runtimeVisibility[preference] = visible
            applySearch(activeQuery)
        } else {
            preference.isVisible = visible
        }
        if (preference is PreferenceCategory) onCategoryEligibilityChanged()
    }

    fun applySearch(query: String) {
        val screen = screenProvider() ?: return
        activeQuery = query
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        val isSearching = normalizedQuery.isNotEmpty()

        for (index in 0 until screen.preferenceCount) {
            val category = screen.getPreference(index) as? PreferenceCategory ?: continue
            val categoryKey = category.key ?: "category_$index"

            if (isSearching && !collapseCounts.containsKey(categoryKey)) {
                collapseCounts[categoryKey] = category.initialExpandedChildrenCount
                runtimeVisibility[category] = category.isVisible
                for (childIndex in 0 until category.preferenceCount) {
                    val child = category.getPreference(childIndex)
                    runtimeVisibility[child] = child.isVisible
                }
            }

            if (!isSearching) {
                category.isVisible = runtimeVisibility[category] ?: category.isVisible
                for (childIndex in 0 until category.preferenceCount) {
                    val child = category.getPreference(childIndex)
                    child.isVisible = runtimeVisibility[child] ?: child.isVisible
                }
                collapseCounts[categoryKey]?.let { category.initialExpandedChildrenCount = it }
                continue
            }

            category.initialExpandedChildrenCount = Int.MAX_VALUE
            val categoryEligible = isRuntimeVisible(category)
            val categoryMatches = categoryEligible && matches(category, normalizedQuery)
            var anyChildMatches = false
            for (childIndex in 0 until category.preferenceCount) {
                val child = category.getPreference(childIndex)
                val childVisible = categoryEligible &&
                    isRuntimeVisible(child) &&
                    (categoryMatches || matches(child, normalizedQuery))
                child.isVisible = childVisible
                anyChildMatches = anyChildMatches || childVisible
            }
            category.isVisible = categoryMatches || anyChildMatches
        }

        if (!isSearching) {
            collapseCounts.clear()
            runtimeVisibility.clear()
        }
    }

    private fun matches(preference: Preference, query: String): Boolean =
        preference.title?.toString()?.lowercase(Locale.getDefault())?.contains(query) == true ||
            preference.summary?.toString()?.lowercase(Locale.getDefault())?.contains(query) == true ||
            preference.key?.lowercase(Locale.getDefault())?.contains(query) == true
}
