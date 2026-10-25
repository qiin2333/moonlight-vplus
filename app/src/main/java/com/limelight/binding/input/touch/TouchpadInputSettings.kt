package com.limelight.binding.input.touch

import com.limelight.Game
import com.limelight.binding.input.advance_setting.element.ElementController
import com.limelight.preferences.TouchpadInputPreferences

/** Current stream values, independent of saved Crown profiles and preset snapshots. */
internal class TouchpadInputSettings(private val game: Game) {
    private var loadedCrownProfileId: Long? = null
    var values = TouchpadInputPreferences.read(game)
        private set

    fun enterCrownProfile(id: Long): Boolean {
        if (loadedCrownProfileId == id) return false
        loadedCrownProfileId = id
        return true
    }

    fun setSensitivity(percent: Int, persist: Boolean = true) {
        values = values.copy(sensitivity = percent.coerceIn(1, 1000))
        apply()
        if (persist) save()
    }

    fun setScrollSpeed(percent: Int, persist: Boolean = true) {
        values = values.copy(scrollSpeed = percent.coerceIn(1, 500))
        apply()
        if (persist) save()
    }

    fun setWheelSpeed(speed: Int) {
        values = values.copy(wheelSpeed = speed.coerceIn(1, 100))
        apply()
        save()
    }

    fun apply() {
        game.relativeTouchContextMap.forEach { context ->
            (context as? RelativeTouchContext)?.apply {
                adjustMsense(values.sensitivity / 100.0)
                adjustScrollSpeed(values.scrollSpeed)
            }
        }
        ElementController.setMouseScrollRepeatInterval(120 - values.wheelSpeed)
    }

    fun save() = TouchpadInputPreferences.save(game, values)
}
