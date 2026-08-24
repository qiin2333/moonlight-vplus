package com.limelight.ui

import android.content.Context
import androidx.core.content.edit

internal enum class FloatBallEdge(val storedValue: String) {
    LEFT("left"),
    RIGHT("right"),
    TOP("top"),
    BOTTOM("bottom"),
    FREE("free");

    companion object {
        fun fromStoredValue(value: String?): FloatBallEdge? = entries.firstOrNull {
            it.storedValue == value
        }
    }
}

internal data class FloatBallStoredPosition(
    val normalized: FloatingButtonNormalizedPosition,
    val edge: FloatBallEdge
)

internal data class FloatBallLegacyPosition(
    val x: Int,
    val y: Int
)

/** Device-local position state; intentionally excluded from configuration sync. */
internal class FloatBallPositionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun customPosition(): FloatBallStoredPosition? {
        if (preferences.getInt(KEY_POSITION_VERSION, 0) != POSITION_VERSION) return null
        val values = preferences.all
        val x = values[KEY_NORMALIZED_X] as? Float ?: return null
        val y = values[KEY_NORMALIZED_Y] as? Float ?: return null
        val edge = FloatBallEdge.fromStoredValue(values[KEY_EDGE] as? String) ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return FloatBallStoredPosition(
            normalized = FloatingButtonNormalizedPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)),
            edge = edge
        )
    }

    fun legacyPosition(): FloatBallLegacyPosition? {
        if (!preferences.contains(LEGACY_KEY_LAST_X) || !preferences.contains(LEGACY_KEY_LAST_Y)) {
            return null
        }
        return FloatBallLegacyPosition(
            x = preferences.getInt(LEGACY_KEY_LAST_X, 0),
            y = preferences.getInt(LEGACY_KEY_LAST_Y, 0)
        )
    }

    fun isHalfShown(): Boolean = preferences.getBoolean(KEY_IS_HALF_SHOWN, false)

    fun saveCustom(position: FloatBallStoredPosition, halfShown: Boolean = false) {
        preferences.edit {
            putInt(KEY_POSITION_VERSION, POSITION_VERSION)
            putFloat(KEY_NORMALIZED_X, position.normalized.x.coerceIn(0f, 1f))
            putFloat(KEY_NORMALIZED_Y, position.normalized.y.coerceIn(0f, 1f))
            putString(KEY_EDGE, position.edge.storedValue)
            putBoolean(KEY_IS_HALF_SHOWN, halfShown)
            remove(LEGACY_KEY_LAST_X)
            remove(LEGACY_KEY_LAST_Y)
        }
    }

    fun setHalfShown(halfShown: Boolean) {
        preferences.edit { putBoolean(KEY_IS_HALF_SHOWN, halfShown) }
    }

    fun clearCustomPosition() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_NAME = "FloatBallPrefs"
        private const val POSITION_VERSION = 2
        private const val KEY_POSITION_VERSION = "positionVersion"
        private const val KEY_NORMALIZED_X = "normalizedX"
        private const val KEY_NORMALIZED_Y = "normalizedY"
        private const val KEY_EDGE = "edge"
        private const val KEY_IS_HALF_SHOWN = "isHalfShow"
        private const val LEGACY_KEY_LAST_X = "lastX"
        private const val LEGACY_KEY_LAST_Y = "lastY"
    }
}
