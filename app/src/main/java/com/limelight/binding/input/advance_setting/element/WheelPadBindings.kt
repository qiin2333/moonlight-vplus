package com.limelight.binding.input.advance_setting.element

/** Keeps saved actions and display names aligned with the visible wheel sectors. */
internal object WheelPadBindings {
    data class Loaded(val values: MutableList<String>, val names: MutableList<String>)

    fun load(value: String?, names: List<String>?, count: Int): Loaded {
        require(count >= 2)
        val tokens = value?.takeIf { it.isNotEmpty() }?.split(',').orEmpty()
        val values = ArrayList<String>(count)
        val loadedNames = ArrayList<String>(count)
        for (i in 0 until count) {
            // Legacy names were unescaped. Keep the stored slot order rather than
            // guessing whether a comma was a name character or an empty sector.
            val parts = tokens.getOrNull(i)?.split('|', limit = 2)
            values.add(parts?.get(0) ?: "null")
            loadedNames.add(if (names != null) names.getOrElse(i) { "" }
                else parts?.getOrNull(1).orEmpty())
        }
        return Loaded(values, loadedNames)
    }

    fun resize(values: MutableList<String>, names: MutableList<String>, count: Int) {
        require(count >= 2)
        while (values.size > count) values.removeAt(values.lastIndex)
        while (names.size > count) names.removeAt(names.lastIndex)
        while (values.size < count) values.add("null")
        while (names.size < count) names.add("")
    }
}
