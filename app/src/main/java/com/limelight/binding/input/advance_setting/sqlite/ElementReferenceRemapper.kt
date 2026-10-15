package com.limelight.binding.input.advance_setting.sqlite

/** Remaps profile-local references after import or merge assigns new element IDs. */
internal object ElementReferenceRemapper {
    /** Missing, malformed, sentinel and unresolved child IDs do not become group members. */
    fun group(value: String?, resolveId: (Long) -> Long?): String = buildString {
        append("-1")
        for (child in value.orEmpty().split(',')) {
            val oldId = child.toLongOrNull()?.takeIf { it != -1L } ?: continue
            val newId = resolveId(oldId) ?: continue
            append(',').append(newId)
        }
    }

    /** Rewrites group IDs while preserving labels, other actions and unresolved segments. */
    fun wheel(value: String, resolveId: (Long) -> Long?): String = value.split(',').joinToString(",") { segment ->
        val action = segment.substringBefore('|')
        val oldId = if (action.startsWith("gb")) action.substring(2).toLongOrNull() else null
        val newId = oldId?.let(resolveId)
        if (newId == null) segment else "gb$newId${segment.substring(action.length)}"
    }
}
