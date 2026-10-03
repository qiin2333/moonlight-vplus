package com.limelight.preferences

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
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

    /** 搜索中看过滤结果，否则看运行时资格。侧栏分类用这个。 */
    fun isListed(preference: Preference): Boolean =
        if (activeQuery.trim().isNotEmpty()) preference.isVisible else isRuntimeVisible(preference)

    fun isSearching(): Boolean = activeQuery.trim().isNotEmpty()

    /** 分类名本身命中时，右侧已经展开整组，不必再提供「在分类中查看」。 */
    fun categoryNameMatches(category: Preference): Boolean {
        val query = activeQuery.trim().lowercase(Locale.getDefault())
        return query.isNotEmpty() && matches(category, query)
    }

    /** 标题没命中、但下拉文案命中时，返回用户能看到的选项名。 */
    fun matchedDropdownLabels(preference: Preference): List<String> {
        val query = activeQuery.trim().lowercase(Locale.getDefault())
        if (query.isEmpty() || containsQuery(preference.title, query)) return emptyList()
        return dropdownLabels(preference)
            .map { it.toString() }
            .filter { containsQuery(it, query) }
            .distinct()
    }

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
        onCategoryEligibilityChanged()
    }

    private fun matches(preference: Preference, query: String): Boolean =
        containsQuery(preference.title, query) ||
            containsQuery(preference.summary, query) ||
            preference.key?.lowercase(Locale.getDefault())?.contains(query) == true ||
            dropdownLabels(preference).any { containsQuery(it, query) }

    /** 只匹配用户在下拉里看到的文案，不匹配内部 entryValues。 */
    private fun dropdownLabels(preference: Preference): Array<out CharSequence> = when (preference) {
        is ListPreference -> preference.entries
        is MultiSelectListPreference -> preference.entries
        else -> null
    } ?: emptyArray()

    private fun containsQuery(text: CharSequence?, query: String): Boolean =
        text?.toString()?.lowercase(Locale.getDefault())?.contains(query) == true
}
