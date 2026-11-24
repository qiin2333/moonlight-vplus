package com.limelight.preferences

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import java.util.IdentityHashMap
import java.util.Locale

/** Coordinates runtime eligibility with temporary search filtering. */
internal class SettingsVisibilityController(
    private val screenProvider: () -> PreferenceScreen?,
    private val onCategoryEligibilityChanged: () -> Unit,
    private val onSearchResultClick: (PreferenceScreen, Preference, String) -> Unit,
) {
    private var filtering = false
    private val runtimeVisibility = IdentityHashMap<Preference, Boolean>()
    private var activeQuery = ""
    private data class SearchResult(
        val parent: PreferenceCategory,
        val screen: PreferenceScreen,
        val target: Preference,
        val ancestors: List<PreferenceGroup>,
        val row: Preference,
    )
    private val searchResults = ArrayList<SearchResult>()

    fun isRuntimeVisible(preference: Preference): Boolean =
        runtimeVisibility[preference] ?: preference.isVisible

    /** 搜索中看过滤结果，否则看运行时资格。侧栏分类用这个。 */
    fun isListed(preference: Preference): Boolean =
        if (isSearching()) preference.isVisible else isRuntimeVisible(preference)

    fun isSearching(): Boolean = activeQuery.trim().isNotEmpty()

    fun refreshSearch() = applySearch(activeQuery)

    /** Find a matching setting when opening a screen that also matched directly. */
    fun matchingDescendant(screen: PreferenceScreen): Preference? {
        val query = activeQuery.trim().lowercase(Locale.getDefault())
        if (query.isEmpty()) return null
        return matchingDescendant(screen, query)
    }

    private fun matchingDescendant(group: PreferenceGroup, query: String): Preference? {
        for (index in 0 until group.preferenceCount) {
            val child = group.getPreference(index)
            if (!isRuntimeVisible(child)) continue
            if (child is PreferenceGroup) {
                if (containsQuery(child.title, query)) firstVisibleLeaf(child)?.let { return it }
                matchingDescendant(child, query)?.let { return it }
            } else if (matches(child, query)) {
                return child
            }
        }
        return null
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
            if (filtering) applySearch(activeQuery)
        }
        if (preference is PreferenceCategory) onCategoryEligibilityChanged()
    }

    fun applySearch(query: String) {
        val screen = screenProvider() ?: return
        activeQuery = query
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        val isSearching = normalizedQuery.isNotEmpty()

        if (isSearching && !filtering) createSearchResults(screen)
        if (!isSearching && filtering) removeSearchResults()

        for (index in 0 until screen.preferenceCount) {
            val category = screen.getPreference(index) as? PreferenceCategory ?: continue
            if (isSearching && !filtering) {
                runtimeVisibility[category] = category.isVisible
                for (childIndex in 0 until category.preferenceCount) {
                    val child = category.getPreference(childIndex)
                    if (searchResults.any { it.row === child }) continue
                    runtimeVisibility[child] = child.isVisible
                }
            }

            if (!isSearching) {
                category.isVisible = runtimeVisibility[category] ?: category.isVisible
                for (childIndex in 0 until category.preferenceCount) {
                    val child = category.getPreference(childIndex)
                    child.isVisible = runtimeVisibility[child] ?: child.isVisible
                }
                continue
            }

            val categoryEligible = isRuntimeVisible(category)
            val categoryMatches = categoryEligible && matches(category, normalizedQuery)
            var anyChildMatches = false
            for (childIndex in 0 until category.preferenceCount) {
                val child = category.getPreference(childIndex)
                val result = searchResults.firstOrNull { it.row === child }
                val childVisible = if (result != null) {
                    val labels = matchedDropdownLabels(result.target)
                    val breadcrumb = (listOf(result.screen) + result.ancestors)
                        .mapNotNull { it.title?.toString()?.takeIf { title -> title.isNotBlank() } }
                        .joinToString(" › ")
                    val summary = if (labels.isEmpty()) breadcrumb else {
                        val context = result.row.context
                        val separator = context.getString(com.limelight.R.string.settings_search_match_separator)
                        val note = context.getString(com.limelight.R.string.settings_search_contains, labels.joinToString(separator))
                        "$breadcrumb · $note"
                    }
                    if (result.row.summary?.toString() != summary) result.row.summary = summary
                    categoryEligible && isRuntimeVisible(result.screen) &&
                        result.ancestors.all(::isRuntimeVisible) && isRuntimeVisible(result.target) &&
                        (matches(result.target, normalizedQuery) || result.ancestors.any {
                            containsQuery(it.title, normalizedQuery)
                        })
                } else {
                    categoryEligible && isRuntimeVisible(child) &&
                        (categoryMatches || matchesDirectly(child, normalizedQuery))
                }
                child.isVisible = childVisible
                anyChildMatches = anyChildMatches || childVisible
            }
            category.isVisible = categoryMatches || anyChildMatches
        }

        if (!isSearching) {
            runtimeVisibility.clear()
        }
        filtering = isSearching
        onCategoryEligibilityChanged()
    }

    private fun firstVisibleLeaf(group: PreferenceGroup): Preference? {
        for (index in 0 until group.preferenceCount) {
            val child = group.getPreference(index)
            if (!isRuntimeVisible(child)) continue
            if (child is PreferenceGroup) firstVisibleLeaf(child)?.let { return it }
            else return child
        }
        return null
    }

    private fun matches(preference: Preference, query: String): Boolean =
        matchesDirectly(preference, query) ||
            (preference is PreferenceScreen && matchingDescendant(preference, query) != null)

    private fun matchesDirectly(preference: Preference, query: String): Boolean =
        containsQuery(preference.title, query) ||
            containsQuery(preference.summary, query) ||
            preference.key?.lowercase(Locale.getDefault())?.contains(query) == true ||
            dropdownLabels(preference).any { containsQuery(it, query) }

    /** Search rows are navigation-only proxies; the real preferences remain in their screen. */
    private fun createSearchResults(root: PreferenceScreen) {
        fun addChildren(category: PreferenceCategory, screen: PreferenceScreen,
                        group: PreferenceGroup, ancestors: List<PreferenceGroup>) {
            for (index in 0 until group.preferenceCount) {
                val child = group.getPreference(index)
                if (child is PreferenceGroup) {
                    addChildren(category, screen, child, ancestors + child)
                } else {
                    val screenKey = screen.key ?: continue
                    val childKey = child.key ?: continue
                    val row = Preference(category.context).apply {
                        key = "settings.search.result.$screenKey.$childKey"
                        title = child.title
                        summary = screen.title
                        isPersistent = false
                        isIconSpaceReserved = false
                        layoutResource = com.limelight.R.layout.preference_material_compact
                        widgetLayoutResource = com.limelight.R.layout.preference_widget_navigation
                        order = screen.order
                        isVisible = false
                        setOnPreferenceClickListener {
                            onSearchResultClick(screen, child, key)
                            true
                        }
                    }
                    searchResults.add(SearchResult(category, screen, child, ancestors, row))
                }
            }
        }
        for (index in 0 until root.preferenceCount) {
            val category = root.getPreference(index) as? PreferenceCategory ?: continue
            for (childIndex in 0 until category.preferenceCount) {
                val screen = category.getPreference(childIndex) as? PreferenceScreen ?: continue
                addChildren(category, screen, screen, emptyList())
            }
        }
        searchResults.forEach { it.parent.addPreference(it.row) }
    }

    private fun removeSearchResults() {
        searchResults.forEach { it.parent.removePreference(it.row) }
        searchResults.clear()
    }

    /** 只匹配用户在下拉里看到的文案，不匹配内部 entryValues。 */
    private fun dropdownLabels(preference: Preference): Array<out CharSequence> = when (preference) {
        is DisplayChoicePreference -> preference.searchLabels.toTypedArray()
        is ListPreference -> preference.entries
        is MultiSelectListPreference -> preference.entries
        else -> null
    } ?: emptyArray()

    private fun containsQuery(text: CharSequence?, query: String): Boolean =
        text?.toString()?.lowercase(Locale.getDefault())?.replace('×', 'x')
            ?.contains(query.replace('×', 'x')) == true
}
