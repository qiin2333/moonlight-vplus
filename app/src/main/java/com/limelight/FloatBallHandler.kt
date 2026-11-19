package com.limelight

import com.limelight.preferences.PreferenceConfiguration

/**
 * The floating ball shortcut bubble is removed in the Thor build: physical buttons and the
 * bottom-screen MENU cover the same actions. This inert stub keeps Game's call sites (and the
 * ds4-bottom-trackpad.patch context in Game.kt) unchanged.
 */
@Suppress("UNUSED_PARAMETER")
class FloatBallHandler(game: Game, prefConfig: PreferenceConfiguration) {
    fun initialize() {}
    fun show() {}
    fun hide() {}
    fun suppress() {}
    fun isRequestedVisible(): Boolean = false
    fun release() {}
}
