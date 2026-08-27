package com.limelight.gamemenu

import android.content.Context
import androidx.core.content.edit
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.touch.NativeTouchContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

internal data class TouchPointerSensitivityPreset(
    val id: String,
    val name: String,
    val values: Map<String, String>
)

internal enum class TouchPointerPresetField(val storageKey: String) {
    POINTER_SPEED("pointer_velocity_factor"),
    INITIAL_STABLE_ZONE("seekbar_flat_region_pixels"),
    ZONE_DIVIDER("enhanced_touch_zone_divider"),
    POINTER_ZONE_SIDE("checkbox_enhanced_touch_on_which_side")
}

internal data class TouchPointerSensitivityState(
    val percent: Int,
    val applicable: Boolean,
    val presets: List<TouchPointerSensitivityPreset>,
    val matchingPresetIds: Set<String>
)

internal object TouchPointerSensitivityPolicy {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 500
    const val DPAD_STEP_PERCENT = 10
    const val MAX_PRESETS = 12
    const val MAX_NAME_LENGTH = 20
    const val MIN_STABLE_ZONE_PIXELS = 0
    const val MAX_STABLE_ZONE_PIXELS = 250
    const val MIN_ZONE_DIVIDER = 0
    const val MAX_ZONE_DIVIDER = 100

    fun normalize(percent: Float): Int = percent.roundToInt().coerceIn(
        MIN_PERCENT,
        MAX_PERCENT
    )

    fun runtimeFactor(percent: Int): Float = normalize(percent.toFloat()) / 100f

    fun normalizeName(name: String): String = name.trim()

    fun isValidName(name: String): Boolean {
        val normalized = normalizeName(name)
        return normalized.isNotEmpty() &&
            normalized.codePointCount(0, normalized.length) <= MAX_NAME_LENGTH &&
            normalized.none(Char::isISOControl)
    }

    fun isApplicable(
        enhancedTouch: Boolean,
        trackpad: Boolean,
        nativeMousePointer: Boolean,
        screenDs5Touchpad: Boolean
    ): Boolean = enhancedTouch && !trackpad && !nativeMousePointer && !screenDs5Touchpad
}

internal enum class TouchPointerPresetSaveResult {
    SAVED,
    INVALID_NAME,
    NO_FIELDS,
    LIMIT_REACHED
}

internal class TouchPointerSensitivityPresetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(defaultName: (Int) -> String): List<TouchPointerSensitivityPreset> {
        val json = preferences.getString(PREF_JSON_KEY, null)
        if (!json.isNullOrBlank()) {
            parse(json, defaultName)?.let { return it }
        }
        return migrateLegacy(defaultName)
    }

    fun save(presets: Collection<TouchPointerSensitivityPreset>) {
        val array = JSONArray()
        presets.take(TouchPointerSensitivityPolicy.MAX_PRESETS).forEach { preset ->
            val values = JSONObject()
            preset.values.forEach { (key, value) -> values.put(key, value) }
            array.put(
                JSONObject()
                    .put("id", normalizedUuid(preset.id))
                    .put("name", TouchPointerSensitivityPolicy.normalizeName(preset.name))
                    .put("values", values)
            )
        }
        val root = JSONObject()
            .put("version", STORAGE_VERSION)
            .put("presets", array)
        preferences.edit {
            putString(PREF_JSON_KEY, root.toString())
            remove(PREF_LEGACY_KEY)
        }
    }

    private fun parse(
        json: String,
        defaultName: (Int) -> String
    ): List<TouchPointerSensitivityPreset>? = runCatching {
        val array = JSONObject(json).optJSONArray("presets") ?: return@runCatching emptyList()
        val seenIds = hashSetOf<String>()
        buildList {
            for (index in 0 until minOf(array.length(), TouchPointerSensitivityPolicy.MAX_PRESETS)) {
                val item = array.optJSONObject(index) ?: continue
                val rawName = item.optString("name")
                val name = rawName.takeIf(TouchPointerSensitivityPolicy::isValidName)
                    ?: defaultName(index + 1)
                val valuesObject = item.optJSONObject("values") ?: JSONObject()
                val values = linkedMapOf<String, String>()
                val keys = valuesObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.isBlank()) continue
                    val value = valuesObject.opt(key)
                    if (value != null && value !== JSONObject.NULL) {
                        values[key] = value.toString()
                    }
                }
                if (values.isNotEmpty()) {
                    var id = normalizedUuid(item.optString("id"))
                    while (!seenIds.add(id)) id = UUID.randomUUID().toString()
                    add(
                        TouchPointerSensitivityPreset(
                            id = id,
                            name = name,
                            values = values
                        )
                    )
                }
            }
        }
    }.getOrNull()

    private fun migrateLegacy(
        defaultName: (Int) -> String
    ): List<TouchPointerSensitivityPreset> {
        val values = preferences.getStringSet(PREF_LEGACY_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .filter { it in TouchPointerSensitivityPolicy.MIN_PERCENT..
                TouchPointerSensitivityPolicy.MAX_PERCENT }
            .distinct()
            .sorted()
            .take(TouchPointerSensitivityPolicy.MAX_PRESETS)
        if (values.isEmpty()) return emptyList()

        val migrated = values.mapIndexed { index, percent ->
            TouchPointerSensitivityPreset(
                id = UUID.randomUUID().toString(),
                name = defaultName(index + 1),
                values = mapOf(
                    TouchPointerPresetField.POINTER_SPEED.storageKey to percent.toString()
                )
            )
        }
        save(migrated)
        return migrated
    }

    private fun normalizedUuid(value: String): String = runCatching {
        UUID.fromString(value).toString()
    }.getOrElse { UUID.randomUUID().toString() }

    private companion object {
        const val STORAGE_VERSION = 1
        const val PREF_FILE = "game_menu_prefs"
        const val PREF_JSON_KEY = "touch_pointer_sensitivity_presets_json"
        const val PREF_LEGACY_KEY = "touch_pointer_sensitivity_presets"
    }
}

/** Owns the in-stream pointer-zone speed and optional named configuration snapshots. */
internal class TouchPointerSensitivityController(
    private val game: Game,
    private val presetStore: TouchPointerSensitivityPresetStore =
        TouchPointerSensitivityPresetStore(game)
) {
    private var onStateChanged: ((TouchPointerSensitivityState) -> Unit)? = null
    private var presets = loadPresets()
    private var state = readState()
    private var persistedPercent = state.percent

    fun snapshot(): TouchPointerSensitivityState = state

    fun start(onStateChanged: (TouchPointerSensitivityState) -> Unit) {
        this.onStateChanged = onStateChanged
        presets = loadPresets()
        state = readState()
        persistedPercent = state.percent
        applyPointerSpeedRuntime(state.percent)
        emitState()
    }

    fun refreshApplicability() {
        state = readState()
        emitState()
    }

    /** Returns true when the value crossed a D-pad-sized tick for haptic feedback. */
    fun preview(percent: Float): Boolean {
        val bounded = TouchPointerSensitivityPolicy.normalize(percent)
        val previous = state.percent
        if (bounded == previous) return false

        game.prefConfig.pointerVelocityFactor = bounded.toFloat()
        applyPointerSpeedRuntime(bounded)
        state = readState()
        emitState()
        return bounded / TouchPointerSensitivityPolicy.DPAD_STEP_PERCENT !=
            previous / TouchPointerSensitivityPolicy.DPAD_STEP_PERCENT
    }

    fun persist() {
        if (state.percent == persistedPercent) return
        game.prefConfig.writePreferences(game)
        persistedPercent = state.percent
    }

    fun defaultName(): String {
        var index = 1
        val names = presets.mapTo(hashSetOf()) { it.name }
        while (true) {
            val candidate = game.getString(
                R.string.game_menu_touch_pointer_default_preset_name,
                index
            )
            if (candidate !in names) return candidate
            index++
        }
    }

    fun preset(id: String): TouchPointerSensitivityPreset? = presets.firstOrNull { it.id == id }

    fun selectedFields(preset: TouchPointerSensitivityPreset?): Set<TouchPointerPresetField> =
        preset?.values?.keys.orEmpty().mapNotNullTo(linkedSetOf()) { key ->
            TouchPointerPresetField.entries.firstOrNull { it.storageKey == key }
        }.ifEmpty { TouchPointerPresetField.entries.toSet() }

    fun fieldValue(field: TouchPointerPresetField): String = captureField(field)

    fun savePreset(
        id: String?,
        name: String,
        selectedFields: Set<TouchPointerPresetField>
    ): TouchPointerPresetSaveResult {
        val normalizedName = TouchPointerSensitivityPolicy.normalizeName(name)
        if (!TouchPointerSensitivityPolicy.isValidName(normalizedName)) {
            return TouchPointerPresetSaveResult.INVALID_NAME
        }
        if (selectedFields.isEmpty()) return TouchPointerPresetSaveResult.NO_FIELDS

        val existingIndex = id?.let { target -> presets.indexOfFirst { it.id == target } } ?: -1
        if (existingIndex < 0 && presets.size >= TouchPointerSensitivityPolicy.MAX_PRESETS) {
            return TouchPointerPresetSaveResult.LIMIT_REACHED
        }

        val previous = presets.getOrNull(existingIndex)
        val values = previous?.values.orEmpty().toMutableMap()
        TouchPointerPresetField.entries.forEach { values.remove(it.storageKey) }
        selectedFields.forEach { field -> values[field.storageKey] = captureField(field) }
        val updated = TouchPointerSensitivityPreset(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = normalizedName,
            values = values
        )
        presets = if (existingIndex >= 0) {
            presets.toMutableList().also { it[existingIndex] = updated }
        } else {
            presets + updated
        }
        presetStore.save(presets)
        state = readState()
        emitState()
        return TouchPointerPresetSaveResult.SAVED
    }

    fun applyPreset(id: String): Boolean {
        val preset = preset(id) ?: return false
        var applied = false
        preset.values.forEach { (key, value) ->
            val field = TouchPointerPresetField.entries.firstOrNull { it.storageKey == key }
                ?: return@forEach
            applied = applyField(field, value) || applied
        }
        if (!applied) return false

        game.prefConfig.writePreferences(game)
        persistedPercent = TouchPointerSensitivityPolicy.normalize(
            game.prefConfig.pointerVelocityFactor
        )
        state = readState()
        emitState()
        return true
    }

    fun removePresets(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val remaining = presets.filterNot { it.id in ids }
        val removed = presets.size - remaining.size
        if (removed == 0) return 0

        presets = remaining
        presetStore.save(presets)
        state = readState()
        emitState()
        return removed
    }

    fun dispose() {
        persist()
        onStateChanged = null
    }

    private fun loadPresets(): List<TouchPointerSensitivityPreset> = presetStore.load { index ->
        game.getString(R.string.game_menu_touch_pointer_default_preset_name, index)
    }

    private fun readState(): TouchPointerSensitivityState {
        val percent = TouchPointerSensitivityPolicy.normalize(game.prefConfig.pointerVelocityFactor)
        val matchingIds = presets.filter(::matchesCurrent).mapTo(linkedSetOf()) { it.id }
        return TouchPointerSensitivityState(
            percent = percent,
            applicable = isApplicable(),
            presets = presets,
            matchingPresetIds = matchingIds
        )
    }

    private fun captureField(field: TouchPointerPresetField): String = when (field) {
        TouchPointerPresetField.POINTER_SPEED ->
            TouchPointerSensitivityPolicy.normalize(game.prefConfig.pointerVelocityFactor).toString()
        TouchPointerPresetField.INITIAL_STABLE_ZONE ->
            game.prefConfig.longPressflatRegionPixels.coerceIn(
                TouchPointerSensitivityPolicy.MIN_STABLE_ZONE_PIXELS,
                TouchPointerSensitivityPolicy.MAX_STABLE_ZONE_PIXELS
            ).toString()
        TouchPointerPresetField.ZONE_DIVIDER ->
            game.prefConfig.enhanceTouchZoneDivider.coerceIn(
                TouchPointerSensitivityPolicy.MIN_ZONE_DIVIDER,
                TouchPointerSensitivityPolicy.MAX_ZONE_DIVIDER
            ).toString()
        TouchPointerPresetField.POINTER_ZONE_SIDE ->
            game.prefConfig.enhancedTouchOnWhichSide.toString()
    }

    private fun applyField(field: TouchPointerPresetField, rawValue: String): Boolean = when (field) {
        TouchPointerPresetField.POINTER_SPEED -> rawValue.toIntOrNull()?.let { value ->
            val bounded = value.coerceIn(
                TouchPointerSensitivityPolicy.MIN_PERCENT,
                TouchPointerSensitivityPolicy.MAX_PERCENT
            )
            game.prefConfig.pointerVelocityFactor = bounded.toFloat()
            applyPointerSpeedRuntime(bounded)
            true
        } ?: false
        TouchPointerPresetField.INITIAL_STABLE_ZONE -> rawValue.toIntOrNull()?.let { value ->
            val bounded = value.coerceIn(
                TouchPointerSensitivityPolicy.MIN_STABLE_ZONE_PIXELS,
                TouchPointerSensitivityPolicy.MAX_STABLE_ZONE_PIXELS
            )
            game.prefConfig.longPressflatRegionPixels = bounded
            NativeTouchContext.INTIAL_ZONE_PIXELS = bounded.toFloat()
            true
        } ?: false
        TouchPointerPresetField.ZONE_DIVIDER -> rawValue.toIntOrNull()?.let { value ->
            val bounded = value.coerceIn(
                TouchPointerSensitivityPolicy.MIN_ZONE_DIVIDER,
                TouchPointerSensitivityPolicy.MAX_ZONE_DIVIDER
            )
            game.prefConfig.enhanceTouchZoneDivider = bounded
            NativeTouchContext.ENHANCED_TOUCH_ZONE_DIVIDER = bounded * 0.01f
            true
        } ?: false
        TouchPointerPresetField.POINTER_ZONE_SIDE ->
            rawValue.toBooleanStrictOrNull()?.let { value ->
                game.prefConfig.enhancedTouchOnWhichSide = value
                NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = if (value) -1 else 1
                true
            } ?: false
    }

    private fun matchesCurrent(preset: TouchPointerSensitivityPreset): Boolean {
        val knownValues = preset.values.mapNotNull { (key, value) ->
            TouchPointerPresetField.entries.firstOrNull { it.storageKey == key }?.let { it to value }
        }
        return knownValues.isNotEmpty() && knownValues.all { (field, value) ->
            captureField(field) == value
        }
    }

    private fun isApplicable(): Boolean = TouchPointerSensitivityPolicy.isApplicable(
        enhancedTouch = game.prefConfig.enableEnhancedTouch,
        trackpad = game.prefConfig.touchscreenTrackpad,
        nativeMousePointer = game.prefConfig.enableNativeMousePointer,
        screenDs5Touchpad = game.prefConfig.screenDs5Touchpad
    )

    private fun applyPointerSpeedRuntime(percent: Int) {
        NativeTouchContext.POINTER_VELOCITY_FACTOR =
            TouchPointerSensitivityPolicy.runtimeFactor(percent)
    }

    private fun emitState() {
        onStateChanged?.invoke(state)
    }
}
