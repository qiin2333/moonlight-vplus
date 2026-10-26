package com.limelight.gamemenu

import com.limelight.Game
import com.limelight.R
import com.limelight.preferences.TouchpadInputPreferences
import java.util.UUID
import kotlin.math.roundToInt

internal enum class TouchpadPresetField(val key: String, val max: Int, val label: Int) {
    SENSITIVITY(TouchpadInputPreferences.SENSITIVITY, 1000, R.string.game_menu_touchpad_sensitivity),
    SCROLL_SPEED(TouchpadInputPreferences.SCROLL_SPEED, 500, R.string.game_menu_touchpad_scroll_speed);

    fun normalize(value: String): Int? = value.toIntOrNull()?.coerceIn(1, max)
}

internal data class TouchpadSensitivityState(
    val pointer: TouchPointerSensitivityState = TouchPointerSensitivityState(100, false, emptyList(), null),
    val scrollPercent: Int = 100
)

internal class TouchpadSensitivityController(private val game: Game) {
    private val store = TouchPointerSensitivityPresetStore(game, TouchpadInputPreferences.PRESETS_FILE_NAME)
    private var presets = loadPresets()
    private var activeId = store.loadActivePresetId()
    private var listener: ((TouchpadSensitivityState) -> Unit)? = null
    private var pending = false

    fun snapshot(): TouchpadSensitivityState {
        val current = game.touchpadInputSettings.values
        val active = presets.firstOrNull { it.id == activeId }
        if (activeId != null && (active == null || !matchesCurrent(active))) {
            activeId = null
            store.saveActivePresetId(null)
        }
        val prefs = game.prefConfig
        return TouchpadSensitivityState(
            TouchPointerSensitivityState(
                current.sensitivity,
                prefs.touchscreenTrackpad && !prefs.enableNativeMousePointer && !prefs.screenDs5Touchpad,
                presets,
                activeId
            ),
            current.scrollSpeed
        )
    }

    fun start(onChanged: (TouchpadSensitivityState) -> Unit) {
        presets = loadPresets()
        activeId = store.loadActivePresetId()
        listener = onChanged
        refresh()
    }

    fun refresh() {
        listener?.invoke(snapshot())
    }

    fun preview(field: TouchpadPresetField, value: Float): Boolean {
        val percent = value.roundToInt().coerceIn(1, field.max)
        val previous = current(field)
        if (percent == previous) return false
        when (field) {
            TouchpadPresetField.SENSITIVITY -> game.touchpadInputSettings.setSensitivity(percent, false)
            TouchpadPresetField.SCROLL_SPEED -> game.touchpadInputSettings.setScrollSpeed(percent, false)
        }
        pending = true
        refresh()
        return previous / 10 != percent / 10
    }

    fun persist() {
        if (!pending) return
        game.touchpadInputSettings.save()
        pending = false
    }

    fun dispose() {
        persist()
        listener = null
    }

    fun preset(id: String) = presets.firstOrNull { it.id == id }

    fun defaultName(): String {
        var index = 1
        while (true) {
            val name = game.getString(R.string.game_menu_touch_pointer_default_preset_name, index++)
            if (presets.none { it.name == name }) return name
        }
    }

    fun fieldValue(field: TouchpadPresetField, preset: TouchPointerSensitivityPreset?): String =
        preset?.values?.get(field.key)?.let(field::normalize)?.toString() ?: current(field).toString()

    fun savePreset(
        id: String?,
        name: String,
        fields: Map<TouchpadPresetField, String>
    ): TouchPointerPresetSaveResult {
        if (!TouchPointerSensitivityPolicy.isValidName(name)) return TouchPointerPresetSaveResult.INVALID_NAME
        val values = fields.mapNotNull { (field, value) ->
            field.normalize(value)?.let { field.key to it.toString() }
        }.toMap()
        if (values.isEmpty()) return TouchPointerPresetSaveResult.NO_FIELDS
        val previous = id?.let(::preset)
        if (previous == null && presets.size >= TouchPointerSensitivityPolicy.MAX_PRESETS) {
            return TouchPointerPresetSaveResult.LIMIT_REACHED
        }
        val unknown = previous?.values.orEmpty().filterKeys { key ->
            TouchpadPresetField.entries.none { it.key == key }
        }
        val updated = TouchPointerSensitivityPreset(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            values = unknown + values,
            updatedAt = System.currentTimeMillis()
        )
        presets = if (previous == null) presets + updated else presets.map {
            if (it.id == id) updated else it
        }
        store.save(presets)
        refresh()
        return TouchPointerPresetSaveResult.SAVED
    }

    fun applyPreset(id: String): Boolean {
        val preset = preset(id) ?: return false
        val fields = TouchpadPresetField.entries.mapNotNull { field ->
            preset.values[field.key]?.let(field::normalize)?.let { field to it }
        }
        if (fields.isEmpty()) return false
        fields.forEach { (field, value) -> preview(field, value.toFloat()) }
        persist()
        activeId = id
        store.saveActivePresetId(id)
        refresh()
        return true
    }

    fun removePresets(ids: Set<String>): Int {
        val removed = presets.count { it.id in ids }
        if (removed == 0) return 0
        store.markDeleted(ids)
        presets = presets.filterNot { it.id in ids }
        store.save(presets)
        refresh()
        return removed
    }

    private fun current(field: TouchpadPresetField): Int = when (field) {
        TouchpadPresetField.SENSITIVITY -> game.touchpadInputSettings.values.sensitivity
        TouchpadPresetField.SCROLL_SPEED -> game.touchpadInputSettings.values.scrollSpeed
    }

    private fun matchesCurrent(preset: TouchPointerSensitivityPreset): Boolean {
        val fields = TouchpadPresetField.entries.filter { field ->
            preset.values[field.key]?.let(field::normalize) != null
        }
        return fields.isNotEmpty() && fields.all { field ->
            field.normalize(preset.values.getValue(field.key)) == current(field)
        }
    }

    private fun loadPresets() = store.load {
        game.getString(R.string.game_menu_touch_pointer_default_preset_name, it)
    }
}
